package com.example.tunnel

import com.example.model.RelayEndpoint
import com.example.model.RelayType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class RelayProbeResult(
    val rttMs: Int,
    val totalTlsMs: Int,
    val supportsUdp: Boolean,
    val supportsCustomPorts: Boolean,
    val exitIp: String = "",
    val exitCountryCode: String = ""
)

object RelayVerifier {

    @Volatile
    private var cachedGstaticIpv4: ByteArray? = null

    private fun getGstaticIpv4(): ByteArray {
        cachedGstaticIpv4?.let { return it }
        return try {
            val addrs = InetAddress.getAllByName("connectivitycheck.gstatic.com")
            val v4 = addrs.firstOrNull { it.address.size == 4 }?.address
            if (v4 != null) {
                cachedGstaticIpv4 = v4
                v4
            } else {
                byteArrayOf(142.toByte(), 250.toByte(), 184.toByte(), 163.toByte())
            }
        } catch (_: Exception) {
            byteArrayOf(142.toByte(), 250.toByte(), 184.toByte(), 163.toByte())
        }
    }

    /**
     * Verifies a SOCKS5, SOCKS4, or HTTP_CONNECT relay using a single clean TLS 443 probe
     * to avoid triggering concurrent rate-limits on public African/South American nodes.
     */
    suspend fun probeEndpoint(
        endpoint: RelayEndpoint,
        timeoutMs: Int = 3600,
        maxTotalMs: Int = 4400,
        checkUdpIfSocks5: Boolean = true,
        protectSocket: ((Socket) -> Boolean)? = null,
        protectDatagram: ((DatagramSocket) -> Boolean)? = null
    ): RelayProbeResult? = withContext(Dispatchers.IO) {
        val (tcpRtt, totalTlsMs) = verifyGstaticHttps443(endpoint, timeoutMs, maxTotalMs, protectSocket)
            ?: return@withContext null

        val supportsUdp = if (checkUdpIfSocks5 && endpoint.type == RelayType.SOCKS5) {
            verifySocks5UdpAssociate(endpoint, 1400, protectSocket, protectDatagram)
        } else {
            false
        }

        RelayProbeResult(
            rttMs = tcpRtt,
            totalTlsMs = totalTlsMs,
            supportsUdp = supportsUdp,
            supportsCustomPorts = endpoint.type == RelayType.SOCKS5 || endpoint.type == RelayType.SOCKS4
        )
    }

    suspend fun checkSocks5UdpSupport(
        endpoint: RelayEndpoint,
        timeoutMs: Int = 1200,
        protectSocket: ((Socket) -> Boolean)? = null,
        protectDatagram: ((DatagramSocket) -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (endpoint.type != RelayType.SOCKS5) return@withContext false
        verifySocks5UdpAssociate(endpoint, timeoutMs, protectSocket, protectDatagram)
    }

    /**
     * Fast single-socket proxy tunnel check used at VPN connect time on already-verified nodes,
     * confirming the relay is alive and accepting CONNECT tunnels right now without hammering it.
     */
    suspend fun quickConnectVerify(
        endpoint: RelayEndpoint,
        timeoutMs: Int = 4500,
        protectSocket: ((Socket) -> Boolean)? = null
    ): Int? = withContext(Dispatchers.IO) {
        var rawSocket: Socket? = null
        return@withContext try {
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            protectSocket?.invoke(rawSocket)
            val connectStart = System.currentTimeMillis()
            rawSocket.connect(InetSocketAddress(endpoint.ip, endpoint.port), timeoutMs)
            val tcpRtt = (System.currentTimeMillis() - connectStart).toInt().coerceAtLeast(10)
            rawSocket.soTimeout = timeoutMs

            val rawInput = BufferedInputStream(rawSocket.getInputStream())
            val gstaticIp = getGstaticIpv4()
            val handshakeOk = when (endpoint.type) {
                RelayType.SOCKS5 -> testSocks5ConnectHost(rawSocket, rawInput, "connectivitycheck.gstatic.com", 443)
                RelayType.SOCKS4 -> testSocks4ConnectIpv4(rawSocket, rawInput, gstaticIp, 443)
                RelayType.HTTP_CONNECT -> testHttpConnectHost(rawSocket, rawInput, "connectivitycheck.gstatic.com", 443)
            }
            if (handshakeOk) tcpRtt else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { rawSocket?.close() }
        }
    }

    suspend fun verifyEndpoint(
        endpoint: RelayEndpoint,
        timeoutMs: Int = 3600,
        protectSocket: ((Socket) -> Boolean)? = null
    ): Int? = verifyGstaticHttps443(endpoint, timeoutMs, timeoutMs + 800, protectSocket)?.first

    private fun verifyGstaticHttps443(
        endpoint: RelayEndpoint,
        timeoutMs: Int,
        maxTotalMs: Int,
        protectSocket: ((Socket) -> Boolean)?
    ): Pair<Int, Int>? {
        val totalStart = System.currentTimeMillis()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            protectSocket?.invoke(rawSocket)
            val connectStart = System.currentTimeMillis()
            rawSocket.connect(InetSocketAddress(endpoint.ip, endpoint.port), timeoutMs)
            val tcpRtt = (System.currentTimeMillis() - connectStart).toInt().coerceAtLeast(10)
            rawSocket.soTimeout = timeoutMs

            val rawInput = BufferedInputStream(rawSocket.getInputStream())
            val gstaticIp = getGstaticIpv4()
            val handshakeOk = when (endpoint.type) {
                RelayType.SOCKS5 -> testSocks5ConnectHost(rawSocket, rawInput, "connectivitycheck.gstatic.com", 443)
                RelayType.SOCKS4 -> testSocks4ConnectIpv4(rawSocket, rawInput, gstaticIp, 443)
                RelayType.HTTP_CONNECT -> testHttpConnectHost(rawSocket, rawInput, "connectivitycheck.gstatic.com", 443)
            }
            if (!handshakeOk) return null

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(
                rawSocket,
                "connectivitycheck.gstatic.com",
                443,
                true
            ) as SSLSocket
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()

            val sslOut = sslSocket.getOutputStream()
            val sslIn = BufferedInputStream(sslSocket.getInputStream())
            val probeReq = "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n"
            sslOut.write(probeReq.toByteArray(Charsets.US_ASCII))
            sslOut.flush()

            val statusLine = readAsciiLine(sslIn) ?: return null
            if (!statusLine.startsWith("HTTP/1.") ||
                !(statusLine.contains("204") || statusLine.contains("200") || statusLine.contains("30"))
            ) {
                return null
            }

            val totalElapsed = (System.currentTimeMillis() - totalStart).toInt().coerceAtLeast(10)
            if (totalElapsed > maxTotalMs) return null
            tcpRtt to totalElapsed
        } catch (_: Exception) {
            null
        } finally {
            runCatching { sslSocket?.close() }
            runCatching { rawSocket?.close() }
        }
    }

    private fun verifySocks5UdpAssociate(
        endpoint: RelayEndpoint,
        timeoutMs: Int,
        protectSocket: ((Socket) -> Boolean)?,
        protectDatagram: ((DatagramSocket) -> Boolean)?
    ): Boolean {
        var tcpControl: Socket? = null
        var udpSock: DatagramSocket? = null
        return try {
            tcpControl = Socket()
            tcpControl.tcpNoDelay = true
            protectSocket?.invoke(tcpControl)
            tcpControl.connect(InetSocketAddress(endpoint.ip, endpoint.port), timeoutMs)
            tcpControl.soTimeout = timeoutMs

            val out = tcpControl.getOutputStream()
            val input = BufferedInputStream(tcpControl.getInputStream())

            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val methodReply = ByteArray(2)
            if (!readFully(input, methodReply)) return false
            if (methodReply[0] != 0x05.toByte() || methodReply[1] != 0x00.toByte()) return false

            out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()

            val header = ByteArray(4)
            if (!readFully(input, header)) return false
            if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) return false

            val bndAddrStr = when (header[3].toInt() and 0xFF) {
                0x01 -> {
                    val addr = ByteArray(4)
                    if (!readFully(input, addr)) return false
                    InetAddress.getByAddress(addr).hostAddress
                }
                0x03 -> {
                    val len = input.read()
                    if (len <= 0) return false
                    val dom = ByteArray(len)
                    if (!readFully(input, dom)) return false
                    String(dom, Charsets.UTF_8)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    if (!readFully(input, addr)) return false
                    InetAddress.getByAddress(addr).hostAddress
                }
                else -> return false
            }
            val portB = ByteArray(2)
            if (!readFully(input, portB)) return false
            val bndPort = ((portB[0].toInt() and 0xFF) shl 8) or (portB[1].toInt() and 0xFF)
            if (bndPort !in 1..65535) return false

            val relayHost = resolveUdpRelayHost(bndAddrStr, endpoint.ip)
            udpSock = DatagramSocket()
            protectDatagram?.invoke(udpSock)
            udpSock.soTimeout = timeoutMs

            val dnsQuery = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x06, 'g'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'g'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0x00, 0x00, 0x01, 0x00, 0x01
            )
            val socksUdpPacket = byteArrayOf(
                0x00, 0x00, 0x00, 0x01,
                1, 1, 1, 1,
                0x00, 53
            ) + dnsQuery

            val relayAddress = InetAddress.getByName(relayHost)
            udpSock.send(DatagramPacket(socksUdpPacket, socksUdpPacket.size, relayAddress, bndPort))

            val respBuf = ByteArray(1024)
            val respPkt = DatagramPacket(respBuf, respBuf.size)
            udpSock.receive(respPkt)
            respPkt.length >= 16
        } catch (_: Exception) {
            false
        } finally {
            runCatching { udpSock?.close() }
            runCatching { tcpControl?.close() }
        }
    }

    fun resolveUdpRelayHost(bndAddr: String?, proxyIp: String): String {
        if (bndAddr.isNullOrBlank() || bndAddr == "0.0.0.0" || bndAddr == "::" || bndAddr == "127.0.0.1") {
            return proxyIp
        }
        return try {
            val addr = InetAddress.getByName(bndAddr)
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isSiteLocalAddress) {
                proxyIp
            } else {
                bndAddr
            }
        } catch (_: Exception) {
            proxyIp
        }
    }

    private fun testSocks5ConnectHost(socket: Socket, input: BufferedInputStream, host: String, port: Int): Boolean {
        val out = socket.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()

        val methodReply = ByteArray(2)
        if (!readFully(input, methodReply)) return false
        if (methodReply[0] != 0x05.toByte() || methodReply[1] != 0x00.toByte()) return false

        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val connectReq = byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
            hostBytes + byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        out.write(connectReq)
        out.flush()

        return readSocks5ConnectReply(input)
    }

    private fun testSocks4ConnectIpv4(socket: Socket, input: BufferedInputStream, ipv4: ByteArray, port: Int): Boolean {
        if (ipv4.size != 4) return false
        val out = socket.getOutputStream()
        val req = byteArrayOf(
            0x04,
            0x01,
            ((port shr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte(),
            ipv4[0], ipv4[1], ipv4[2], ipv4[3],
            0x00
        )
        out.write(req)
        out.flush()

        val reply = ByteArray(8)
        if (!readFully(input, reply)) return false
        return reply[1] == 0x5A.toByte()
    }

    private fun readSocks5ConnectReply(input: BufferedInputStream): Boolean {
        val header = ByteArray(4)
        if (!readFully(input, header)) return false
        if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) return false

        return when (header[3].toInt() and 0xFF) {
            0x01 -> readFully(input, ByteArray(6))
            0x03 -> {
                val lenB = ByteArray(1)
                if (!readFully(input, lenB)) return false
                readFully(input, ByteArray((lenB[0].toInt() and 0xFF) + 2))
            }
            0x04 -> readFully(input, ByteArray(18))
            else -> false
        }
    }

    private fun testHttpConnectHost(socket: Socket, input: BufferedInputStream, host: String, port: Int): Boolean {
        val out = socket.getOutputStream()
        val req = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\nProxy-Connection: Keep-Alive\r\n\r\n"
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readAsciiLine(input) ?: return false
        if (!statusLine.contains(" 200 ") && !statusLine.startsWith("HTTP/1.1 200") && !statusLine.startsWith("HTTP/1.0 200")) {
            return false
        }
        while (true) {
            val line = readAsciiLine(input) ?: return false
            if (line.isEmpty()) break
        }
        return true
    }

    fun readFully(input: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Boolean {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read <= 0) return false
            total += read
        }
        return true
    }

    fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) {
                    sb.deleteCharAt(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
            if (sb.length > 4096) return sb.toString()
        }
    }
}
