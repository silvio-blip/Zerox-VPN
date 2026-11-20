package com.example.repository

import com.example.model.Continent
import com.example.model.RelayEndpoint
import com.example.model.RelayType
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.tunnel.RelayVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

data class ServerScanUpdate(
    val servers: List<VpnServer>,
    val isFinished: Boolean,
    val verifiedEndpointsCount: Int,
    val testedCount: Int,
    val totalCandidates: Int
)

class ServerRepository {
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 6
        })
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    private data class CountryMeta(
        val code: String,
        val name: String,
        val city: String,
        val continent: Continent
    )

    private val africaCountries = listOf(
        "ZA", "NG", "KE", "EG", "MA", "GH", "TZ", "TN", "DZ", "AO",
        "MZ", "SN", "CM", "ET", "UG", "RW", "MU", "ZM", "ZW", "NA",
        "BW", "CI", "LY", "SD", "MG", "CV", "BJ", "TG", "ML", "NE",
        "BF", "GA", "CG", "CD", "MW", "SZ", "LS", "SL", "LR", "GN"
    )

    private val southAmericaCountries = listOf(
        "BR", "AR", "CL", "CO", "PE", "EC", "UY", "PY", "VE", "BO"
    )

    private val countryCatalog = listOf(
        // Africa
        CountryMeta("ZA", "África do Sul", "Johannesburgo", Continent.AFRICA),
        CountryMeta("NG", "Nigéria", "Lagos", Continent.AFRICA),
        CountryMeta("KE", "Quênia", "Nairóbi", Continent.AFRICA),
        CountryMeta("EG", "Egito", "Cairo", Continent.AFRICA),
        CountryMeta("MA", "Marrocos", "Casablanca", Continent.AFRICA),
        CountryMeta("GH", "Gana", "Acra", Continent.AFRICA),
        CountryMeta("TZ", "Tanzânia", "Dar es Salaam", Continent.AFRICA),
        CountryMeta("TN", "Tunísia", "Túnis", Continent.AFRICA),
        CountryMeta("DZ", "Argélia", "Argel", Continent.AFRICA),
        CountryMeta("AO", "Angola", "Luanda", Continent.AFRICA),
        CountryMeta("MZ", "Moçambique", "Maputo", Continent.AFRICA),
        CountryMeta("SN", "Senegal", "Dacar", Continent.AFRICA),
        CountryMeta("CM", "Camarões", "Dualá", Continent.AFRICA),
        CountryMeta("ET", "Etiópia", "Adis Abeba", Continent.AFRICA),
        CountryMeta("UG", "Uganda", "Campala", Continent.AFRICA),
        CountryMeta("RW", "Ruanda", "Kigali", Continent.AFRICA),
        CountryMeta("MU", "Maurícia", "Port Louis", Continent.AFRICA),
        CountryMeta("ZM", "Zâmbia", "Lusaka", Continent.AFRICA),
        CountryMeta("ZW", "Zimbábue", "Harare", Continent.AFRICA),
        CountryMeta("NA", "Namíbia", "Windhoek", Continent.AFRICA),
        CountryMeta("BW", "Botsuana", "Gaborone", Continent.AFRICA),
        CountryMeta("CI", "Costa do Marfim", "Abidjan", Continent.AFRICA),
        CountryMeta("MG", "Madagascar", "Antananarivo", Continent.AFRICA),
        CountryMeta("CV", "Cabo Verde", "Praia", Continent.AFRICA),
        // South America
        CountryMeta("BR", "Brasil", "São Paulo", Continent.SOUTH_AMERICA),
        CountryMeta("AR", "Argentina", "Buenos Aires", Continent.SOUTH_AMERICA),
        CountryMeta("CL", "Chile", "Santiago", Continent.SOUTH_AMERICA),
        CountryMeta("CO", "Colômbia", "Bogotá", Continent.SOUTH_AMERICA),
        CountryMeta("PE", "Peru", "Lima", Continent.SOUTH_AMERICA),
        CountryMeta("EC", "Equador", "Quito", Continent.SOUTH_AMERICA),
        CountryMeta("UY", "Uruguai", "Montevidéu", Continent.SOUTH_AMERICA),
        CountryMeta("PY", "Paraguai", "Assunção", Continent.SOUTH_AMERICA),
        // Europe
        CountryMeta("PT", "Portugal", "Lisboa", Continent.EUROPE),
        CountryMeta("ES", "Espanha", "Madri", Continent.EUROPE),
        CountryMeta("FR", "França", "Paris", Continent.EUROPE),
        CountryMeta("DE", "Alemanha", "Frankfurt", Continent.EUROPE),
        CountryMeta("GB", "Reino Unido", "Londres", Continent.EUROPE),
        CountryMeta("NL", "Países Baixos", "Amsterdã", Continent.EUROPE),
        CountryMeta("IT", "Itália", "Milão", Continent.EUROPE),
        CountryMeta("CH", "Suíça", "Zurique", Continent.EUROPE),
        CountryMeta("PL", "Polônia", "Varsóvia", Continent.EUROPE),
        CountryMeta("SE", "Suécia", "Estocolmo", Continent.EUROPE),
        // North America & Asia & Oceania
        CountryMeta("US", "Estados Unidos", "Nova York", Continent.NORTH_AMERICA),
        CountryMeta("CA", "Canadá", "Toronto", Continent.NORTH_AMERICA),
        CountryMeta("MX", "México", "Cidade do México", Continent.NORTH_AMERICA),
        CountryMeta("JP", "Japão", "Tóquio", Continent.ASIA),
        CountryMeta("SG", "Singapura", "Singapura", Continent.ASIA),
        CountryMeta("KR", "Coreia do Sul", "Seul", Continent.ASIA),
        CountryMeta("IN", "Índia", "Mumbai", Continent.ASIA),
        CountryMeta("HK", "Hong Kong", "Hong Kong", Continent.ASIA),
        CountryMeta("AU", "Austrália", "Sydney", Continent.OCEANIA)
    ).associateBy { it.code }

    private val priorityCountryOrder = africaCountries + southAmericaCountries + listOf(
        "PT", "ES", "GB", "DE", "FR", "NL", "IT", "PL", "US", "CA", "JP", "SG"
    )

    private val bootstrapCandidates = listOf(
        // Africa
        "ZA" to RelayEndpoint("102.130.126.178", 1080, RelayType.SOCKS5),
        "ZA" to RelayEndpoint("102.37.216.71", 8080, RelayType.HTTP_CONNECT),
        "ZA" to RelayEndpoint("196.216.147.201", 8080, RelayType.HTTP_CONNECT),
        "NG" to RelayEndpoint("102.89.44.150", 1080, RelayType.SOCKS5),
        "NG" to RelayEndpoint("102.89.33.19", 8080, RelayType.HTTP_CONNECT),
        "KE" to RelayEndpoint("197.232.14.162", 8080, RelayType.HTTP_CONNECT),
        "EG" to RelayEndpoint("156.213.199.182", 8080, RelayType.HTTP_CONNECT),
        "MA" to RelayEndpoint("105.159.245.50", 8080, RelayType.HTTP_CONNECT),
        "GH" to RelayEndpoint("154.160.23.134", 8080, RelayType.HTTP_CONNECT),
        "TZ" to RelayEndpoint("197.250.218.42", 8080, RelayType.HTTP_CONNECT),
        "TN" to RelayEndpoint("197.14.10.82", 8080, RelayType.HTTP_CONNECT),
        "DZ" to RelayEndpoint("41.107.161.73", 8080, RelayType.HTTP_CONNECT),
        // South America
        "BR" to RelayEndpoint("177.93.45.194", 8080, RelayType.HTTP_CONNECT),
        "BR" to RelayEndpoint("177.67.82.90", 8080, RelayType.HTTP_CONNECT),
        "BR" to RelayEndpoint("187.16.4.150", 8080, RelayType.HTTP_CONNECT),
        "CO" to RelayEndpoint("181.205.207.66", 8080, RelayType.HTTP_CONNECT),
        "CO" to RelayEndpoint("190.144.195.146", 8080, RelayType.HTTP_CONNECT),
        "AR" to RelayEndpoint("181.168.167.246", 8080, RelayType.HTTP_CONNECT),
        "CL" to RelayEndpoint("190.82.88.238", 8080, RelayType.HTTP_CONNECT),
        // Europe
        "PT" to RelayEndpoint("185.11.144.170", 8080, RelayType.HTTP_CONNECT),
        "PT" to RelayEndpoint("193.136.2.228", 8080, RelayType.HTTP_CONNECT),
        "ES" to RelayEndpoint("185.253.99.13", 8080, RelayType.HTTP_CONNECT),
        "FR" to RelayEndpoint("51.159.115.233", 8080, RelayType.HTTP_CONNECT),
        "DE" to RelayEndpoint("159.69.153.169", 1080, RelayType.SOCKS5),
        "DE" to RelayEndpoint("116.202.164.19", 8080, RelayType.HTTP_CONNECT),
        "GB" to RelayEndpoint("178.62.193.19", 8080, RelayType.HTTP_CONNECT),
        "NL" to RelayEndpoint("185.246.86.100", 1080, RelayType.SOCKS5),
        // North America & Asia
        "US" to RelayEndpoint("198.199.86.11", 1080, RelayType.SOCKS5),
        "US" to RelayEndpoint("68.183.109.119", 8080, RelayType.HTTP_CONNECT),
        "CA" to RelayEndpoint("158.69.53.111", 8080, RelayType.HTTP_CONNECT),
        "SG" to RelayEndpoint("128.199.202.122", 1080, RelayType.SOCKS5),
        "JP" to RelayEndpoint("133.18.234.13", 8080, RelayType.HTTP_CONNECT)
    )

    /**
     * Streams live-verified, connectable countries progressively.
     * Probes bootstrap seed nodes immediately so connectable countries appear in seconds,
     * while concurrent feeds enrich and validate additional global nodes in the background.
     */
    fun scanVerifiedGlobalServers(): Flow<ServerScanUpdate> = channelFlow {
        send(ServerScanUpdate(emptyList(), isFinished = false, verifiedEndpointsCount = 0, testedCount = 0, totalCandidates = bootstrapCandidates.size))

        val mutex = Mutex()
        val semaphore = Semaphore(24)
        val verifiedByCountry = mutableMapOf<String, MutableList<RelayEndpoint>>()
        val testedCount = AtomicInteger(0)
        val verifiedEndpointsCount = AtomicInteger(0)
        var lastEmitTimeMs = 0L

        fun buildSnapshotLocked(): List<VpnServer> {
            return verifiedByCountry.entries.mapNotNull { (code, endpoints) ->
                val sortedEps = endpoints.sortedWith(
                    compareByDescending<RelayEndpoint> { it.supportsUdp }
                        .thenBy {
                            when (it.type) {
                                RelayType.SOCKS5 -> 0
                                RelayType.SOCKS4 -> 1
                                RelayType.HTTP_CONNECT -> 2
                            }
                        }
                        .thenByDescending { it.supportsCustomPorts }
                        .thenBy { it.measuredPingMs ?: Int.MAX_VALUE }
                )
                val best = sortedEps.firstOrNull() ?: return@mapNotNull null
                val meta = countryCatalog[code]
                val countryName = meta?.name ?: getCountryName(code)
                val city = meta?.city ?: "Principal"
                val continent = meta?.continent ?: mapCountryToContinent(code)

                VpnServer(
                    id = "node_${code.lowercase()}",
                    hostName = "${best.ip}:${best.port}",
                    ip = best.ip,
                    port = best.port,
                    countryName = countryName,
                    countryCode = code,
                    city = city,
                    continent = continent,
                    protocol = when (best.type) {
                        RelayType.HTTP_CONNECT -> VpnProtocol.HTTP_TUNNEL
                        RelayType.SOCKS5, RelayType.SOCKS4 -> VpnProtocol.SOCKS5_TUNNEL
                    },
                    pingMs = best.measuredPingMs ?: 120,
                    speedMbps = estimateSpeedMbps(best.measuredPingMs ?: 120),
                    relayPool = sortedEps.take(25),
                    isVerified = true,
                    supportsUdp = sortedEps.any { it.supportsUdp }
                )
            }.sortedBy { it.pingMs }
        }

        // 1. Fetch dynamic feeds in background (compact, non-rate-limited, cooperatively cancellable)
        val dynamicFeedJob = async(Dispatchers.IO) {
            val candidatePool = mutableMapOf<String, MutableList<RelayEndpoint>>()
            val africaParam = africaCountries.take(20).joinToString(",")
            val saParam = southAmericaCountries.joinToString(",")

            withTimeoutOrNull(7500L) {
                coroutineScope {
                    val tasks = buildList {
                        add(async {
                            fetchProxyScrapeV4(
                                "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&protocol=socks5&proxy_format=protocolipport&format=json&timeout=15000",
                                RelayType.SOCKS5
                            )
                        })
                        add(async {
                            fetchProxyScrapeV4(
                                "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&protocol=socks4&proxy_format=protocolipport&format=json&timeout=15000",
                                RelayType.SOCKS4
                            )
                        })
                        add(async {
                            fetchProxyScrapeV4(
                                "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&protocol=http&ssl=yes&proxy_format=protocolipport&format=json&timeout=15000",
                                RelayType.HTTP_CONNECT
                            )
                        })
                        add(async {
                            fetchProxyScrapeV4(
                                "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&country=$africaParam,$saParam,PT&protocol=all&proxy_format=protocolipport&format=json&timeout=20000",
                                RelayType.HTTP_CONNECT
                            )
                        })
                        add(async {
                            fetchProxifly(
                                "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.json",
                                RelayType.SOCKS5
                            )
                        })
                        add(async {
                            fetchProxifly(
                                "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks4/data.json",
                                RelayType.SOCKS4
                            )
                        })
                        add(async {
                            fetchProxifly(
                                "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/https/data.json",
                                RelayType.HTTP_CONNECT
                            )
                        })
                        add(async {
                            fetchProxifly(
                                "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/all/data.json",
                                RelayType.HTTP_CONNECT
                            )
                        })
                        add(async {
                            fetchVakhovList("https://cdn.jsdelivr.net/gh/vakhov/fresh-proxy-list@master/proxylist.json")
                        })
                        add(async {
                            fetchMonosans("https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies.json")
                        })
                        add(async {
                            fetchGeonode(
                                "https://proxylist.geonode.com/api/proxy-list?limit=300&page=1&sort_by=lastChecked&sort_type=desc&protocols=socks5%2Csocks4%2Chttps%2Chttp&country=$africaParam,$saParam,PT"
                            )
                        })
                        add(async {
                            fetchGeonode(
                                "https://proxylist.geonode.com/api/proxy-list?limit=250&page=1&sort_by=lastChecked&sort_type=desc&protocols=socks5%2Csocks4%2Chttps"
                            )
                        })
                        listOf("BR", "ZA", "NG", "PT", "AO").forEach { cc ->
                            add(async { fetchProxiflyCountry(cc) })
                        }
                    }

                    tasks.awaitAll().flatten().forEach { (countryCode, endpoint) ->
                        val list = candidatePool.getOrPut(countryCode) { mutableListOf() }
                        if (list.none { it.ip == endpoint.ip && it.port == endpoint.port }) {
                            list.add(endpoint)
                        }
                    }
                }
            }
            candidatePool
        }

        // 2. Immediately probe bootstrap candidates
        val testedEndpoints = mutableSetOf<String>()
        bootstrapCandidates.forEach { (_, ep) -> testedEndpoints.add("${ep.ip}:${ep.port}") }

        coroutineScope {
            bootstrapCandidates.forEach { (countryCode, endpoint) ->
                launch {
                    semaphore.withPermit {
                        val probe = RelayVerifier.probeEndpoint(
                            endpoint = endpoint,
                            timeoutMs = 3000,
                            maxTotalMs = 4500,
                            checkUdpIfSocks5 = false
                        )
                        val done = testedCount.incrementAndGet()
                        if (probe != null) {
                            val actualCountryCode = probe.exitCountryCode.ifBlank { countryCode }
                            val verifiedEp = endpoint.copy(
                                measuredPingMs = probe.rttMs,
                                supportsUdp = probe.supportsUdp,
                                supportsCustomPorts = probe.supportsCustomPorts,
                                exitIp = probe.exitIp,
                                exitCountryCode = actualCountryCode
                            )
                            val okCount = verifiedEndpointsCount.incrementAndGet()
                            var snapshotToEmit: List<VpnServer>? = null
                            mutex.withLock {
                                val countryList = verifiedByCountry.getOrPut(actualCountryCode) { mutableListOf() }
                                val isFirstResult = verifiedByCountry.size == 1 && countryList.isEmpty()
                                if (countryList.none { it.ip == verifiedEp.ip && it.port == verifiedEp.port }) {
                                    countryList.add(verifiedEp)
                                }
                                val now = System.currentTimeMillis()
                                if (isFirstResult || now - lastEmitTimeMs >= 150L) {
                                    lastEmitTimeMs = now
                                    snapshotToEmit = buildSnapshotLocked()
                                }
                            }
                            snapshotToEmit?.let {
                                trySend(
                                    ServerScanUpdate(
                                        servers = it,
                                        isFinished = false,
                                        verifiedEndpointsCount = okCount,
                                        testedCount = done,
                                        totalCandidates = bootstrapCandidates.size
                                    )
                                )
                            }
                        } else if (done % 8 == 0) {
                            val snapshot = mutex.withLock { buildSnapshotLocked() }
                            trySend(
                                ServerScanUpdate(
                                    servers = snapshot,
                                    isFinished = false,
                                    verifiedEndpointsCount = verifiedEndpointsCount.get(),
                                    testedCount = done,
                                    totalCandidates = bootstrapCandidates.size
                                )
                            )
                        }
                    }
                }
            }
        }

        val bootstrapSnapshot = mutex.withLock { buildSnapshotLocked() }
        if (bootstrapSnapshot.isNotEmpty()) {
            trySend(
                ServerScanUpdate(
                    servers = bootstrapSnapshot,
                    isFinished = false,
                    verifiedEndpointsCount = verifiedEndpointsCount.get(),
                    testedCount = testedCount.get(),
                    totalCandidates = bootstrapCandidates.size
                )
            )
        }

        // 3. Await dynamic feeds and probe additional nodes
        val feedPool = dynamicFeedJob.await()
        val orderedCandidates = buildList<Pair<String, RelayEndpoint>> {
            val prioritySet = priorityCountryOrder.toSet()
            val priorityLists = priorityCountryOrder.associateWith { code ->
                feedPool[code].orEmpty()
                    .filter { "${it.ip}:${it.port}" !in testedEndpoints }
                    .sortedBy {
                        when (it.type) {
                            RelayType.SOCKS5 -> 0
                            RelayType.SOCKS4 -> 1
                            RelayType.HTTP_CONNECT -> 2
                        }
                    }
                    .take(25)
            }
            for (round in 0 until 25) {
                for (code in priorityCountryOrder) {
                    priorityLists[code]?.getOrNull(round)?.let { add(code to it) }
                }
            }
            feedPool.keys
                .filter { it !in prioritySet }
                .forEach { code ->
                    feedPool[code].orEmpty()
                        .filter { "${it.ip}:${it.port}" !in testedEndpoints }
                        .sortedBy {
                            when (it.type) {
                                RelayType.SOCKS5 -> 0
                                RelayType.SOCKS4 -> 1
                                RelayType.HTTP_CONNECT -> 2
                            }
                        }
                        .take(8)
                        .forEach { add(code to it) }
                }
        }.take(260)

        val totalCandidates = testedCount.get() + orderedCandidates.size
        if (orderedCandidates.isNotEmpty()) {
            trySend(
                ServerScanUpdate(
                    servers = mutex.withLock { buildSnapshotLocked() },
                    isFinished = false,
                    verifiedEndpointsCount = verifiedEndpointsCount.get(),
                    testedCount = testedCount.get(),
                    totalCandidates = totalCandidates
                )
            )

            withTimeoutOrNull(14000L) {
                coroutineScope {
                    orderedCandidates.forEach { (countryCode, endpoint) ->
                        launch {
                            semaphore.withPermit {
                                val probe = RelayVerifier.probeEndpoint(
                                    endpoint = endpoint,
                                    timeoutMs = 2800,
                                    maxTotalMs = 4200,
                                    checkUdpIfSocks5 = false
                                )
                                val done = testedCount.incrementAndGet()
                                if (probe != null) {
                                    val actualCountryCode = probe.exitCountryCode.ifBlank { countryCode }
                                    val verifiedEp = endpoint.copy(
                                        measuredPingMs = probe.rttMs,
                                        supportsUdp = probe.supportsUdp,
                                        supportsCustomPorts = probe.supportsCustomPorts,
                                        exitIp = probe.exitIp,
                                        exitCountryCode = actualCountryCode
                                    )
                                    val okCount = verifiedEndpointsCount.incrementAndGet()
                                    var snapshotToEmit: List<VpnServer>? = null
                                    mutex.withLock {
                                        val countryList = verifiedByCountry.getOrPut(actualCountryCode) { mutableListOf() }
                                        val isNewCountry = countryList.isEmpty()
                                        if (countryList.none { it.ip == verifiedEp.ip && it.port == verifiedEp.port }) {
                                            countryList.add(verifiedEp)
                                        }
                                        val now = System.currentTimeMillis()
                                        if (isNewCountry || now - lastEmitTimeMs >= 200L) {
                                            lastEmitTimeMs = now
                                            snapshotToEmit = buildSnapshotLocked()
                                        }
                                    }
                                    snapshotToEmit?.let {
                                        trySend(
                                            ServerScanUpdate(
                                                servers = it,
                                                isFinished = false,
                                                verifiedEndpointsCount = okCount,
                                                testedCount = done,
                                                totalCandidates = totalCandidates
                                            )
                                        )
                                    }
                                } else if (done % 15 == 0) {
                                    val now = System.currentTimeMillis()
                                    var snapshotToEmit: List<VpnServer>? = null
                                    mutex.withLock {
                                        if (now - lastEmitTimeMs >= 250L) {
                                            lastEmitTimeMs = now
                                            snapshotToEmit = buildSnapshotLocked()
                                        }
                                    }
                                    snapshotToEmit?.let {
                                        trySend(
                                            ServerScanUpdate(
                                                servers = it,
                                                isFinished = false,
                                                verifiedEndpointsCount = verifiedEndpointsCount.get(),
                                                testedCount = done,
                                                totalCandidates = totalCandidates
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val finalSnapshot = mutex.withLock { buildSnapshotLocked() }
        send(
            ServerScanUpdate(
                servers = finalSnapshot,
                isFinished = true,
                verifiedEndpointsCount = verifiedEndpointsCount.get(),
                testedCount = testedCount.get().coerceAtLeast(totalCandidates),
                totalCandidates = totalCandidates
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Fetches fresh candidate relays for a single country on demand (used by AtlasVpnService auto-rescue
     * when previously cached nodes in a country rotate or time out).
     */
    suspend fun findFreshEndpointsForCountry(countryCode: String): List<RelayEndpoint> {
        val cc = countryCode.uppercase().take(2)
        if (cc.length != 2) return emptyList()

        val collected = mutableListOf<RelayEndpoint>()
        withTimeoutOrNull(4500L) {
            coroutineScope {
                val tasks = listOf(
                    async {
                        fetchProxyScrapeV4(
                            "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&country=$cc&protocol=all&proxy_format=protocolipport&format=json&timeout=20000",
                            RelayType.HTTP_CONNECT
                        )
                    },
                    async { fetchProxyScrapeV2Country(cc, "socks5", RelayType.SOCKS5) },
                    async { fetchProxyScrapeV2Country(cc, "socks4", RelayType.SOCKS4) },
                    async { fetchProxyScrapeV2Country(cc, "http&ssl=yes", RelayType.HTTP_CONNECT) },
                    async { fetchProxiflyCountry(cc) },
                    async {
                        fetchGeonode(
                            "https://proxylist.geonode.com/api/proxy-list?limit=80&page=1&sort_by=lastChecked&sort_type=desc&protocols=socks5%2Csocks4%2Chttps%2Chttp&country=$cc"
                        )
                    }
                )
                tasks.awaitAll().flatten().forEach { (code, ep) ->
                    if (code.equals(cc, ignoreCase = true) && collected.none { it.ip == ep.ip && it.port == ep.port }) {
                        collected.add(ep)
                    }
                }
            }
        }
        return collected.sortedBy {
            when (it.type) {
                RelayType.SOCKS5 -> 0
                RelayType.SOCKS4 -> 1
                RelayType.HTTP_CONNECT -> 2
            }
        }.take(50)
    }

    private suspend fun executeGet(url: String): String? = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) ZeroxVPN/1.0")
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation {
            runCatching { call.cancel() }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                } catch (_: Exception) {
                    null
                }
                if (cont.isActive) cont.resume(body)
            }
        })
    }

    private fun parseRelayType(protoRaw: String, defaultType: RelayType): RelayType {
        val p = protoRaw.lowercase()
        return when {
            p.contains("socks5") -> RelayType.SOCKS5
            p.contains("socks4") -> RelayType.SOCKS4
            p.contains("http") -> RelayType.HTTP_CONNECT
            else -> defaultType
        }
    }

    private suspend fun fetchProxyScrapeV4(url: String, defaultType: RelayType): List<Pair<String, RelayEndpoint>> {
        val result = mutableListOf<Pair<String, RelayEndpoint>>()
        try {
            val body = executeGet(url)?.trim() ?: return result
            val arr = if (body.startsWith("{")) {
                JSONObject(body).optJSONArray("proxies") ?: return result
            } else if (body.startsWith("[")) {
                JSONArray(body)
            } else return result

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ip = obj.optString("ip")
                val port = obj.optInt("port", 0)
                val proto = obj.optString("protocol")
                val ipData = obj.optJSONObject("ip_data")
                val country = (ipData?.optString("countryCode")?.takeIf { it.isNotBlank() }
                    ?: ipData?.optString("country_code")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("country")).uppercase()
                if (ip.isNotBlank() && port in 1..65535 && country.length == 2) {
                    val type = parseRelayType(proto, defaultType)
                    result.add(country to RelayEndpoint(ip, port, type))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private suspend fun fetchProxifly(url: String, defaultType: RelayType): List<Pair<String, RelayEndpoint>> {
        val result = mutableListOf<Pair<String, RelayEndpoint>>()
        try {
            val body = executeGet(url)?.trim() ?: return result
            if (!body.startsWith("[")) return result
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ip = obj.optString("ip")
                val port = obj.optInt("port", 0)
                val proto = obj.optString("protocol")
                val geo = obj.optJSONObject("geolocation")
                val country = geo?.optString("country")?.uppercase().orEmpty()
                if (ip.isNotBlank() && port in 1..65535 && country.length == 2) {
                    val type = parseRelayType(proto, defaultType)
                    result.add(country to RelayEndpoint(ip, port, type))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private suspend fun fetchProxiflyCountry(countryCode: String): List<Pair<String, RelayEndpoint>> {
        return fetchProxifly(
            "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/countries/${countryCode.uppercase()}/data.json",
            RelayType.HTTP_CONNECT
        )
    }

    private suspend fun fetchProxyScrapeV2Country(
        countryCode: String,
        protocolParam: String,
        defaultType: RelayType
    ): List<Pair<String, RelayEndpoint>> {
        val cc = countryCode.uppercase()
        val result = mutableListOf<Pair<String, RelayEndpoint>>()
        try {
            val url = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=$protocolParam&timeout=15000&country=$cc"
            val body = executeGet(url) ?: return result
            body.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || !line.contains(':')) return@forEach
                val parts = line.split(':')
                if (parts.size == 2) {
                    val ip = parts[0].trim()
                    val port = parts[1].trim().toIntOrNull() ?: 0
                    if (ip.isNotBlank() && port in 1..65535) {
                        result.add(cc to RelayEndpoint(ip, port, defaultType))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private suspend fun fetchVakhovList(url: String): List<Pair<String, RelayEndpoint>> {
        val result = mutableListOf<Pair<String, RelayEndpoint>>()
        try {
            val body = executeGet(url)?.trim() ?: return result
            if (!body.startsWith("[")) return result
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ip = obj.optString("ip").ifBlank { obj.optString("host") }
                val port = obj.optString("port").toIntOrNull() ?: obj.optInt("port", 0)
                val country = (obj.optString("country_code").takeIf { it.length == 2 }
                    ?: obj.optString("country").takeIf { it.length == 2 }
                    ?: "").uppercase()
                if (ip.isBlank() || port !in 1..65535 || country.length != 2) continue

                val isSocks5 = obj.optInt("socks5", 0) == 1 || obj.optBoolean("socks5", false)
                val isSocks4 = obj.optInt("socks4", 0) == 1 || obj.optBoolean("socks4", false)
                val isSsl = obj.optInt("ssl", 0) == 1 || obj.optBoolean("ssl", false)
                val protoRaw = obj.optString("protocol").ifBlank { obj.optString("type") }

                val type = when {
                    isSocks5 -> RelayType.SOCKS5
                    isSocks4 -> RelayType.SOCKS4
                    protoRaw.isNotBlank() -> parseRelayType(protoRaw, RelayType.HTTP_CONNECT)
                    isSsl -> RelayType.HTTP_CONNECT
                    else -> continue
                }
                result.add(country to RelayEndpoint(ip, port, type))
            }
        } catch (_: Exception) {
        }
        return result
    }

    private suspend fun fetchMonosans(url: String): List<Pair<String, RelayEndpoint>> {
        val result = mutableListOf<Pair<String, RelayEndpoint>>()
        try {
            val body = executeGet(url)?.trim() ?: return result
            if (!body.startsWith("[")) return result
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ip = obj.optString("host").ifBlank { obj.optString("ip") }
                val port = obj.optInt("port", 0)
                val proto = obj.optString("protocol")
                val geo = obj.optJSONObject("geolocation")
                val countryObj = geo?.optJSONObject("country")
                val country = (countryObj?.optString("iso_code")?.takeIf { it.isNotBlank() }
                    ?: geo?.optString("country")?.takeIf { it.length == 2 }
                    ?: obj.optString("country")).uppercase()
                if (ip.isNotBlank() && port in 1..65535 && country.length == 2) {
                    val type = parseRelayType(proto, RelayType.HTTP_CONNECT)
                    result.add(country to RelayEndpoint(ip, port, type))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private suspend fun fetchGeonode(url: String): List<Pair<String, RelayEndpoint>> {
        val result = mutableListOf<Pair<String, RelayEndpoint>>()
        try {
            val body = executeGet(url)?.trim() ?: return result
            if (!body.startsWith("{")) return result
            val root = JSONObject(body)
            val arr = root.optJSONArray("data") ?: return result
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ip = obj.optString("ip")
                val port = obj.optString("port").toIntOrNull() ?: obj.optInt("port", 0)
                val country = obj.optString("country").uppercase()
                val protos = obj.optJSONArray("protocols")
                val protoStr = (0 until (protos?.length() ?: 0))
                    .mapNotNull { protos?.optString(it)?.lowercase() }
                    .joinToString(",")
                val type = parseRelayType(protoStr, RelayType.HTTP_CONNECT)
                if (ip.isNotBlank() && port in 1..65535 && country.length == 2) {
                    result.add(country to RelayEndpoint(ip, port, type))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun estimateSpeedMbps(pingMs: Int): Double {
        return when {
            pingMs < 80 -> 140.0
            pingMs < 180 -> 95.0
            pingMs < 350 -> 55.0
            else -> 25.0
        }
    }

    private fun mapCountryToContinent(code: String): Continent {
        return when (code) {
            in southAmericaCountries, "GY", "SR", "GF" -> Continent.SOUTH_AMERICA
            in africaCountries, "BI", "CF", "TD", "KM", "DJ", "GQ", "ER", "GM", "GW", "MR", "SC", "SO", "SS", "ST", "RE", "YT" -> Continent.AFRICA
            "US", "CA", "MX", "GT", "BZ", "SV", "HN", "NI", "CR", "PA", "CU", "DO", "HT", "JM", "PR", "TT", "BS", "BB" -> Continent.NORTH_AMERICA
            "PT", "GB", "DE", "FR", "NL", "CH", "IT", "ES", "SE", "PL", "RO", "NO", "FI", "IE", "AT", "BE", "CZ", "UA", "RU", "GR",
            "DK", "HU", "BG", "RS", "HR", "SK", "SI", "LT", "LV", "EE", "LU", "IS", "AL", "MK", "BA", "MD", "BY", "CY", "MT" -> Continent.EUROPE
            "JP", "KR", "SG", "IN", "HK", "TW", "ID", "TH", "VN", "PH", "AE", "CN", "MY", "TR", "SA", "IL", "PK", "BD", "LK",
            "NP", "KZ", "UZ", "IQ", "IR", "JO", "LB", "KW", "QA", "OM", "BH", "KH", "LA", "MM", "MN", "AZ", "GE", "AM" -> Continent.ASIA
            "AU", "NZ", "FJ", "PG" -> Continent.OCEANIA
            else -> Continent.EUROPE
        }
    }

    private fun getCountryName(code: String): String {
        return countryCatalog[code]?.name
            ?: java.util.Locale("pt", code).getDisplayCountry(java.util.Locale("pt")).ifBlank { code }
    }
}
