package com.example.tunnel

import android.util.Log
import com.example.model.DnsProvider
import com.example.model.RelayEndpoint
import com.example.model.RelayType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class LocalSocks5Relay(
    initialRelayPool: List<RelayEndpoint>,
    private val dnsProvider: DnsProvider,
    private val allowDirectUdpFallback: Boolean,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean
) {
    private val tag = "LocalSocks5Relay"
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile
    private var currentRelayPool: List<RelayEndpoint> = initialRelayPool
    private val activeEndpointIndex = AtomicInteger(0)
    private val consecutivePoolFailures = AtomicInteger(0)
    private val proxyTcp53Failures = AtomicInteger(0)
    private val proxyDohFailures = AtomicInteger(0)
    private val udp53Timeouts = AtomicInteger(0)

    fun updateRelayPool(newPool: List<RelayEndpoint>) {
        if (newPool.isNotEmpty()) {
            currentRelayPool = newPool
            activeEndpointIndex.set(0)
            consecutivePoolFailures.set(0)
        }
    }

    fun getConsecutiveFailures(): Int = consecutivePoolFailures.get()

    fun currentActiveEndpoint(): RelayEndpoint? {
        val pool = currentRelayPool
        if (pool.isEmpty()) return null
        val idx = activeEndpointIndex.get().coerceIn(0, pool.lastIndex)
        return pool[idx]
    }

    private data class CachedDnsEntry(val response: ByteArray, val expiresAtMs: Long)
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()

    private val udpControlSockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
    private val tcpControlSockets = ConcurrentHashMap.newKeySet<Socket>()

    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = Socket().also { protectSocket(it) }
                override fun createSocket(host: String?, port: Int): Socket =
                    Socket().also {
                        protectSocket(it)
                        it.connect(InetSocketAddress(host, port), 3000)
                    }
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                    createSocket(host, port)
                override fun createSocket(host: InetAddress?, port: Int): Socket =
                    Socket().also {
                        protectSocket(it)
                        it.connect(InetSocketAddress(host, port), 3000)
                    }
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                    createSocket(address, port)
            })
            .build()
    }

    fun start(): Int {
        val ss = ServerSocket(0, 512, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        acceptJob = scope.launch {
            while (isActive && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    client.tcpNoDelay = true
                    launch { handleClient(client) }
                } catch (_: IOException) {
                    break
                } catch (e: Exception) {
                    Log.w(tag, "Accept error: ${e.message}")
                }
            }
        }
        return ss.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        udpControlSockets.forEach { runCatching { it.close() } }
        udpControlSockets.clear()
        tcpControlSockets.forEach { runCatching { it.close() } }
        tcpControlSockets.clear()
        acceptJob?.cancel()
        scope.cancel()
        runCatching { dispatcher.close() }
    }

    private suspend fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15000
            val clientIn = BufferedInputStream(client.getInputStream())
            val clientOut = client.getOutputStream()

            // 1. SOCKS5 Greeting
            val greetingHeader = ByteArray(2)
            if (!RelayVerifier.readFully(clientIn, greetingHeader)) return
            if (greetingHeader[0] != 0x05.toByte()) return
            val nMethods = greetingHeader[1].toInt() and 0xFF
            if (nMethods > 0) {
                val methods = ByteArray(nMethods)
                if (!RelayVerifier.readFully(clientIn, methods)) return
            }
            clientOut.write(byteArrayOf(0x05, 0x00))
            clientOut.flush()

            // 2. SOCKS5 Request
            val reqHeader = ByteArray(4)
            if (!RelayVerifier.readFully(clientIn, reqHeader)) return
            val cmd = reqHeader[1].toInt() and 0xFF
            val atyp = reqHeader[3].toInt() and 0xFF

            val destHost: String
            when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4)
                    if (!RelayVerifier.readFully(clientIn, addr)) return
                    destHost = InetAddress.getByAddress(addr).hostAddress ?: return
                }
                0x03 -> {
                    val lenByte = ByteArray(1)
                    if (!RelayVerifier.readFully(clientIn, lenByte)) return
                    val len = lenByte[0].toInt() and 0xFF
                    val addr = ByteArray(len)
                    if (!RelayVerifier.readFully(clientIn, addr)) return
                    destHost = String(addr, Charsets.UTF_8)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    if (!RelayVerifier.readFully(clientIn, addr)) return
                    destHost = InetAddress.getByAddress(addr).hostAddress ?: return
                }
                else -> {
                    sendSocks5Error(clientOut, 0x08)
                    return
                }
            }

            val portBytes = ByteArray(2)
            if (!RelayVerifier.readFully(clientIn, portBytes)) return
            val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            if (cmd == 0x03) {
                client.soTimeout = 0
                handleUdpAssociate(client, clientIn, clientOut)
                return
            }

            if (cmd != 0x01) {
                sendSocks5Error(clientOut, 0x07)
                return
            }

            // Route ALL TCP (including TCP DNS port 53 if reachable) through upstream VPN relay pool
            upstream = if (destPort == 53) {
                connectViaRelayPool(dnsProvider.primaryIp, 53) ?: connectDirectProtected(dnsProvider.primaryIp, 53)
            } else {
                connectViaRelayPool(destHost, destPort)
            }

            if (upstream == null) {
                sendSocks5Error(clientOut, 0x05)
                return
            }

            client.soTimeout = 300000
            upstream.soTimeout = 300000

            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()

            val jobA = scope.launch {
                pump(clientIn, upOut)
                runCatching { upstream.shutdownOutput() }
            }
            val jobB = scope.launch {
                pump(upIn, clientOut)
                runCatching { client.shutdownOutput() }
            }
            jobA.join()
            jobB.join()
        } catch (_: Exception) {
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun connectDirectProtected(host: String, port: Int): Socket? {
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.tcpNoDelay = true
            protectSocket(sock)
            sock.connect(InetSocketAddress(host, port), 2200)
            sock
        } catch (_: Exception) {
            runCatching { sock?.close() }
            null
        }
    }

    /**
     * Connects to destination via the active sticky VPN endpoint.
     * Preserves session IP consistency for HTTPS/OAuth logins while still allowing fallback
     * for custom game/auth ports if an HTTP proxy rejects non-443 ports.
     */
    private fun connectViaRelayPool(destHost: String, destPort: Int, connectTimeoutMs: Int = 4000): Socket? {
        val pool = currentRelayPool
        if (pool.isEmpty()) return null
        val startIdx = activeEndpointIndex.get()
        val count = pool.size

        for (attempt in 0 until count) {
            val idx = (startIdx + attempt) % count
            val endpoint = pool[idx]
            val (sock, proxyReachable) = tryConnectEndpoint(endpoint, destHost, destPort, connectTimeoutMs)
            if (sock != null) {
                consecutivePoolFailures.set(0)
                // Only rotate sticky primary index if the primary proxy itself failed/dropped web traffic
                if (attempt > 0 && (!proxyReachable || destPort == 443 || destPort == 80)) {
                    activeEndpointIndex.set(idx)
                }
                return sock
            }
        }
        consecutivePoolFailures.incrementAndGet()
        return null
    }

    private fun tryConnectEndpoint(
        endpoint: RelayEndpoint,
        destHost: String,
        destPort: Int,
        connectTimeoutMs: Int
    ): Pair<Socket?, Boolean> {
        var socket: Socket? = null
        var proxyTcpConnected = false
        return try {
            socket = Socket()
            socket.tcpNoDelay = true
            protectSocket(socket)
            socket.connect(InetSocketAddress(endpoint.ip, endpoint.port), connectTimeoutMs)
            proxyTcpConnected = true
            socket.soTimeout = 6000

            val ok = when (endpoint.type) {
                RelayType.SOCKS5 -> performSocks5Connect(socket, destHost, destPort)
                RelayType.SOCKS4 -> performSocks4Connect(socket, destHost, destPort)
                RelayType.HTTP_CONNECT -> performHttpConnect(socket, destHost, destPort)
            }
            if (ok) {
                socket to true
            } else {
                runCatching { socket.close() }
                null to proxyTcpConnected
            }
        } catch (_: Exception) {
            runCatching { socket?.close() }
            null to proxyTcpConnected
        }
    }

    private fun performSocks5Connect(socket: Socket, destHost: String, destPort: Int): Boolean {
        val out = socket.getOutputStream()
        val input = BufferedInputStream(socket.getInputStream())

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val methodReply = ByteArray(2)
        if (!RelayVerifier.readFully(input, methodReply)) return false
        if (methodReply[0] != 0x05.toByte() || methodReply[1] != 0x00.toByte()) return false

        val isNumericIpv4 = destHost.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val isNumericIpv6 = destHost.contains(":")

        val portHi = ((destPort shr 8) and 0xFF).toByte()
        val portLo = (destPort and 0xFF).toByte()

        val reqBytes = if (isNumericIpv4) {
            val addrIp = InetAddress.getByName(destHost).address
            byteArrayOf(0x05, 0x01, 0x00, 0x01) + addrIp + byteArrayOf(portHi, portLo)
        } else if (isNumericIpv6) {
            val addrIp = InetAddress.getByName(destHost).address
            byteArrayOf(0x05, 0x01, 0x00, 0x04) + addrIp + byteArrayOf(portHi, portLo)
        } else {
            val hostBytes = destHost.toByteArray(Charsets.UTF_8)
            byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) + hostBytes + byteArrayOf(portHi, portLo)
        }

        out.write(reqBytes)
        out.flush()

        val respHeader = ByteArray(4)
        if (!RelayVerifier.readFully(input, respHeader)) return false
        if (respHeader[0] != 0x05.toByte() || respHeader[1] != 0x00.toByte()) return false

        when (respHeader[3].toInt() and 0xFF) {
            0x01 -> if (!RelayVerifier.readFully(input, ByteArray(6))) return false
            0x03 -> {
                val lenB = ByteArray(1)
                if (!RelayVerifier.readFully(input, lenB)) return false
                if (!RelayVerifier.readFully(input, ByteArray((lenB[0].toInt() and 0xFF) + 2))) return false
            }
            0x04 -> if (!RelayVerifier.readFully(input, ByteArray(18))) return false
            else -> return false
        }
        return true
    }

    private fun performSocks4Connect(socket: Socket, destHost: String, destPort: Int): Boolean {
        val out = socket.getOutputStream()
        val input = BufferedInputStream(socket.getInputStream())

        val portHi = ((destPort shr 8) and 0xFF).toByte()
        val portLo = (destPort and 0xFF).toByte()
        val isNumericIpv4 = destHost.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

        val reqBytes = if (isNumericIpv4) {
            val addrIp = InetAddress.getByName(destHost).address
            byteArrayOf(0x04, 0x01, portHi, portLo) + addrIp + byteArrayOf(0x00)
        } else {
            // SOCKS4a domain resolution
            val hostBytes = destHost.toByteArray(Charsets.UTF_8)
            byteArrayOf(0x04, 0x01, portHi, portLo, 0x00, 0x00, 0x00, 0x01, 0x00) +
                hostBytes + byteArrayOf(0x00)
        }
        out.write(reqBytes)
        out.flush()

        val reply = ByteArray(8)
        if (!RelayVerifier.readFully(input, reply)) return false
        return reply[1] == 0x5A.toByte()
    }

    private fun performHttpConnect(socket: Socket, destHost: String, destPort: Int): Boolean {
        val out = socket.getOutputStream()
        val input = BufferedInputStream(socket.getInputStream())

        val hostHeader = if (destHost.contains(":")) "[$destHost]:$destPort" else "$destHost:$destPort"
        val req = "CONNECT $hostHeader HTTP/1.1\r\nHost: $hostHeader\r\nProxy-Connection: Keep-Alive\r\n\r\n"
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = RelayVerifier.readAsciiLine(input) ?: return false
        if (!statusLine.contains(" 200 ") && !statusLine.startsWith("HTTP/1.1 200") && !statusLine.startsWith("HTTP/1.0 200")) {
            return false
        }
        while (true) {
            val line = RelayVerifier.readAsciiLine(input) ?: return false
            if (line.isEmpty()) break
        }
        return true
    }

    private inner class UpstreamUdpAssociation(
        val controlSock: Socket,
        val udpSock: DatagramSocket,
        val targetAddr: InetSocketAddress,
        val readerJob: Job
    ) {
        fun isAlive(): Boolean = !controlSock.isClosed && !udpSock.isClosed
        fun close() {
            readerJob.cancel()
            udpControlSockets.remove(udpSock)
            tcpControlSockets.remove(controlSock)
            runCatching { udpSock.close() }
            runCatching { controlSock.close() }
        }
    }

    private inner class DirectUdpNatEntry(
        val socket: DatagramSocket,
        val destAddress: InetAddress,
        val destPort: Int,
        val readerJob: Job,
        @Volatile var lastActiveMs: Long = System.currentTimeMillis()
    ) {
        fun close() {
            readerJob.cancel()
            udpControlSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun handleUdpAssociate(tcpControl: Socket, controlIn: InputStream, clientOut: OutputStream) {
        val localUdpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        udpControlSockets.add(localUdpRelay)

        var upstreamAssoc: UpstreamUdpAssociation? = null
        val upstreamLock = Any()
        val directNatSessions = ConcurrentHashMap<String, DirectUdpNatEntry>()
        val lastClientAddr = AtomicReference<SocketAddress?>(null)

        val reaperJob = scope.launch {
            while (isActive && !localUdpRelay.isClosed) {
                delay(15000L)
                val cutoff = System.currentTimeMillis() - 60000L
                val iterator = directNatSessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value.lastActiveMs < cutoff || entry.value.socket.isClosed) {
                        entry.value.close()
                        iterator.remove()
                    }
                }
            }
        }

        fun getOrCreateUpstreamAssociation(clientAddr: SocketAddress): UpstreamUdpAssociation? {
            lastClientAddr.set(clientAddr)
            val existing = upstreamAssoc
            if (existing != null && existing.isAlive()) return existing

            synchronized(upstreamLock) {
                val again = upstreamAssoc
                if (again != null && again.isAlive()) return again
                upstreamAssoc?.close()
                upstreamAssoc = null

                val udpCandidates = currentRelayPool.filter { it.supportsUdp && it.type == RelayType.SOCKS5 }
                for (endpoint in udpCandidates) {
                    val assoc = createUpstreamUdpAssociation(endpoint, localUdpRelay) { lastClientAddr.get() }
                    if (assoc != null) {
                        upstreamAssoc = assoc
                        return assoc
                    }
                }
                return null
            }
        }

        try {
            val boundPort = localUdpRelay.localPort
            clientOut.write(
                byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((boundPort shr 8) and 0xFF).toByte(),
                    (boundPort and 0xFF).toByte()
                )
            )
            clientOut.flush()

            val udpJob = scope.launch {
                val buf = ByteArray(8192)
                while (isActive && !localUdpRelay.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    localUdpRelay.receive(pkt)
                    val len = pkt.length
                    if (len < 10) continue
                    val dataCopy = buf.copyOf(len)
                    val sourceAddr = pkt.socketAddress
                    lastClientAddr.set(sourceAddr)
                    processSocks5UdpPacket(
                        localUdpRelay = localUdpRelay,
                        clientAddr = sourceAddr,
                        packet = dataCopy,
                        getUpstreamAssoc = { getOrCreateUpstreamAssociation(sourceAddr) },
                        directNatSessions = directNatSessions
                    )
                }
            }

            val dummy = ByteArray(64)
            while (controlIn.read(dummy) != -1) {
                // Keep UDP association open until hev-socks5-tunnel closes control TCP
            }
            udpJob.cancel()
        } catch (_: Exception) {
        } finally {
            reaperJob.cancel()
            upstreamAssoc?.close()
            directNatSessions.values.forEach { it.close() }
            directNatSessions.clear()
            udpControlSockets.remove(localUdpRelay)
            runCatching { localUdpRelay.close() }
        }
    }

    private fun createUpstreamUdpAssociation(
        endpoint: RelayEndpoint,
        localUdpRelay: DatagramSocket,
        clientAddrProvider: () -> SocketAddress?
    ): UpstreamUdpAssociation? {
        var ctrl: Socket? = null
        var udpSock: DatagramSocket? = null
        return try {
            ctrl = Socket()
            ctrl.tcpNoDelay = true
            protectSocket(ctrl)
            ctrl.connect(InetSocketAddress(endpoint.ip, endpoint.port), 3000)
            ctrl.soTimeout = 5000

            val out = ctrl.getOutputStream()
            val input = BufferedInputStream(ctrl.getInputStream())
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val methodReply = ByteArray(2)
            if (!RelayVerifier.readFully(input, methodReply)) return null
            if (methodReply[0] != 0x05.toByte() || methodReply[1] != 0x00.toByte()) return null

            out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            val header = ByteArray(4)
            if (!RelayVerifier.readFully(input, header)) return null
            if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) return null

            val bndHost = when (header[3].toInt() and 0xFF) {
                0x01 -> ByteArray(4).also { if (!RelayVerifier.readFully(input, it)) return null }
                    .let { InetAddress.getByAddress(it).hostAddress }
                0x03 -> {
                    val len = input.read()
                    if (len <= 0) return null
                    val dom = ByteArray(len)
                    if (!RelayVerifier.readFully(input, dom)) return null
                    String(dom, Charsets.UTF_8)
                }
                0x04 -> ByteArray(16).also { if (!RelayVerifier.readFully(input, it)) return null }
                    .let { InetAddress.getByAddress(it).hostAddress }
                else -> return null
            }
            val portB = ByteArray(2)
            if (!RelayVerifier.readFully(input, portB)) return null
            val bndPort = ((portB[0].toInt() and 0xFF) shl 8) or (portB[1].toInt() and 0xFF)
            if (bndPort !in 1..65535) return null

            val relayIp = RelayVerifier.resolveUdpRelayHost(bndHost, endpoint.ip)
            ctrl.soTimeout = 0
            udpSock = DatagramSocket()
            protectDatagram(udpSock)

            tcpControlSockets.add(ctrl)
            udpControlSockets.add(udpSock)

            val finalCtrl = ctrl
            val finalUdp = udpSock

            val readerJob = scope.launch {
                launch {
                    val buf = ByteArray(16)
                    try {
                        while (input.read(buf) != -1) {
                            // Keep alive
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { finalUdp.close() }
                        runCatching { finalCtrl.close() }
                    }
                }

                val rBuf = ByteArray(8192)
                while (isActive && !finalUdp.isClosed && !localUdpRelay.isClosed) {
                    try {
                        val rPkt = DatagramPacket(rBuf, rBuf.size)
                        finalUdp.receive(rPkt)
                        val targetClient = clientAddrProvider() ?: continue
                        if (rPkt.length >= 10) {
                            val replyData = rBuf.copyOf(rPkt.length)
                            localUdpRelay.send(DatagramPacket(replyData, replyData.size, targetClient))
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            UpstreamUdpAssociation(
                controlSock = ctrl,
                udpSock = udpSock,
                targetAddr = InetSocketAddress(relayIp, bndPort),
                readerJob = readerJob
            )
        } catch (_: Exception) {
            runCatching { udpSock?.close() }
            runCatching { ctrl?.close() }
            null
        }
    }

    private fun processSocks5UdpPacket(
        localUdpRelay: DatagramSocket,
        clientAddr: SocketAddress,
        packet: ByteArray,
        getUpstreamAssoc: () -> UpstreamUdpAssociation?,
        directNatSessions: ConcurrentHashMap<String, DirectUdpNatEntry>
    ) {
        try {
            if (packet[2] != 0x00.toByte()) return
            val atyp = packet[3].toInt() and 0xFF
            var offset = 4
            val destHost: String
            when (atyp) {
                0x01 -> {
                    if (packet.size < offset + 6) return
                    destHost = InetAddress.getByAddress(packet.copyOfRange(offset, offset + 4)).hostAddress ?: return
                    offset += 4
                }
                0x04 -> {
                    if (packet.size < offset + 18) return
                    destHost = InetAddress.getByAddress(packet.copyOfRange(offset, offset + 16)).hostAddress ?: return
                    offset += 16
                }
                0x03 -> {
                    val dLen = packet[offset].toInt() and 0xFF
                    offset += 1
                    if (packet.size < offset + dLen + 2) return
                    destHost = String(packet.copyOfRange(offset, offset + dLen), Charsets.UTF_8)
                    offset += dLen
                }
                else -> return
            }
            val dstPort = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val headerLen = offset + 2
            val header = packet.copyOfRange(0, headerLen)
            val payload = packet.copyOfRange(headerLen, packet.size)

            if (dstPort == 53) {
                // Handle DNS asynchronously so UDP game packets are never blocked by DNS queries
                scope.launch {
                    val dnsResp = buildEmptyAaaaResponseIfApplicable(payload) ?: resolveDnsQuery(payload) ?: return@launch
                    val fullResp = header + dnsResp
                    runCatching { localUdpRelay.send(DatagramPacket(fullResp, fullResp.size, clientAddr)) }
                }
                return
            }

            // Route non-DNS UDP through upstream SOCKS5 UDP relay if supported (preserves VPN exit IP on UDP)
            val upstreamAssoc = getUpstreamAssoc()
            if (upstreamAssoc != null) {
                try {
                    upstreamAssoc.udpSock.send(DatagramPacket(packet, packet.size, upstreamAssoc.targetAddr))
                    return
                } catch (_: Exception) {
                    upstreamAssoc.close()
                }
            }

            // Force QUIC (UDP 443) to fall back to TCP 443 over the VPN proxy
            if (dstPort == 443) return

            // In-order NAT forwarding for game match UDP ports when Kill Switch is off and upstream is TCP-only
            if (allowDirectUdpFallback) {
                val key = "$destHost:$dstPort"
                var natEntry = directNatSessions[key]
                if (natEntry == null || natEntry.socket.isClosed) {
                    val addr = InetAddress.getByName(destHost)
                    val sock = DatagramSocket()
                    protectDatagram(sock)
                    udpControlSockets.add(sock)
                    val readerJob = scope.launch {
                        val rBuf = ByteArray(4096)
                        while (isActive && !sock.isClosed && !localUdpRelay.isClosed) {
                            try {
                                val rPkt = DatagramPacket(rBuf, rBuf.size)
                                sock.receive(rPkt)
                                val respPayload = rBuf.copyOf(rPkt.length)
                                val fullResp = header + respPayload
                                localUdpRelay.send(DatagramPacket(fullResp, fullResp.size, clientAddr))
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }
                    natEntry = DirectUdpNatEntry(sock, addr, dstPort, readerJob)
                    val prev = directNatSessions.putIfAbsent(key, natEntry)
                    if (prev != null) {
                        natEntry.close()
                        natEntry = prev
                    }
                }
                natEntry.lastActiveMs = System.currentTimeMillis()
                natEntry.socket.send(DatagramPacket(payload, payload.size, natEntry.destAddress, natEntry.destPort))
            }
        } catch (_: Exception) {
        }
    }

    private fun buildEmptyAaaaResponseIfApplicable(query: ByteArray): ByteArray? {
        if (query.size < 18) return null
        val qr = (query[2].toInt() and 0x80) != 0
        if (qr) return null
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qdCount != 1) return null

        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            if ((len and 0xC0) != 0) return null
            pos += 1 + len
        }
        if (pos + 4 > query.size) return null
        val qType = ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF)
        val qClass = ((query[pos + 2].toInt() and 0xFF) shl 8) or (query[pos + 3].toInt() and 0xFF)
        if (qType != 28 || qClass != 1) return null

        val questionEnd = pos + 4
        val reply = query.copyOfRange(0, questionEnd)
        reply[2] = 0x81.toByte()
        reply[3] = 0x80.toByte()
        reply[4] = 0x00
        reply[5] = 0x01
        reply[6] = 0x00
        reply[7] = 0x00
        reply[8] = 0x00
        reply[9] = 0x00
        reply[10] = 0x00
        reply[11] = 0x00
        return reply
    }

    /**
     * Resolves DNS THROUGH the active VPN proxy (DoH over port 443 first, then TCP:53) so GeoDNS domains
     * (Free Fire / Garena / Google OAuth) return regional IPs matching the VPN server's exit country.
     * Caches valid DNS answers for 60s to make login bursts instantaneous.
     */
    private fun resolveDnsQuery(queryPayload: ByteArray): ByteArray? {
        if (queryPayload.size < 12) return null
        val cacheKey = extractDnsQuestionKey(queryPayload)
        if (cacheKey != null) {
            val cached = dnsCache[cacheKey]
            if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
                val reply = cached.response.copyOf()
                reply[0] = queryPayload[0]
                reply[1] = queryPayload[1]
                return reply
            }
        }

        val resolved: ByteArray? = run {
            if (proxyDohFailures.get() < 3) {
                val dohViaProxy = resolveDnsViaProxyDoh(queryPayload)
                if (dohViaProxy != null) {
                    proxyDohFailures.set(0)
                    return@run dohViaProxy
                }
                proxyDohFailures.incrementAndGet()
            }

            if (proxyTcp53Failures.get() < 2) {
                val tcpDnsResp = resolveDnsViaProxyTcp(queryPayload)
                if (tcpDnsResp != null) {
                    proxyTcp53Failures.set(0)
                    return@run tcpDnsResp
                }
                proxyTcp53Failures.incrementAndGet()
            }

            if (udp53Timeouts.get() < 3) {
                var udpSock: DatagramSocket? = null
                try {
                    udpSock = DatagramSocket()
                    protectDatagram(udpSock)
                    udpSock.soTimeout = 1000
                    val dnsAddr = InetAddress.getByName(dnsProvider.primaryIp)
                    udpSock.send(DatagramPacket(queryPayload, queryPayload.size, dnsAddr, 53))
                    val respBuf = ByteArray(2048)
                    val respPkt = DatagramPacket(respBuf, respBuf.size)
                    udpSock.receive(respPkt)
                    udp53Timeouts.set(0)
                    return@run respBuf.copyOf(respPkt.length)
                } catch (_: Exception) {
                    udp53Timeouts.incrementAndGet()
                } finally {
                    runCatching { udpSock?.close() }
                }
            }

            try {
                val req = Request.Builder()
                    .url("https://1.1.1.1/dns-query")
                    .addHeader("Accept", "application/dns-message")
                    .post(queryPayload.toRequestBody("application/dns-message".toMediaType()))
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } catch (_: Exception) {
                null
            }
        }

        if (resolved != null && resolved.size >= 12 && cacheKey != null) {
            if (dnsCache.size > 400) dnsCache.clear()
            dnsCache[cacheKey] = CachedDnsEntry(
                response = resolved.copyOf(),
                expiresAtMs = System.currentTimeMillis() + 60_000L
            )
        }
        return resolved
    }

    private fun extractDnsQuestionKey(query: ByteArray): String? {
        if (query.size < 18) return null
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qdCount != 1) return null
        val sb = StringBuilder()
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            if ((len and 0xC0) != 0 || pos + 1 + len > query.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(query, pos + 1, len, Charsets.US_ASCII).lowercase())
            pos += 1 + len
        }
        if (pos + 4 > query.size) return null
        val qType = ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF)
        val qClass = ((query[pos + 2].toInt() and 0xFF) shl 8) or (query[pos + 3].toInt() and 0xFF)
        return "$sb:$qType:$qClass"
    }

    private fun resolveDnsViaProxyDoh(queryPayload: ByteArray): ByteArray? {
        val dohIp = if (dnsProvider == DnsProvider.GOOGLE) "8.8.8.8" else "1.1.1.1"
        var rawSock: Socket? = null
        var sslSock: SSLSocket? = null
        return try {
            rawSock = connectViaRelayPool(dohIp, 443, connectTimeoutMs = 2800) ?: return null
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSock = factory.createSocket(rawSock, dohIp, 443, true) as SSLSocket
            sslSock.soTimeout = 3500
            sslSock.startHandshake()

            val out = sslSock.getOutputStream()
            val header = (
                "POST /dns-query HTTP/1.1\r\n" +
                    "Host: $dohIp\r\n" +
                    "Content-Type: application/dns-message\r\n" +
                    "Accept: application/dns-message\r\n" +
                    "Content-Length: ${queryPayload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            out.write(header)
            out.write(queryPayload)
            out.flush()

            val input = BufferedInputStream(sslSock.getInputStream())
            val statusLine = RelayVerifier.readAsciiLine(input) ?: return null
            if (!statusLine.contains(" 200 ")) return null

            var contentLength = -1
            var isChunked = false
            while (true) {
                val line = RelayVerifier.readAsciiLine(input) ?: return null
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
                } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    isChunked = true
                }
            }

            val body = if (contentLength in 12..4096) {
                ByteArray(contentLength).also {
                    if (!RelayVerifier.readFully(input, it)) return null
                }
            } else if (isChunked) {
                val lenLine = RelayVerifier.readAsciiLine(input)?.trim() ?: return null
                val chunkSize = lenLine.substringBefore(';').toIntOrNull(16) ?: return null
                if (chunkSize !in 12..4096) return null
                ByteArray(chunkSize).also {
                    if (!RelayVerifier.readFully(input, it)) return null
                }
            } else {
                val all = input.readBytes()
                if (all.size in 12..4096) all else return null
            }
            if (body.size >= 12) body else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { sslSock?.close() }
            runCatching { rawSock?.close() }
        }
    }

    private fun resolveDnsViaProxyTcp(queryPayload: ByteArray): ByteArray? {
        var sock: Socket? = null
        return try {
            sock = connectViaRelayPool(dnsProvider.primaryIp, 53, connectTimeoutMs = 950) ?: return null
            sock.soTimeout = 1100
            val out = sock.getOutputStream()
            val lenPrefix = byteArrayOf(
                ((queryPayload.size shr 8) and 0xFF).toByte(),
                (queryPayload.size and 0xFF).toByte()
            )
            out.write(lenPrefix)
            out.write(queryPayload)
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val respLenBytes = ByteArray(2)
            if (!RelayVerifier.readFully(input, respLenBytes)) return null
            val respLen = ((respLenBytes[0].toInt() and 0xFF) shl 8) or (respLenBytes[1].toInt() and 0xFF)
            if (respLen !in 12..4096) return null
            val respBody = ByteArray(respLen)
            if (!RelayVerifier.readFully(input, respBody)) return null
            respBody
        } catch (_: Exception) {
            null
        } finally {
            runCatching { sock?.close() }
        }
    }

    private fun sendSocks5Error(out: OutputStream, repCode: Byte) {
        runCatching {
            out.write(byteArrayOf(0x05, repCode, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }
}
