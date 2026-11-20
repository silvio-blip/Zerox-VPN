package com.example.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.model.ConnectionState
import com.example.model.TrafficMetrics
import com.example.model.VpnServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object VpnStateHolder {
    @Volatile
    var activeTargetServer: VpnServer? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _trafficMetrics = MutableStateFlow(TrafficMetrics())
    val trafficMetrics: StateFlow<TrafficMetrics> = _trafficMetrics.asStateFlow()

    private val receiverRegistered = AtomicBoolean(false)
    private val lastSyncReplyMs = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initClientSync(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(VpnIpc.ACTION_VPN_STATE)
            addAction(VpnIpc.ACTION_VPN_METRICS)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                lastSyncReplyMs.set(System.currentTimeMillis())
                when (intent.action) {
                    VpnIpc.ACTION_VPN_STATE -> {
                        VpnIpc.parseStateFromIntent(intent)?.let { parsed ->
                            updateConnectionState(parsed)
                        }
                    }
                    VpnIpc.ACTION_VPN_METRICS -> {
                        updateTrafficMetrics(VpnIpc.parseMetricsFromIntent(intent))
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            appCtx,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun requestSync(context: Context) {
        val appCtx = context.applicationContext
        val requestSentAt = System.currentTimeMillis()
        runCatching {
            appCtx.sendBroadcast(
                Intent(VpnIpc.ACTION_REQUEST_STATUS).setPackage(appCtx.packageName)
            )
        }
        // If UI thinks VPN is active/paused but :vpn process doesn't reply within 2.5s, reconcile stale UI state
        mainHandler.postDelayed({
            val state = _connectionState.value
            val isStaleActive = state is ConnectionState.Connected ||
                state is ConnectionState.Connecting ||
                state is ConnectionState.Paused
            if (isStaleActive && lastSyncReplyMs.get() < requestSentAt) {
                updateConnectionState(ConnectionState.Disconnected)
            }
        }, 2500L)
    }

    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
        when (state) {
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                _trafficMetrics.value = TrafficMetrics()
            }
            is ConnectionState.Paused -> {
                _trafficMetrics.value = TrafficMetrics(
                    downloadBytesPerSec = 0L,
                    uploadBytesPerSec = 0L,
                    totalDownloadBytes = state.accumulatedDownloadBytes,
                    totalUploadBytes = state.accumulatedUploadBytes,
                    elapsedSeconds = state.accumulatedElapsedSeconds
                )
            }
            else -> Unit
        }
    }

    fun updateTrafficMetrics(metrics: TrafficMetrics) {
        _trafficMetrics.value = metrics
    }
}
