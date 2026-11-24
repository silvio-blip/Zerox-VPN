package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PreferencesRepository
import com.example.model.AppItem
import com.example.model.ConnectionDiagnostics
import com.example.model.ConnectionState
import com.example.model.Continent
import com.example.model.DnsProvider
import com.example.model.SpeedTestResult
import com.example.model.SplitTunnelMode
import com.example.model.TrafficMetrics
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.model.VpnSettings
import com.example.repository.ServerRepository
import com.example.tunnel.VpnStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ServerRepository()
    private val prefsRepository = PreferencesRepository(application)

    private val speedClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _isLoadingServers = MutableStateFlow(true)
    val isLoadingServers: StateFlow<Boolean> = _isLoadingServers.asStateFlow()

    private val _scanStatus = MutableStateFlow("Procurando servidores gratuitos...")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _selectedServer = MutableStateFlow<VpnServer?>(null)
    val selectedServer: StateFlow<VpnServer?> = _selectedServer.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = VpnStateHolder.connectionState
    val trafficMetrics: StateFlow<TrafficMetrics> = VpnStateHolder.trafficMetrics

    private val _speedTestResult = MutableStateFlow(SpeedTestResult())
    val speedTestResult: StateFlow<SpeedTestResult> = _speedTestResult.asStateFlow()

    private val _settings = MutableStateFlow(VpnSettings())
    val settings: StateFlow<VpnSettings> = _settings.asStateFlow()

    private val _selectedContinentFilter = MutableStateFlow<Continent?>(null)
    val selectedContinentFilter: StateFlow<Continent?> = _selectedContinentFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _favoriteServerIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteServerIds: StateFlow<Set<String>> = _favoriteServerIds.asStateFlow()

    private val _recentServerIds = MutableStateFlow<List<String>>(emptyList())

    private val _recentServers = MutableStateFlow<List<VpnServer>>(emptyList())
    val recentServers: StateFlow<List<VpnServer>> = _recentServers.asStateFlow()

    private val _isFavoritesFilterSelected = MutableStateFlow(false)
    val isFavoritesFilterSelected: StateFlow<Boolean> = _isFavoritesFilterSelected.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val installedApps: StateFlow<List<AppItem>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _diagnostics = MutableStateFlow(ConnectionDiagnostics())
    val diagnostics: StateFlow<ConnectionDiagnostics> = _diagnostics.asStateFlow()

    private val _isTestingRoute = MutableStateFlow(false)
    val isTestingRoute: StateFlow<Boolean> = _isTestingRoute.asStateFlow()

    private var loadJob: Job? = null
    private var activeLoadToken = 0L
    private var speedTestJob: Job? = null

    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        viewModelScope.launch {
            prefsRepository.settingsFlow.collect { saved ->
                _settings.value = saved
            }
        }
        viewModelScope.launch {
            prefsRepository.favoriteServersFlow.collect { favs ->
                _favoriteServerIds.value = favs
            }
        }
        viewModelScope.launch {
            prefsRepository.recentServersFlow.collect { recents ->
                _recentServerIds.value = recents
                updateRecentServersList(recents, _servers.value)
            }
        }
        registerNetworkObserver()
        loadServers(forceRestart = true)
    }

    private fun updateRecentServersList(recentIds: List<String>, availableServers: List<VpnServer>) {
        if (recentIds.isEmpty() || availableServers.isEmpty()) {
            _recentServers.value = emptyList()
            return
        }
        val serverMap = availableServers.associateBy { it.id }
        _recentServers.value = recentIds.mapNotNull { serverMap[it] }
    }

    private fun registerNetworkObserver() {
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch {
                    if (_servers.value.isEmpty() && loadJob?.isActive != true) {
                        loadServers(forceRestart = false)
                    }
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    fun ensureServersLoaded() {
        if (_servers.value.isEmpty() && loadJob?.isActive != true) {
            loadServers(forceRestart = false)
        }
    }

    fun loadServers(forceRestart: Boolean = true) {
        if (!forceRestart && loadJob?.isActive == true) {
            return
        }
        loadJob?.cancel()
        val myToken = ++activeLoadToken
        loadJob = viewModelScope.launch {
            _isLoadingServers.value = true
            _scanProgress.value = 0f
            _scanStatus.value = "Procurando servidores ativos..."
            if (_servers.value.isEmpty()) {
                _selectedServer.value = null
            }

            val savedId = runCatching { prefsRepository.lastServerIdFlow.first() }.getOrNull()

            try {
                var foundAny = false
                val maxAttempts = 3
                for (attempt in 0 until maxAttempts) {
                    if (!currentCoroutineContext().isActive || activeLoadToken != myToken) break

                    if (attempt > 0) {
                        _scanProgress.value = 0f
                        _scanStatus.value = "Nova tentativa automática (${attempt + 1}/$maxAttempts)..."
                        delay(1200L)
                    }

                    var attemptGotServers = false
                    repository.scanVerifiedGlobalServers().collect { update ->
                        if (activeLoadToken != myToken) return@collect

                        if (update.servers.isNotEmpty()) {
                            attemptGotServers = true
                            foundAny = true
                            _servers.value = update.servers
                            updateRecentServersList(_recentServerIds.value, update.servers)
                            val currentId = _selectedServer.value?.id ?: savedId
                            val matched = update.servers.find { it.id == currentId }
                            _selectedServer.value = matched ?: _selectedServer.value ?: update.servers.first()
                        }

                        _scanProgress.value = if (update.totalCandidates > 0) {
                            (update.testedCount.toFloat() / update.totalCandidates).coerceIn(0f, 1f)
                        } else 0f

                        _scanStatus.value = when {
                            !update.isFinished && update.totalCandidates == 0 ->
                                "Consultando listas globais gratuitas..."
                            !update.isFinished ->
                                "Verificando nós (${update.testedCount}/${update.totalCandidates}) • ${update.servers.size} países"
                            update.servers.isEmpty() && attempt < maxAttempts - 1 ->
                                "Aguardando resposta da rede..."
                            update.servers.isEmpty() ->
                                "Nenhum nó respondeu na sua rede agora"
                            else ->
                                "${update.servers.size} regiões verificadas • Ordenadas por menor ping"
                        }
                    }

                    if (attemptGotServers) break
                }

                if (!foundAny && _servers.value.isEmpty() && activeLoadToken == myToken) {
                    _scanStatus.value = "Nenhum nó respondeu na sua rede agora"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (activeLoadToken == myToken) {
                    _scanStatus.value = "Erro ao consultar servidores. Tente novamente."
                }
            } finally {
                if (activeLoadToken == myToken) {
                    _isLoadingServers.value = false
                }
            }
        }
    }

    fun selectServer(server: VpnServer) {
        _selectedServer.value = server
        viewModelScope.launch {
            prefsRepository.saveLastServerId(server.id)
        }
    }

    fun setContinentFilter(continent: Continent?) {
        _selectedContinentFilter.value = continent
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSettings(newSettings: VpnSettings) {
        _settings.value = newSettings
        viewModelScope.launch {
            prefsRepository.saveSettings(newSettings)
        }
    }

    fun setConnectionState(state: ConnectionState) {
        VpnStateHolder.updateConnectionState(state)
    }

    fun clearError() {
        if (connectionState.value is ConnectionState.Error) {
            VpnStateHolder.updateConnectionState(ConnectionState.Disconnected)
        }
    }

    fun toggleFavoriteServer(serverId: String) {
        viewModelScope.launch {
            prefsRepository.toggleFavoriteServer(serverId)
        }
    }

    fun toggleFavoritesFilter() {
        _isFavoritesFilterSelected.value = !_isFavoritesFilterSelected.value
    }

    fun markServerUsed(server: VpnServer) {
        viewModelScope.launch {
            prefsRepository.addRecentServer(server.id)
        }
    }

    fun toggleAutoReconnect(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.updateAutoReconnect(enabled)
        }
    }

    fun toggleSplitTunneling(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.updateSplitTunnelEnabled(enabled)
        }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch {
            prefsRepository.updateSplitTunnelMode(mode)
        }
    }

    fun toggleSplitTunnelApp(packageName: String) {
        viewModelScope.launch {
            val current = _settings.value.splitTunnelApps.toMutableSet()
            if (current.contains(packageName)) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            prefsRepository.updateSplitTunnelApps(current)
        }
    }

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty() || _isLoadingApps.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            try {
                val pm = getApplication<Application>().packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    pm.queryIntentActivities(intent, 0)
                }
                val myPackage = getApplication<Application>().packageName
                val apps = resolveInfos.mapNotNull { ri ->
                    val pkg = ri.activityInfo.packageName
                    if (pkg == myPackage) return@mapNotNull null
                    val label = ri.loadLabel(pm).toString()
                    val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    AppItem(packageName = pkg, label = label, isSystem = isSystem)
                }.distinctBy { it.packageName }
                 .sortedBy { it.label.lowercase() }

                _installedApps.value = apps
            } catch (_: Exception) {
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun refreshDiagnostics() {
        val cm = connectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val netType = when {
            caps == null -> "Sem Rede"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados Móveis (4G/5G)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Outro"
        }
        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val cState = connectionState.value
        val currentServer = when (cState) {
            is ConnectionState.Connected -> cState.server
            is ConnectionState.Paused -> cState.server
            is ConnectionState.Connecting -> cState.server
            else -> _selectedServer.value
        }

        val exitIp = when (cState) {
            is ConnectionState.Connected -> cState.activeRelayIp
            else -> "-"
        }

        val protoLabel = _settings.value.selectedProtocol.label
        val dnsInfo = _settings.value.dnsProvider.label

        _diagnostics.value = ConnectionDiagnostics(
            connectionState = cState,
            networkType = netType,
            isOnline = isOnline,
            virtualIp = if (cState is ConnectionState.Connected) "198.18.0.1" else "-",
            exitIp = exitIp,
            protocolName = protoLabel,
            dnsInfo = dnsInfo,
            activeServerName = currentServer?.countryName ?: "Nenhum",
            activeServerLocation = currentServer?.city ?: "-",
            serverEndpoint = if (currentServer != null && currentServer.ip.isNotBlank()) "${currentServer.ip}:${currentServer.port}" else "-",
            pingMs = currentServer?.pingMs
        )
    }

    fun runRouteDiagnosticTest() {
        if (_isTestingRoute.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isTestingRoute.value = true
            refreshDiagnostics()
            try {
                val start = System.currentTimeMillis()
                val req = Request.Builder()
                    .url("https://1.1.1.1")
                    .head()
                    .build()
                val resp = speedClient.newCall(req).execute()
                val duration = System.currentTimeMillis() - start
                val code = resp.code
                val resultText = if (code in 200..399) {
                    "✓ Rota operacional ($duration ms) • DNS Seguro OK"
                } else {
                    "⚠ Resposta HTTP $code ($duration ms)"
                }
                _diagnostics.value = _diagnostics.value.copy(
                    isTestingRoute = false,
                    routeTestResult = resultText
                )
            } catch (e: Exception) {
                _diagnostics.value = _diagnostics.value.copy(
                    isTestingRoute = false,
                    routeTestResult = "Falha no teste: ${e.localizedMessage ?: "Sem resposta de rota"}"
                )
            } finally {
                _isTestingRoute.value = false
            }
        }
    }

    fun runSpeedTest() {
        if (_speedTestResult.value.isTesting) return
        speedTestJob?.cancel()
        speedTestJob = viewModelScope.launch {
            _speedTestResult.value = SpeedTestResult(
                isTesting = true,
                phase = "Medindo Ping & Jitter...",
                progress = 0.15f
            )

            val (avgPing, jitter) = measureRealPingAndJitter()

            _speedTestResult.value = SpeedTestResult(
                isTesting = true,
                phase = "Testando Download...",
                pingMs = avgPing,
                jitterMs = jitter,
                progress = 0.45f
            )

            val downloadMbps = measureRealDownloadMbps()

            _speedTestResult.value = SpeedTestResult(
                isTesting = true,
                phase = "Testando Upload...",
                pingMs = avgPing,
                jitterMs = jitter,
                downloadMbps = downloadMbps,
                progress = 0.78f
            )

            val uploadMbps = measureRealUploadMbps()

            _speedTestResult.value = SpeedTestResult(
                isTesting = false,
                phase = if (downloadMbps > 0.0) "Teste Concluído" else "Sem resposta de rede",
                pingMs = avgPing,
                jitterMs = jitter,
                downloadMbps = downloadMbps,
                uploadMbps = uploadMbps,
                progress = 1.0f
            )
        }
    }

    private suspend fun measureRealPingAndJitter(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val targetHost = (connectionState.value as? ConnectionState.Connected)?.activeRelayIp ?: "1.1.1.1"
        val targetPort = if (targetHost == "1.1.1.1") 443 else (_selectedServer.value?.port ?: 443)
        val samples = mutableListOf<Int>()

        repeat(4) {
            val start = System.currentTimeMillis()
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(targetHost, targetPort), 2000)
                }
                samples.add((System.currentTimeMillis() - start).toInt().coerceAtLeast(1))
            } catch (_: Exception) {
                try {
                    val fbStart = System.currentTimeMillis()
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress("1.1.1.1", 443), 2000)
                    }
                    samples.add((System.currentTimeMillis() - fbStart).toInt().coerceAtLeast(1))
                } catch (_: Exception) {
                }
            }
        }

        if (samples.isEmpty()) return@withContext (0 to 0)
        val avg = samples.average().toInt()
        val jitter = if (samples.size > 1) {
            samples.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        } else 0
        avg to jitter
    }

    private suspend fun measureRealDownloadMbps(): Double = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=1500000")
                .build()
            val startNs = System.nanoTime()
            var totalBytes = 0L
            speedClient.newCall(req).execute().use { resp ->
                val stream = resp.body?.byteStream() ?: return@withContext 0.0
                val buf = ByteArray(16384)
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    totalBytes += read
                }
            }
            val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            val mbps = (totalBytes * 8.0) / elapsedSec / 1_000_000.0
            String.format("%.1f", mbps).replace(',', '.').toDoubleOrNull() ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private suspend fun measureRealUploadMbps(): Double = withContext(Dispatchers.IO) {
        return@withContext try {
            val payload = ByteArray(262_144) { 0x41 }
            val body = payload.toRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder()
                .url("https://speed.cloudflare.com/__up")
                .post(body)
                .build()
            val startNs = System.nanoTime()
            speedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0.0
            }
            val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            val mbps = (payload.size * 8.0) / elapsedSec / 1_000_000.0
            String.format("%.1f", mbps).replace(',', '.').toDoubleOrNull() ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }
}
