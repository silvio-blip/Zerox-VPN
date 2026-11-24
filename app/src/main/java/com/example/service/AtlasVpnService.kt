package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.ConnectionState
import com.example.model.Continent
import com.example.model.DnsProvider
import com.example.model.RelayEndpoint
import com.example.model.RelayType
import com.example.model.SplitTunnelMode
import com.example.model.TrafficMetrics
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.repository.ServerRepository
import com.example.tunnel.LocalSocks5Relay
import com.example.tunnel.RelayVerifier
import com.example.tunnel.VpnIpc
import com.example.tunnel.VpnStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.hevtunnel.TProxyService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AtlasVpnService : VpnService() {

    private var tunnelInterface: ParcelFileDescriptor? = null
    private var localRelay: LocalSocks5Relay? = null
    private val isRunning = AtomicBoolean(false)
    private val isPausedState = AtomicBoolean(false)
    private val manualStopRequested = AtomicBoolean(false)
    private val hasConnectedOnce = AtomicBoolean(false)
    private val isRefreshingPool = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)

    private var connectionJob: Job? = null
    private var statsJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var activeServerRef: VpnServer? = null

    @Volatile
    private var lastTotalElapsedSeconds = 0L

    @Volatile
    private var lastTotalRxBytes = 0L

    @Volatile
    private var lastTotalTxBytes = 0L

    @Volatile
    private var lastActiveNetwork: Network? = null

    @Volatile
    private var activeAutoReconnect = true

    @Volatile
    private var activeSplitTunnelEnabled = false

    @Volatile
    private var activeSplitTunnelMode = SplitTunnelMode.BYPASS

    @Volatile
    private var activeSplitTunnelApps = emptySet<String>()

    private val statusRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VpnIpc.ACTION_REQUEST_STATUS) {
                VpnIpc.sendStateBroadcast(this@AtlasVpnService, VpnStateHolder.connectionState.value)
                VpnIpc.sendMetricsBroadcast(this@AtlasVpnService, VpnStateHolder.trafficMetrics.value)
            }
        }
    }

    companion object {
        private const val TAG = "AtlasVpnService"
        private const val CHANNEL_ID = "atlas_vpn_channel"
        private const val NOTIFICATION_ID = 101
        private const val PREFS_NAME = "atlas_active_vpn_session"
        private const val KEY_ACTIVE_JSON = "active_session_json"

        const val ACTION_START_VPN = "com.example.START_VPN"
        const val ACTION_STOP_VPN = "com.example.STOP_VPN"
        const val ACTION_PAUSE_VPN = "com.example.PAUSE_VPN"
        const val ACTION_RESUME_VPN = "com.example.RESUME_VPN"
        const val ACTION_RESTORE_PERSISTENT = "com.example.RESTORE_PERSISTENT"

        const val EXTRA_SERVER_JSON = "server_json"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val EXTRA_DNS_PROVIDER = "dns_provider"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_AUTO_RECONNECT = "auto_reconnect"
        const val EXTRA_SPLIT_TUNNEL_ENABLED = "split_tunnel_enabled"
        const val EXTRA_SPLIT_TUNNEL_MODE = "split_tunnel_mode"
        const val EXTRA_SPLIT_TUNNEL_APPS = "split_tunnel_apps"

        fun hasPersistedSession(context: Context): Boolean {
            return !context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_ACTIVE_JSON, null)
                .isNullOrBlank()
        }
    }

    private data class SavedSession(
        val server: VpnServer,
        val killSwitch: Boolean,
        val dnsProvider: DnsProvider,
        val preferredProtocol: VpnProtocol,
        val autoReconnect: Boolean = true,
        val splitTunnelEnabled: Boolean = false,
        val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.BYPASS,
        val splitTunnelApps: Set<String> = emptySet(),
        val isPaused: Boolean = false,
        val hasConnected: Boolean = false,
        val accElapsedSeconds: Long = 0L,
        val accRxBytes: Long = 0L,
        val accTxBytes: Long = 0L
    )

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        runCatching {
            ContextCompat.registerReceiver(
                this,
                statusRequestReceiver,
                IntentFilter(VpnIpc.ACTION_REQUEST_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        registerNetworkChangeWatcher()
    }

    private fun registerNetworkChangeWatcher() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    runCatching { setUnderlyingNetworks(arrayOf(network)) }
                }
                val prevNetwork = lastActiveNetwork
                lastActiveNetwork = network
                if (manualStopRequested.get() || isPausedState.get()) return
                val server = activeServerRef ?: return
                if (!isRunning.get()) return

                val relay = localRelay ?: return
                val networkSwitched = prevNetwork != null && prevNetwork != network
                if (networkSwitched && activeAutoReconnect) {
                    Log.i(TAG, "Transição de rede detetada (Wi-Fi/Dados Móveis). Auto-reconexão inteligente revalidando o túnel...")
                    updateNotificationRealtime(
                        title = "Zerox VPN • Ajustando Rota...",
                        text = "Nova ligação de rede detetada. Revalidando túnel...",
                        bigText = "Auto-reconexão inteligente ativa. Mantendo o túnel seguro.",
                        whenMillis = System.currentTimeMillis(),
                        useChronometer = false,
                        isPausedNotification = false
                    )
                    refreshRelayPoolInBackground(server, sessionGeneration.get(), relay)
                } else if (relay.getConsecutiveFailures() > 0) {
                    refreshRelayPoolInBackground(server, sessionGeneration.get(), relay)
                }
            }

            override fun onLost(network: Network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    runCatching { setUnderlyingNetworks(null) }
                }
                if (activeAutoReconnect && isRunning.get() && !manualStopRequested.get() && !isPausedState.get()) {
                    activeServerRef?.let { server ->
                        localRelay?.let { relay ->
                            refreshRelayPoolInBackground(server, sessionGeneration.get(), relay)
                        }
                    }
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                manualStopRequested.set(true)
                isPausedState.set(false)
                hasConnectedOnce.set(false)
                lastTotalElapsedSeconds = 0L
                lastTotalRxBytes = 0L
                lastTotalTxBytes = 0L
                clearPersistedSession()
                stopVpn(preserveError = false)
                return START_NOT_STICKY
            }

            ACTION_PAUSE_VPN -> {
                pauseVpn()
                return START_STICKY
            }

            ACTION_RESUME_VPN -> {
                val saved = loadPersistedSession()
                if (saved == null) {
                    manualStopRequested.set(true)
                    stopVpn(preserveError = false)
                    return START_NOT_STICKY
                }
                manualStopRequested.set(false)
                isPausedState.set(false)
                hasConnectedOnce.set(true)
                lastTotalElapsedSeconds = saved.accElapsedSeconds
                lastTotalRxBytes = saved.accRxBytes
                lastTotalTxBytes = saved.accTxBytes
                activeServerRef = saved.server
                VpnStateHolder.activeTargetServer = saved.server

                activeAutoReconnect = saved.autoReconnect
                activeSplitTunnelEnabled = saved.splitTunnelEnabled
                activeSplitTunnelMode = saved.splitTunnelMode
                activeSplitTunnelApps = saved.splitTunnelApps

                val newGen = sessionGeneration.incrementAndGet()
                stopTunnelOnly()
                persistActiveSession(
                    server = saved.server,
                    killSwitch = saved.killSwitch,
                    dnsProvider = saved.dnsProvider,
                    preferredProtocol = saved.preferredProtocol,
                    autoReconnect = saved.autoReconnect,
                    splitTunnelEnabled = saved.splitTunnelEnabled,
                    splitTunnelMode = saved.splitTunnelMode,
                    splitTunnelApps = saved.splitTunnelApps,
                    isPaused = false,
                    hasConnected = true,
                    accElapsedSeconds = lastTotalElapsedSeconds,
                    accRxBytes = lastTotalRxBytes,
                    accTxBytes = lastTotalTxBytes
                )
                acquireWakeLock()
                startForegroundWithNotification(
                    title = "Zerox VPN • Retomando...",
                    text = "Reativando túnel persistente em ${saved.server.countryName}...",
                    bigText = null,
                    whenMillis = System.currentTimeMillis(),
                    useChronometer = false,
                    isPausedNotification = false
                )
                startVpn(
                    myGen = newGen,
                    server = saved.server,
                    killSwitch = saved.killSwitch,
                    dnsProvider = saved.dnsProvider,
                    preferredProtocol = saved.preferredProtocol,
                    autoReconnect = saved.autoReconnect,
                    splitTunnelEnabled = saved.splitTunnelEnabled,
                    splitTunnelMode = saved.splitTunnelMode,
                    splitTunnelApps = saved.splitTunnelApps,
                    baseElapsedSeconds = lastTotalElapsedSeconds,
                    baseRxBytes = lastTotalRxBytes,
                    baseTxBytes = lastTotalTxBytes,
                    isPersistentRecovery = true
                )
                return START_STICKY
            }
        }

        val isRestoreAction = intent == null || intent.action == ACTION_RESTORE_PERSISTENT
        val restored = if (isRestoreAction) loadPersistedSession() else null
        if (isRestoreAction && restored == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (restored != null && restored.isPaused) {
            manualStopRequested.set(false)
            isPausedState.set(true)
            hasConnectedOnce.set(restored.hasConnected)
            activeServerRef = restored.server
            VpnStateHolder.activeTargetServer = restored.server
            lastTotalElapsedSeconds = restored.accElapsedSeconds
            lastTotalRxBytes = restored.accRxBytes
            lastTotalTxBytes = restored.accTxBytes

            activeAutoReconnect = restored.autoReconnect
            activeSplitTunnelEnabled = restored.splitTunnelEnabled
            activeSplitTunnelMode = restored.splitTunnelMode
            activeSplitTunnelApps = restored.splitTunnelApps

            val pausedState = ConnectionState.Paused(
                server = restored.server,
                pausedAtMillis = System.currentTimeMillis(),
                accumulatedElapsedSeconds = restored.accElapsedSeconds,
                accumulatedDownloadBytes = restored.accRxBytes,
                accumulatedUploadBytes = restored.accTxBytes
            )
            startForegroundWithNotification(
                title = "Zerox VPN • Em Pausa (${countryFlag(restored.server.countryCode)} ${restored.server.countryName})",
                text = "⏱ ${formatDuration(restored.accElapsedSeconds)} • ↓ ${formatTotal(restored.accRxBytes)} • ↑ ${formatTotal(restored.accTxBytes)}",
                bigText = "Túnel em pausa em ${restored.server.countryName}.\nToque em Retomar para continuar protegido.",
                whenMillis = System.currentTimeMillis(),
                useChronometer = false,
                isPausedNotification = true
            )
            emitConnectionState(pausedState)
            return START_STICKY
        }

        manualStopRequested.set(false)
        isPausedState.set(false)

        val targetServer: VpnServer
        val killSwitch: Boolean
        val dnsProvider: DnsProvider
        val preferredProtocol: VpnProtocol
        val autoReconnect: Boolean
        val splitTunnelEnabled: Boolean
        val splitTunnelMode: SplitTunnelMode
        val splitTunnelApps: Set<String>
        val baseElapsed: Long
        val baseRx: Long
        val baseTx: Long
        val isPersistentRecovery: Boolean

        if (restored != null) {
            targetServer = restored.server
            killSwitch = restored.killSwitch
            dnsProvider = restored.dnsProvider
            preferredProtocol = restored.preferredProtocol
            autoReconnect = restored.autoReconnect
            splitTunnelEnabled = restored.splitTunnelEnabled
            splitTunnelMode = restored.splitTunnelMode
            splitTunnelApps = restored.splitTunnelApps
            hasConnectedOnce.set(restored.hasConnected)
            baseElapsed = restored.accElapsedSeconds
            baseRx = restored.accRxBytes
            baseTx = restored.accTxBytes
            lastTotalElapsedSeconds = baseElapsed
            lastTotalRxBytes = baseRx
            lastTotalTxBytes = baseTx
            isPersistentRecovery = true
            VpnStateHolder.activeTargetServer = targetServer
        } else {
            val serverIp = intent?.getStringExtra(EXTRA_SERVER_IP) ?: ""
            val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 1080) ?: 1080
            val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "Atlas Global"
            killSwitch = intent?.getBooleanExtra(EXTRA_KILL_SWITCH, false) ?: false
            val dnsName = intent?.getStringExtra(EXTRA_DNS_PROVIDER) ?: DnsProvider.CLOUDFLARE.name
            dnsProvider = runCatching { DnsProvider.valueOf(dnsName) }.getOrDefault(DnsProvider.CLOUDFLARE)
            val protocolName = intent?.getStringExtra(EXTRA_PROTOCOL) ?: VpnProtocol.AUTO.name
            preferredProtocol = runCatching { VpnProtocol.valueOf(protocolName) }.getOrDefault(VpnProtocol.AUTO)

            autoReconnect = intent?.getBooleanExtra(EXTRA_AUTO_RECONNECT, true) ?: true
            splitTunnelEnabled = intent?.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, false) ?: false
            val splitModeName = intent?.getStringExtra(EXTRA_SPLIT_TUNNEL_MODE) ?: SplitTunnelMode.BYPASS.name
            splitTunnelMode = runCatching { SplitTunnelMode.valueOf(splitModeName) }.getOrDefault(SplitTunnelMode.BYPASS)
            splitTunnelApps = intent?.getStringArrayListExtra(EXTRA_SPLIT_TUNNEL_APPS)?.toSet() ?: emptySet()

            val parsedFromExtra = VpnIpc.serverFromJson(intent?.getStringExtra(EXTRA_SERVER_JSON))
            targetServer = parsedFromExtra ?: VpnStateHolder.activeTargetServer ?: VpnServer(
                id = "intent_server",
                hostName = serverName,
                ip = serverIp,
                port = serverPort,
                countryName = serverName,
                countryCode = "PT",
                city = "Principal",
                continent = Continent.EUROPE,
                protocol = preferredProtocol,
                pingMs = 50,
                speedMbps = 50.0,
                relayPool = if (serverIp.isNotBlank()) listOf(RelayEndpoint(serverIp, serverPort, RelayType.SOCKS5)) else emptyList()
            )
            hasConnectedOnce.set(false)
            baseElapsed = 0L
            baseRx = 0L
            baseTx = 0L
            lastTotalElapsedSeconds = 0L
            lastTotalRxBytes = 0L
            lastTotalTxBytes = 0L
            isPersistentRecovery = false
            VpnStateHolder.activeTargetServer = targetServer
        }

        activeAutoReconnect = autoReconnect
        activeSplitTunnelEnabled = splitTunnelEnabled
        activeSplitTunnelMode = splitTunnelMode
        activeSplitTunnelApps = splitTunnelApps

        activeServerRef = targetServer
        val newGen = sessionGeneration.incrementAndGet()
        stopTunnelOnly()
        persistActiveSession(
            server = targetServer,
            killSwitch = killSwitch,
            dnsProvider = dnsProvider,
            preferredProtocol = preferredProtocol,
            autoReconnect = autoReconnect,
            splitTunnelEnabled = splitTunnelEnabled,
            splitTunnelMode = splitTunnelMode,
            splitTunnelApps = splitTunnelApps,
            isPaused = false,
            hasConnected = hasConnectedOnce.get(),
            accElapsedSeconds = baseElapsed,
            accRxBytes = baseRx,
            accTxBytes = baseTx
        )
        acquireWakeLock()
        startForegroundWithNotification(
            title = "Zerox VPN • Conectando...",
            text = "Validando rota em ${targetServer.countryName}...",
            bigText = null,
            whenMillis = System.currentTimeMillis(),
            useChronometer = false,
            isPausedNotification = false
        )
        startVpn(
            myGen = newGen,
            server = targetServer,
            killSwitch = killSwitch,
            dnsProvider = dnsProvider,
            preferredProtocol = preferredProtocol,
            autoReconnect = autoReconnect,
            splitTunnelEnabled = splitTunnelEnabled,
            splitTunnelMode = splitTunnelMode,
            splitTunnelApps = splitTunnelApps,
            baseElapsedSeconds = baseElapsed,
            baseRxBytes = baseRx,
            baseTxBytes = baseTx,
            isPersistentRecovery = isPersistentRecovery
        )

        return START_STICKY
    }

    private fun pauseVpn() {
        if (manualStopRequested.get()) return
        val saved = loadPersistedSession()
        val server = activeServerRef ?: saved?.server ?: VpnStateHolder.activeTargetServer
        if (server == null) {
            stopVpn(preserveError = false)
            return
        }

        isPausedState.set(true)
        isRunning.set(false)
        sessionGeneration.incrementAndGet()
        stopTunnelOnly()
        releaseWakeLock()

        val accElapsed = lastTotalElapsedSeconds.coerceAtLeast(saved?.accElapsedSeconds ?: 0L)
        val accRx = lastTotalRxBytes.coerceAtLeast(saved?.accRxBytes ?: 0L)
        val accTx = lastTotalTxBytes.coerceAtLeast(saved?.accTxBytes ?: 0L)

        persistActiveSession(
            server = server,
            killSwitch = saved?.killSwitch ?: false,
            dnsProvider = saved?.dnsProvider ?: DnsProvider.CLOUDFLARE,
            preferredProtocol = saved?.preferredProtocol ?: server.protocol,
            autoReconnect = saved?.autoReconnect ?: activeAutoReconnect,
            splitTunnelEnabled = saved?.splitTunnelEnabled ?: activeSplitTunnelEnabled,
            splitTunnelMode = saved?.splitTunnelMode ?: activeSplitTunnelMode,
            splitTunnelApps = saved?.splitTunnelApps ?: activeSplitTunnelApps,
            isPaused = true,
            hasConnected = true,
            accElapsedSeconds = accElapsed,
            accRxBytes = accRx,
            accTxBytes = accTx
        )

        val flag = countryFlag(server.countryCode)
        startForegroundWithNotification(
            title = "Zerox VPN • Em Pausa ($flag ${server.countryName})",
            text = "⏱ ${formatDuration(accElapsed)} • ↓ ${formatTotal(accRx)} • ↑ ${formatTotal(accTx)}",
            bigText = "Túnel pausado em ${server.countryName} (${server.city}).\nToque em Retomar para voltar ao túnel protegido.",
            whenMillis = System.currentTimeMillis(),
            useChronometer = false,
            isPausedNotification = true
        )

        emitConnectionState(
            ConnectionState.Paused(
                server = server,
                pausedAtMillis = System.currentTimeMillis(),
                accumulatedElapsedSeconds = accElapsed,
                accumulatedDownloadBytes = accRx,
                accumulatedUploadBytes = accTx
            )
        )
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZeroxVpn::PersistentTunnel")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun emitConnectionState(state: ConnectionState) {
        VpnStateHolder.updateConnectionState(state)
        VpnIpc.sendStateBroadcast(this, state)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zerox VPN Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado e velocidade em tempo real do túnel VPN"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createServiceActionPendingIntent(requestCode: Int, action: String): PendingIntent {
        val actionIntent = Intent(this, AtlasVpnService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, actionIntent, flags)
        } else {
            PendingIntent.getService(this, requestCode, actionIntent, flags)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        bigText: String?,
        whenMillis: Long,
        useChronometer: Boolean,
        isPausedNotification: Boolean
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val stopPendingIntent = createServiceActionPendingIntent(1, ACTION_STOP_VPN)
        val pauseOrResumePendingIntent = if (isPausedNotification) {
            createServiceActionPendingIntent(3, ACTION_RESUME_VPN)
        } else {
            createServiceActionPendingIntent(2, ACTION_PAUSE_VPN)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(whenMillis)
            .setUsesChronometer(useChronometer)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
                if (contentPendingIntent != null) setContentIntent(contentPendingIntent)
                if (!bigText.isNullOrBlank()) {
                    style = Notification.BigTextStyle().bigText(bigText)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(
                        Notification.Action.Builder(
                            null,
                            if (isPausedNotification) "Retomar" else "Pausar",
                            pauseOrResumePendingIntent
                        ).build()
                    )
                    addAction(
                        Notification.Action.Builder(
                            null,
                            "Desconectar",
                            stopPendingIntent
                        ).build()
                    )
                }
            }

        return builder.build()
    }

    private fun startForegroundWithNotification(
        title: String,
        text: String,
        bigText: String?,
        whenMillis: Long,
        useChronometer: Boolean,
        isPausedNotification: Boolean
    ) {
        ensureNotificationChannel()
        val notification = buildNotification(title, text, bigText, whenMillis, useChronometer, isPausedNotification)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationRealtime(
        title: String,
        text: String,
        bigText: String?,
        whenMillis: Long,
        useChronometer: Boolean,
        isPausedNotification: Boolean
    ) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(title, text, bigText, whenMillis, useChronometer, isPausedNotification)
        )
    }

    private fun startVpn(
        myGen: Long,
        server: VpnServer,
        killSwitch: Boolean,
        dnsProvider: DnsProvider,
        preferredProtocol: VpnProtocol,
        autoReconnect: Boolean = activeAutoReconnect,
        splitTunnelEnabled: Boolean = activeSplitTunnelEnabled,
        splitTunnelMode: SplitTunnelMode = activeSplitTunnelMode,
        splitTunnelApps: Set<String> = activeSplitTunnelApps,
        baseElapsedSeconds: Long = 0L,
        baseRxBytes: Long = 0L,
        baseTxBytes: Long = 0L,
        isPersistentRecovery: Boolean = false
    ) {
        isRunning.set(true)
        connectionJob = serviceScope.launch {
            try {
                delay(150L)
                if (!isActive || sessionGeneration.get() != myGen || manualStopRequested.get() || isPausedState.get()) {
                    return@launch
                }

                var verified: List<RelayEndpoint> = emptyList()
                var rescueAttempt = 0

                while (isActive && sessionGeneration.get() == myGen && !manualStopRequested.get() && !isPausedState.get()) {
                    emitConnectionState(
                        ConnectionState.Connecting(
                            server,
                            if (rescueAttempt == 0) "Validando túnel em ${server.countryName}..."
                            else "Reconectando rota persistente em ${server.countryName}..."
                        )
                    )

                    val candidatePool = buildList {
                        addAll(server.relayPool)
                        if (server.ip.isNotBlank() && server.relayPool.none { it.ip == server.ip && it.port == server.port }) {
                            add(RelayEndpoint(server.ip, server.port, RelayType.SOCKS5))
                        }
                    }.let { pool ->
                        when (preferredProtocol) {
                            VpnProtocol.SOCKS5_TUNNEL -> pool.filter { it.type == RelayType.SOCKS5 || it.type == RelayType.SOCKS4 }.ifEmpty { pool }
                            VpnProtocol.HTTP_TUNNEL -> pool.filter { it.type == RelayType.HTTP_CONNECT }.ifEmpty { pool }
                            VpnProtocol.AUTO -> pool
                        }
                    }

                    if (rescueAttempt == 0 && candidatePool.isNotEmpty()) {
                        verified = candidatePool.take(25).map { endpoint ->
                            async {
                                val rtt = RelayVerifier.quickConnectVerify(
                                    endpoint = endpoint,
                                    timeoutMs = 4500,
                                    protectSocket = { socket -> protect(socket) }
                                )
                                rtt?.let {
                                    endpoint.copy(
                                        measuredPingMs = it,
                                        supportsCustomPorts = endpoint.type == RelayType.SOCKS5 || endpoint.type == RelayType.SOCKS4
                                    )
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }

                    if (!isActive || !isRunning.get() || sessionGeneration.get() != myGen || manualStopRequested.get() || isPausedState.get()) {
                        return@launch
                    }

                    if (verified.isEmpty() && server.countryCode.length == 2) {
                        val rescueStatus = "Buscando rota ativa em ${server.countryName}..."
                        updateNotificationRealtime(
                            title = "Zerox VPN • Conectando...",
                            text = rescueStatus,
                            bigText = null,
                            whenMillis = System.currentTimeMillis(),
                            useChronometer = false,
                            isPausedNotification = false
                        )
                        emitConnectionState(ConnectionState.Connecting(server, rescueStatus))

                        val alreadyTried = if (rescueAttempt == 0) {
                            candidatePool.map { "${it.ip}:${it.port}" }.toSet()
                        } else emptySet()
                        val freshRaw = ServerRepository().findFreshEndpointsForCountry(server.countryCode)
                            .filter { "${it.ip}:${it.port}" !in alreadyTried }
                            .let { pool ->
                                when (preferredProtocol) {
                                    VpnProtocol.SOCKS5_TUNNEL -> pool.filter { it.type == RelayType.SOCKS5 || it.type == RelayType.SOCKS4 }.ifEmpty { pool }
                                    VpnProtocol.HTTP_TUNNEL -> pool.filter { it.type == RelayType.HTTP_CONNECT }.ifEmpty { pool }
                                    VpnProtocol.AUTO -> pool
                                }
                            }
                            .take(35)

                        if (freshRaw.isNotEmpty()) {
                            verified = freshRaw.map { ep ->
                                async {
                                    val probe = RelayVerifier.probeEndpoint(
                                        endpoint = ep,
                                        timeoutMs = 3600,
                                        maxTotalMs = 4500,
                                        checkUdpIfSocks5 = false,
                                        protectSocket = { socket -> protect(socket) }
                                    )
                                    probe?.let {
                                        ep.copy(
                                            measuredPingMs = it.rttMs,
                                            supportsCustomPorts = it.supportsCustomPorts
                                        )
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }
                    }

                    if (verified.isNotEmpty()) break

                    val mustPersist = isPersistentRecovery || hasConnectedOnce.get()
                    if (!mustPersist && rescueAttempt >= 1) {
                        clearPersistedSession()
                        emitConnectionState(
                            ConnectionState.Error(
                                "Nenhum servidor ativo respondeu em ${server.countryName} agora. Atualize a lista de Regiões ou tente outro país próximo."
                            )
                        )
                        stopVpn(preserveError = true, expectedGen = myGen)
                        return@launch
                    }

                    rescueAttempt++
                    updateNotificationRealtime(
                        title = "Zerox VPN • Mantendo Ligação...",
                        text = "Aguardando rede em ${server.countryName}...",
                        bigText = "Conexão persistente ativa. Tentando restabelecer rota automaticamente...",
                        whenMillis = System.currentTimeMillis(),
                        useChronometer = false,
                        isPausedNotification = false
                    )
                    delay(3000L)
                }

                if (!isActive || !isRunning.get() || sessionGeneration.get() != myGen || manualStopRequested.get() || isPausedState.get()) {
                    return@launch
                }

                verified = verified.sortedWith(
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

                val primaryEp = verified.first()
                if (primaryEp.type == RelayType.SOCKS5 && !primaryEp.supportsUdp) {
                    val udpOk = RelayVerifier.checkSocks5UdpSupport(
                        endpoint = primaryEp,
                        timeoutMs = 1200,
                        protectSocket = { socket -> protect(socket) },
                        protectDatagram = { dgram -> protect(dgram) }
                    )
                    if (udpOk) {
                        verified = listOf(primaryEp.copy(supportsUdp = true)) + verified.drop(1)
                    }
                }

                val updatedServer = server.copy(
                    ip = verified.first().ip,
                    port = verified.first().port,
                    pingMs = verified.first().measuredPingMs ?: server.pingMs,
                    relayPool = verified,
                    supportsUdp = verified.any { it.supportsUdp }
                )
                activeServerRef = updatedServer
                VpnStateHolder.activeTargetServer = updatedServer
                hasConnectedOnce.set(true)
                persistActiveSession(
                    server = updatedServer,
                    killSwitch = killSwitch,
                    dnsProvider = dnsProvider,
                    preferredProtocol = preferredProtocol,
                    autoReconnect = autoReconnect,
                    splitTunnelEnabled = splitTunnelEnabled,
                    splitTunnelMode = splitTunnelMode,
                    splitTunnelApps = splitTunnelApps,
                    isPaused = false,
                    hasConnected = true,
                    accElapsedSeconds = baseElapsedSeconds,
                    accRxBytes = baseRxBytes,
                    accTxBytes = baseTxBytes
                )

                val relay = LocalSocks5Relay(
                    initialRelayPool = verified,
                    dnsProvider = dnsProvider,
                    allowDirectUdpFallback = !killSwitch,
                    protectSocket = { socket -> protect(socket) },
                    protectDatagram = { dgram -> protect(dgram) }
                )
                val localPort = relay.start()
                localRelay = relay

                // Route ALL IPv4 & IPv6 device traffic via TUN; MTU 1360 prevents 4G/5G TLS fragmentation
                val vpnBuilder = Builder()
                    .setSession("Zerox VPN (${updatedServer.countryName})")
                    .addAddress("198.18.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fc00::1", 128)
                    .addRoute("::", 0)
                    .addDnsServer(dnsProvider.primaryIp)
                    .addDnsServer(dnsProvider.secondaryIp)
                    .setMtu(1360)

                if (splitTunnelEnabled && splitTunnelApps.isNotEmpty()) {
                    when (splitTunnelMode) {
                        SplitTunnelMode.BYPASS -> {
                            vpnBuilder.addDisallowedApplication(packageName)
                            for (pkg in splitTunnelApps) {
                                if (pkg != packageName) {
                                    runCatching { vpnBuilder.addDisallowedApplication(pkg) }
                                }
                            }
                        }
                        SplitTunnelMode.ONLY_SELECTED -> {
                            for (pkg in splitTunnelApps) {
                                if (pkg != packageName) {
                                    runCatching { vpnBuilder.addAllowedApplication(pkg) }
                                }
                            }
                        }
                    }
                } else {
                    vpnBuilder.addDisallowedApplication(packageName)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vpnBuilder.setMetered(false)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    lastActiveNetwork?.let { net ->
                        runCatching { setUnderlyingNetworks(arrayOf(net)) }
                    }
                }

                val pfd = vpnBuilder.establish()
                if (pfd == null) {
                    if (isPersistentRecovery || hasConnectedOnce.get()) {
                        delay(2000L)
                        if (isActive && sessionGeneration.get() == myGen && !manualStopRequested.get() && !isPausedState.get()) {
                            val nextGen = sessionGeneration.incrementAndGet()
                            stopTunnelOnly()
                            startVpn(
                                myGen = nextGen,
                                server = updatedServer,
                                killSwitch = killSwitch,
                                dnsProvider = dnsProvider,
                                preferredProtocol = preferredProtocol,
                                autoReconnect = autoReconnect,
                                splitTunnelEnabled = splitTunnelEnabled,
                                splitTunnelMode = splitTunnelMode,
                                splitTunnelApps = splitTunnelApps,
                                baseElapsedSeconds = baseElapsedSeconds,
                                baseRxBytes = baseRxBytes,
                                baseTxBytes = baseTxBytes,
                                isPersistentRecovery = true
                            )
                        }
                        return@launch
                    }
                    clearPersistedSession()
                    emitConnectionState(
                        ConnectionState.Error("Falha ao criar interface VPN do sistema.")
                    )
                    stopVpn(preserveError = true, expectedGen = myGen)
                    return@launch
                }
                tunnelInterface = pfd

                val configPath = writeHevConfig(localPort)
                launchNativeTunnelLoop(myGen, configPath, pfd.fd)
                delay(180L)
                if (!isActive || !isRunning.get() || sessionGeneration.get() != myGen || manualStopRequested.get() || isPausedState.get()) {
                    return@launch
                }

                val connectedAt = System.currentTimeMillis()
                val activeNode = verified.first()
                val displayIp = activeNode.exitIp.ifBlank { activeNode.ip }
                val livePing = activeNode.measuredPingMs ?: updatedServer.pingMs

                updateNotificationRealtime(
                    title = "Zerox VPN • ${countryFlag(updatedServer.countryCode)} ${updatedServer.countryName} (${livePing} ms)",
                    text = "⏱ ${formatDuration(baseElapsedSeconds)} • ↓ 0 B/s • ↑ 0 B/s • IP $displayIp",
                    bigText = "Conectado a ${updatedServer.countryName} (${updatedServer.city})\nIP de saída: $displayIp • Ping: ${livePing} ms",
                    whenMillis = connectedAt - (baseElapsedSeconds * 1000L),
                    useChronometer = true,
                    isPausedNotification = false
                )

                emitConnectionState(
                    ConnectionState.Connected(
                        server = updatedServer,
                        connectedAtMillis = connectedAt - (baseElapsedSeconds * 1000L),
                        activeRelayIp = displayIp
                    )
                )

                startStatsPolling(
                    myGen = myGen,
                    connectedAtMillis = connectedAt,
                    server = updatedServer,
                    displayIp = displayIp,
                    pingMs = livePing,
                    killSwitch = killSwitch,
                    dnsProvider = dnsProvider,
                    preferredProtocol = preferredProtocol,
                    baseElapsedSeconds = baseElapsedSeconds,
                    baseRxBytes = baseRxBytes,
                    baseTxBytes = baseTxBytes
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionGeneration.get() == myGen && !manualStopRequested.get() && !isPausedState.get()) {
                    Log.e(TAG, "VPN start failure, persistent auto-retry", e)
                    if (isPersistentRecovery || hasConnectedOnce.get()) {
                        delay(2000L)
                        if (sessionGeneration.get() == myGen && !manualStopRequested.get() && !isPausedState.get()) {
                            val nextGen = sessionGeneration.incrementAndGet()
                            stopTunnelOnly()
                            startVpn(
                                myGen = nextGen,
                                server = server,
                                killSwitch = killSwitch,
                                dnsProvider = dnsProvider,
                                preferredProtocol = preferredProtocol,
                                autoReconnect = autoReconnect,
                                splitTunnelEnabled = splitTunnelEnabled,
                                splitTunnelMode = splitTunnelMode,
                                splitTunnelApps = splitTunnelApps,
                                baseElapsedSeconds = lastTotalElapsedSeconds,
                                baseRxBytes = lastTotalRxBytes,
                                baseTxBytes = lastTotalTxBytes,
                                isPersistentRecovery = true
                            )
                        }
                    } else {
                        clearPersistedSession()
                        emitConnectionState(
                            ConnectionState.Error("Erro ao conectar: ${e.localizedMessage ?: "Falha de rede"}")
                        )
                        stopVpn(preserveError = true, expectedGen = myGen)
                    }
                }
            }
        }
    }

    private fun refreshRelayPoolInBackground(server: VpnServer, expectedGen: Long, relay: LocalSocks5Relay) {
        if (!isRefreshingPool.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                if (server.countryCode.length != 2) return@launch
                val freshRaw = ServerRepository().findFreshEndpointsForCountry(server.countryCode).take(25)
                if (freshRaw.isEmpty()) return@launch
                val freshVerified = freshRaw.map { ep ->
                    async {
                        val probe = RelayVerifier.probeEndpoint(
                            endpoint = ep,
                            timeoutMs = 3000,
                            maxTotalMs = 4000,
                            checkUdpIfSocks5 = false,
                            protectSocket = { socket -> protect(socket) }
                        )
                        probe?.let {
                            ep.copy(
                                measuredPingMs = it.rttMs,
                                supportsCustomPorts = it.supportsCustomPorts
                            )
                        }
                    }
                }.awaitAll().filterNotNull().sortedWith(
                    compareByDescending<RelayEndpoint> { it.supportsUdp }
                        .thenBy { it.measuredPingMs ?: Int.MAX_VALUE }
                )
                if (freshVerified.isNotEmpty() && sessionGeneration.get() == expectedGen && !manualStopRequested.get() && !isPausedState.get()) {
                    relay.updateRelayPool(freshVerified)
                    activeServerRef = server.copy(
                        ip = freshVerified.first().ip,
                        port = freshVerified.first().port,
                        relayPool = freshVerified
                    )
                }
            } catch (_: Exception) {
            } finally {
                isRefreshingPool.set(false)
            }
        }
    }

    private fun launchNativeTunnelLoop(myGen: Long, configPath: String, tunFd: Int) {
        Thread({
            while (isRunning.get() && sessionGeneration.get() == myGen && !manualStopRequested.get() && !isPausedState.get()) {
                val pfdValid = tunnelInterface?.fileDescriptor?.valid() == true
                if (!pfdValid) break
                runCatching {
                    TProxyService.TProxyStartService(configPath, tunFd)
                }.onFailure { err ->
                    Log.e(TAG, "TProxyStartService exited with error", err)
                }
                if (!isRunning.get() || sessionGeneration.get() != myGen || manualStopRequested.get() || isPausedState.get()) break
                runCatching { Thread.sleep(350L) }
            }
        }, "hev-socks5-tunnel-$myGen").start()
    }

    private fun writeHevConfig(localSocksPort: Int): String {
        val yaml = """
            tunnel:
              name: tun0
              mtu: 1360
              multi-queue: false
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
            socks5:
              port: $localSocksPort
              address: 127.0.0.1
              udp: 'udp'
            misc:
              connect-timeout: 12000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: warn
        """.trimIndent()

        val configFile = File(cacheDir, "hev-tunnel.yml")
        FileOutputStream(configFile).use { it.write(yaml.toByteArray(Charsets.UTF_8)) }
        return configFile.absolutePath
    }

    private fun startStatsPolling(
        myGen: Long,
        connectedAtMillis: Long,
        server: VpnServer,
        displayIp: String,
        pingMs: Int,
        killSwitch: Boolean,
        dnsProvider: DnsProvider,
        preferredProtocol: VpnProtocol,
        baseElapsedSeconds: Long,
        baseRxBytes: Long,
        baseTxBytes: Long
    ) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var prevSessionRx = 0L
            var prevSessionTx = 0L
            val flag = countryFlag(server.countryCode)
            var tick = 0
            while (isActive && isRunning.get() && sessionGeneration.get() == myGen && !manualStopRequested.get() && !isPausedState.get()) {
                delay(1000L)
                tick++

                // Self-heal immediately if Android tore down TUN unexpectedly without manual stop
                val tunAlive = tunnelInterface?.fileDescriptor?.valid() == true
                if (!tunAlive) {
                    if (!manualStopRequested.get() && !isPausedState.get()) {
                        Log.w(TAG, "TUN closed unexpectedly by system, self-healing persistent tunnel...")
                        val nextGen = sessionGeneration.incrementAndGet()
                        stopTunnelOnly()
                        updateNotificationRealtime(
                            title = "Zerox VPN • Reconectando...",
                            text = "Restaurando túnel persistente em ${server.countryName}...",
                            bigText = null,
                            whenMillis = System.currentTimeMillis(),
                            useChronometer = false,
                            isPausedNotification = false
                        )
                        startVpn(
                            myGen = nextGen,
                            server = activeServerRef ?: server,
                            killSwitch = killSwitch,
                            dnsProvider = dnsProvider,
                            preferredProtocol = preferredProtocol,
                            baseElapsedSeconds = lastTotalElapsedSeconds,
                            baseRxBytes = lastTotalRxBytes,
                            baseTxBytes = lastTotalTxBytes,
                            isPersistentRecovery = true
                        )
                    }
                    break
                }

                // Auto-heal proxy pool silently in background if upstream proxies rotate/fail
                val relay = localRelay
                if (relay != null && relay.getConsecutiveFailures() >= 3) {
                    refreshRelayPoolInBackground(activeServerRef ?: server, myGen, relay)
                }

                val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                val sessionTx = stats?.getOrNull(1) ?: prevSessionTx
                val sessionRx = stats?.getOrNull(3) ?: prevSessionRx
                val rxRate = (sessionRx - prevSessionRx).coerceAtLeast(0L)
                val txRate = (sessionTx - prevSessionTx).coerceAtLeast(0L)
                prevSessionRx = sessionRx
                prevSessionTx = sessionTx

                val totalRx = baseRxBytes + sessionRx
                val totalTx = baseTxBytes + sessionTx
                val totalElapsed = baseElapsedSeconds + ((System.currentTimeMillis() - connectedAtMillis) / 1000L)

                lastTotalElapsedSeconds = totalElapsed
                lastTotalRxBytes = totalRx
                lastTotalTxBytes = totalTx

                if (tick % 10 == 0) {
                    persistActiveSession(
                        server = activeServerRef ?: server,
                        killSwitch = killSwitch,
                        dnsProvider = dnsProvider,
                        preferredProtocol = preferredProtocol,
                        isPaused = false,
                        hasConnected = true,
                        accElapsedSeconds = totalElapsed,
                        accRxBytes = totalRx,
                        accTxBytes = totalTx
                    )
                }

                val metrics = TrafficMetrics(
                    downloadBytesPerSec = rxRate,
                    uploadBytesPerSec = txRate,
                    totalDownloadBytes = totalRx,
                    totalUploadBytes = totalTx,
                    elapsedSeconds = totalElapsed
                )
                VpnStateHolder.updateTrafficMetrics(metrics)
                VpnIpc.sendMetricsBroadcast(this@AtlasVpnService, metrics)

                val currentRelayIp = relay?.currentActiveEndpoint()?.let { it.exitIp.ifBlank { it.ip } } ?: displayIp

                // Keep top status notification live every second with Pause & Disconnect actions
                updateNotificationRealtime(
                    title = "Zerox VPN • $flag ${server.countryName} (${pingMs} ms)",
                    text = "⏱ ${formatDuration(totalElapsed)} • ↓ ${formatRate(rxRate)} • ↑ ${formatRate(txRate)}",
                    bigText = "Conectado a ${server.countryName} (${server.city})\n" +
                        "IP: $currentRelayIp • Ping: ${pingMs} ms\n" +
                        "↓ ${formatRate(rxRate)} (${formatTotal(totalRx)}) • ↑ ${formatRate(txRate)} (${formatTotal(totalTx)})",
                    whenMillis = connectedAtMillis - (baseElapsedSeconds * 1000L),
                    useChronometer = true,
                    isPausedNotification = false
                )
            }
        }
    }

    private fun stopTunnelOnly() {
        connectionJob?.cancel()
        statsJob?.cancel()
        runCatching { TProxyService.TProxyStopService() }
        runCatching { localRelay?.stop() }
        localRelay = null
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
    }

    private fun stopVpn(preserveError: Boolean = false, expectedGen: Long? = null) {
        if (expectedGen != null && sessionGeneration.get() != expectedGen) return
        sessionGeneration.incrementAndGet()
        isRunning.set(false)
        stopTunnelOnly()
        releaseWakeLock()
        if (!preserveError) {
            emitConnectionState(ConnectionState.Disconnected)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    private fun schedulePersistentRestart() {
        if (manualStopRequested.get() || !hasPersistedSession(this)) return
        runCatching {
            val restartIntent = Intent(applicationContext, AtlasVpnService::class.java).apply {
                action = ACTION_RESTORE_PERSISTENT
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(applicationContext, 99, restartIntent, flags)
            } else {
                PendingIntent.getService(applicationContext, 99, restartIntent, flags)
            }
            val am = getSystemService(ALARM_SERVICE) as? AlarmManager
            am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000L, pi)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep VPN service alive when user removes the UI task or switches apps
        if (!manualStopRequested.get() && hasPersistedSession(this)) {
            schedulePersistentRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        // User revoked VPN permission in Android system settings or replaced VPN app
        manualStopRequested.set(true)
        isPausedState.set(false)
        clearPersistedSession()
        stopVpn(preserveError = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusRequestReceiver) }
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null

        if (manualStopRequested.get() || !hasPersistedSession(this)) {
            val preserveError = VpnStateHolder.connectionState.value is ConnectionState.Error
            stopVpn(preserveError = preserveError)
        } else {
            // Unexpected OS kill without manual disconnect: clean sockets only and schedule auto-restore
            isRunning.set(false)
            stopTunnelOnly()
            releaseWakeLock()
            schedulePersistentRestart()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun persistActiveSession(
        server: VpnServer,
        killSwitch: Boolean,
        dnsProvider: DnsProvider,
        preferredProtocol: VpnProtocol,
        autoReconnect: Boolean = true,
        splitTunnelEnabled: Boolean = false,
        splitTunnelMode: SplitTunnelMode = SplitTunnelMode.BYPASS,
        splitTunnelApps: Set<String> = emptySet(),
        isPaused: Boolean,
        hasConnected: Boolean,
        accElapsedSeconds: Long,
        accRxBytes: Long,
        accTxBytes: Long
    ) {
        runCatching {
            val appsArray = org.json.JSONArray()
            splitTunnelApps.forEach { appsArray.put(it) }
            val root = VpnIpc.serverToJson(server).apply {
                put("killSwitch", killSwitch)
                put("dnsProvider", dnsProvider.name)
                put("protocol", preferredProtocol.name)
                put("autoReconnect", autoReconnect)
                put("splitTunnelEnabled", splitTunnelEnabled)
                put("splitTunnelMode", splitTunnelMode.name)
                put("splitTunnelApps", appsArray)
                put("isPaused", isPaused)
                put("hasConnected", hasConnected)
                put("accElapsed", accElapsedSeconds)
                put("accRx", accRxBytes)
                put("accTx", accTxBytes)
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_JSON, root.toString())
                .commit()
        }
    }

    private fun loadPersistedSession(): SavedSession? {
        return runCatching {
            val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ACTIVE_JSON, null)
                ?: return null
            val root = JSONObject(raw)
            val server = VpnIpc.serverFromJson(raw) ?: return null
            val protocol = runCatching { VpnProtocol.valueOf(root.optString("protocol")) }
                .getOrDefault(VpnProtocol.AUTO)
            val dns = runCatching { DnsProvider.valueOf(root.optString("dnsProvider")) }
                .getOrDefault(DnsProvider.CLOUDFLARE)
            val splitMode = runCatching { SplitTunnelMode.valueOf(root.optString("splitTunnelMode")) }
                .getOrDefault(SplitTunnelMode.BYPASS)
            val rawApps = root.optJSONArray("splitTunnelApps") ?: org.json.JSONArray()
            val apps = buildSet {
                for (i in 0 until rawApps.length()) {
                    add(rawApps.optString(i))
                }
            }
            SavedSession(
                server = server,
                killSwitch = root.optBoolean("killSwitch", false),
                dnsProvider = dns,
                preferredProtocol = protocol,
                autoReconnect = root.optBoolean("autoReconnect", true),
                splitTunnelEnabled = root.optBoolean("splitTunnelEnabled", false),
                splitTunnelMode = splitMode,
                splitTunnelApps = apps,
                isPaused = root.optBoolean("isPaused", false),
                hasConnected = root.optBoolean("hasConnected", false),
                accElapsedSeconds = root.optLong("accElapsed", 0L),
                accRxBytes = root.optLong("accRx", 0L),
                accTxBytes = root.optLong("accTx", 0L)
            )
        }.getOrNull()
    }

    private fun clearPersistedSession() {
        runCatching {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_ACTIVE_JSON)
                .commit()
        }
    }

    private fun countryFlag(code: String): String {
        val cc = code.uppercase(Locale.US)
        if (cc.length != 2) return "🌐"
        val first = Character.codePointAt(cc, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(cc, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatRate(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    private fun formatTotal(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
