package com.example.tunnel

import android.content.Context
import android.content.Intent
import com.example.model.ConnectionState
import com.example.model.Continent
import com.example.model.RelayEndpoint
import com.example.model.RelayType
import com.example.model.TrafficMetrics
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import org.json.JSONArray
import org.json.JSONObject

object VpnIpc {
    const val ACTION_VPN_STATE = "com.example.ACTION_VPN_STATE"
    const val ACTION_VPN_METRICS = "com.example.ACTION_VPN_METRICS"
    const val ACTION_REQUEST_STATUS = "com.example.ACTION_REQUEST_STATUS"

    private const val EXTRA_STATE_TYPE = "state_type"
    private const val EXTRA_SERVER_JSON = "server_json"
    private const val EXTRA_STATUS_DETAIL = "status_detail"
    private const val EXTRA_CONNECTED_AT = "connected_at"
    private const val EXTRA_ACTIVE_RELAY_IP = "active_relay_ip"
    private const val EXTRA_ERROR_MSG = "error_msg"

    private const val EXTRA_RX_RATE = "rx_rate"
    private const val EXTRA_TX_RATE = "tx_rate"
    private const val EXTRA_RX_TOTAL = "rx_total"
    private const val EXTRA_TX_TOTAL = "tx_total"
    private const val EXTRA_ELAPSED = "elapsed"

    private const val TYPE_DISCONNECTED = "DISCONNECTED"
    private const val TYPE_PREPARING = "PREPARING"
    private const val TYPE_CONNECTING = "CONNECTING"
    private const val TYPE_CONNECTED = "CONNECTED"
    private const val TYPE_PAUSED = "PAUSED"
    private const val TYPE_ERROR = "ERROR"
    private const val EXTRA_PAUSED_AT = "paused_at"

    fun serverToJson(server: VpnServer): JSONObject {
        val poolArr = JSONArray()
        server.relayPool.forEach { ep ->
            poolArr.put(
                JSONObject().apply {
                    put("ip", ep.ip)
                    put("port", ep.port)
                    put("type", ep.type.name)
                    put("supportsUdp", ep.supportsUdp)
                    put("supportsCustomPorts", ep.supportsCustomPorts)
                    put("exitIp", ep.exitIp)
                    put("exitCountryCode", ep.exitCountryCode)
                    ep.measuredPingMs?.let { put("ping", it) }
                }
            )
        }
        return JSONObject().apply {
            put("id", server.id)
            put("hostName", server.hostName)
            put("ip", server.ip)
            put("port", server.port)
            put("countryName", server.countryName)
            put("countryCode", server.countryCode)
            put("city", server.city)
            put("continent", server.continent.name)
            put("protocol", server.protocol.name)
            put("pingMs", server.pingMs)
            put("speedMbps", server.speedMbps)
            put("isVerified", server.isVerified)
            put("supportsUdp", server.supportsUdp)
            put("relayPool", poolArr)
        }
    }

    fun serverFromJson(raw: String?): VpnServer? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val poolArr = root.optJSONArray("relayPool") ?: JSONArray()
            val pool = buildList {
                for (i in 0 until poolArr.length()) {
                    val obj = poolArr.optJSONObject(i) ?: continue
                    val type = runCatching { RelayType.valueOf(obj.optString("type")) }
                        .getOrDefault(RelayType.SOCKS5)
                    add(
                        RelayEndpoint(
                            ip = obj.optString("ip"),
                            port = obj.optInt("port"),
                            type = type,
                            measuredPingMs = if (obj.has("ping")) obj.optInt("ping") else null,
                            supportsUdp = obj.optBoolean("supportsUdp", false),
                            supportsCustomPorts = obj.optBoolean("supportsCustomPorts", false),
                            exitIp = obj.optString("exitIp", ""),
                            exitCountryCode = obj.optString("exitCountryCode", "")
                        )
                    )
                }
            }
            val continent = runCatching { Continent.valueOf(root.optString("continent")) }
                .getOrDefault(Continent.EUROPE)
            val protocol = runCatching { VpnProtocol.valueOf(root.optString("protocol")) }
                .getOrDefault(VpnProtocol.AUTO)
            VpnServer(
                id = root.optString("id"),
                hostName = root.optString("hostName"),
                ip = root.optString("ip"),
                port = root.optInt("port"),
                countryName = root.optString("countryName"),
                countryCode = root.optString("countryCode"),
                city = root.optString("city"),
                continent = continent,
                protocol = protocol,
                pingMs = root.optInt("pingMs", 80),
                speedMbps = root.optDouble("speedMbps", 50.0),
                relayPool = pool,
                isVerified = root.optBoolean("isVerified", true),
                supportsUdp = root.optBoolean("supportsUdp", false)
            )
        }.getOrNull()
    }

    fun sendStateBroadcast(context: Context, state: ConnectionState) {
        val intent = Intent(ACTION_VPN_STATE).setPackage(context.packageName)
        when (state) {
            is ConnectionState.Disconnected -> {
                intent.putExtra(EXTRA_STATE_TYPE, TYPE_DISCONNECTED)
            }
            is ConnectionState.Preparing -> {
                intent.putExtra(EXTRA_STATE_TYPE, TYPE_PREPARING)
            }
            is ConnectionState.Connecting -> {
                intent.putExtra(EXTRA_STATE_TYPE, TYPE_CONNECTING)
                intent.putExtra(EXTRA_SERVER_JSON, serverToJson(state.server).toString())
                intent.putExtra(EXTRA_STATUS_DETAIL, state.statusDetail)
            }
            is ConnectionState.Connected -> {
                intent.putExtra(EXTRA_STATE_TYPE, TYPE_CONNECTED)
                intent.putExtra(EXTRA_SERVER_JSON, serverToJson(state.server).toString())
                intent.putExtra(EXTRA_CONNECTED_AT, state.connectedAtMillis)
                intent.putExtra(EXTRA_ACTIVE_RELAY_IP, state.activeRelayIp)
            }
            is ConnectionState.Paused -> {
                intent.putExtra(EXTRA_STATE_TYPE, TYPE_PAUSED)
                intent.putExtra(EXTRA_SERVER_JSON, serverToJson(state.server).toString())
                intent.putExtra(EXTRA_PAUSED_AT, state.pausedAtMillis)
                intent.putExtra(EXTRA_ELAPSED, state.accumulatedElapsedSeconds)
                intent.putExtra(EXTRA_RX_TOTAL, state.accumulatedDownloadBytes)
                intent.putExtra(EXTRA_TX_TOTAL, state.accumulatedUploadBytes)
            }
            is ConnectionState.Error -> {
                intent.putExtra(EXTRA_STATE_TYPE, TYPE_ERROR)
                intent.putExtra(EXTRA_ERROR_MSG, state.message)
            }
        }
        runCatching { context.sendBroadcast(intent) }
    }

    fun sendMetricsBroadcast(context: Context, metrics: TrafficMetrics) {
        val intent = Intent(ACTION_VPN_METRICS).setPackage(context.packageName).apply {
            putExtra(EXTRA_RX_RATE, metrics.downloadBytesPerSec)
            putExtra(EXTRA_TX_RATE, metrics.uploadBytesPerSec)
            putExtra(EXTRA_RX_TOTAL, metrics.totalDownloadBytes)
            putExtra(EXTRA_TX_TOTAL, metrics.totalUploadBytes)
            putExtra(EXTRA_ELAPSED, metrics.elapsedSeconds)
        }
        runCatching { context.sendBroadcast(intent) }
    }

    fun parseStateFromIntent(intent: Intent): ConnectionState? {
        return when (intent.getStringExtra(EXTRA_STATE_TYPE)) {
            TYPE_DISCONNECTED -> ConnectionState.Disconnected
            TYPE_PREPARING -> ConnectionState.Preparing
            TYPE_CONNECTING -> {
                val server = serverFromJson(intent.getStringExtra(EXTRA_SERVER_JSON)) ?: return null
                val detail = intent.getStringExtra(EXTRA_STATUS_DETAIL) ?: "Conectando..."
                ConnectionState.Connecting(server, detail)
            }
            TYPE_CONNECTED -> {
                val server = serverFromJson(intent.getStringExtra(EXTRA_SERVER_JSON)) ?: return null
                val connectedAt = intent.getLongExtra(EXTRA_CONNECTED_AT, System.currentTimeMillis())
                val relayIp = intent.getStringExtra(EXTRA_ACTIVE_RELAY_IP) ?: server.ip
                ConnectionState.Connected(server, connectedAt, relayIp)
            }
            TYPE_PAUSED -> {
                val server = serverFromJson(intent.getStringExtra(EXTRA_SERVER_JSON)) ?: return null
                val pausedAt = intent.getLongExtra(EXTRA_PAUSED_AT, System.currentTimeMillis())
                val elapsed = intent.getLongExtra(EXTRA_ELAPSED, 0L)
                val rxTotal = intent.getLongExtra(EXTRA_RX_TOTAL, 0L)
                val txTotal = intent.getLongExtra(EXTRA_TX_TOTAL, 0L)
                ConnectionState.Paused(server, pausedAt, elapsed, rxTotal, txTotal)
            }
            TYPE_ERROR -> {
                val msg = intent.getStringExtra(EXTRA_ERROR_MSG) ?: "Erro na conexão VPN."
                ConnectionState.Error(msg)
            }
            else -> null
        }
    }

    fun parseMetricsFromIntent(intent: Intent): TrafficMetrics {
        return TrafficMetrics(
            downloadBytesPerSec = intent.getLongExtra(EXTRA_RX_RATE, 0L),
            uploadBytesPerSec = intent.getLongExtra(EXTRA_TX_RATE, 0L),
            totalDownloadBytes = intent.getLongExtra(EXTRA_RX_TOTAL, 0L),
            totalUploadBytes = intent.getLongExtra(EXTRA_TX_TOTAL, 0L),
            elapsedSeconds = intent.getLongExtra(EXTRA_ELAPSED, 0L)
        )
    }
}
