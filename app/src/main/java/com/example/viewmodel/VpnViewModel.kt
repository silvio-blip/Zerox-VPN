package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PreferencesRepository
import com.example.model.ConnectionState
import com.example.model.Continent
import com.example.model.SpeedTestResult
import com.example.model.TrafficMetrics
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
        registerNetworkObserver()
        loadServers(forceRestart = true)
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
