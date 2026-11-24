package com.example.model

enum class DnsProvider(val label: String, val primaryIp: String, val secondaryIp: String, val dohUrl: String) {
    CLOUDFLARE("Cloudflare (1.1.1.1)", "1.1.1.1", "1.0.0.1", "https://1.1.1.1/dns-query"),
    GOOGLE("Google (8.8.8.8)", "8.8.8.8", "8.8.4.4", "https://8.8.8.8/resolve"),
    QUAD9("Quad9 (9.9.9.9)", "9.9.9.9", "149.112.112.112", "https://dns.quad9.net:5053/dns-query")
}

enum class SplitTunnelMode(val label: String, val description: String) {
    BYPASS("Excluir Apps (Bypass)", "As aplicações selecionadas usarão a ligação normal sem passar pela VPN."),
    ONLY_SELECTED("Apenas Apps Selecionadas", "Apenas as aplicações selecionadas passarão pelo túnel encriptado da VPN.")
}

sealed interface ConnectionState {
    object Disconnected : ConnectionState
    object Preparing : ConnectionState
    data class Connecting(val server: VpnServer, val statusDetail: String = "Verificando rota segura...") : ConnectionState
    data class Connected(
        val server: VpnServer,
        val connectedAtMillis: Long,
        val activeRelayIp: String,
        val virtualIp: String = "198.18.0.1"
    ) : ConnectionState
    data class Paused(
        val server: VpnServer,
        val pausedAtMillis: Long,
        val accumulatedElapsedSeconds: Long = 0L,
        val accumulatedDownloadBytes: Long = 0L,
        val accumulatedUploadBytes: Long = 0L
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class TrafficMetrics(
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val elapsedSeconds: Long = 0L
)

data class SpeedTestResult(
    val isTesting: Boolean = false,
    val phase: String = "Pronto",
    val pingMs: Int = 0,
    val jitterMs: Int = 0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val progress: Float = 0f
)

data class VpnSettings(
    val killSwitchEnabled: Boolean = false,
    val selectedProtocol: VpnProtocol = VpnProtocol.AUTO,
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val autoConnectOnStart: Boolean = false,
    val autoReconnectOnNetworkChange: Boolean = true,
    val splitTunnelingEnabled: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.BYPASS,
    val splitTunnelApps: Set<String> = emptySet()
)

data class PermanentPermissionsStatus(
    val hasNotificationPermission: Boolean = true,
    val isBatteryUnrestricted: Boolean = false,
    val hasVpnPermission: Boolean = false
) {
    val allGranted: Boolean
        get() = hasNotificationPermission && isBatteryUnrestricted && hasVpnPermission
}

data class AppItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean = false
)

data class ConnectionDiagnostics(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val networkType: String = "Desconhecido",
    val isOnline: Boolean = true,
    val virtualIp: String = "198.18.0.1",
    val exitIp: String = "-",
    val protocolName: String = "Automático",
    val dnsInfo: String = "Cloudflare (1.1.1.1)",
    val activeServerName: String = "Nenhum",
    val activeServerLocation: String = "-",
    val serverEndpoint: String = "-",
    val pingMs: Int? = null,
    val isTestingRoute: Boolean = false,
    val routeTestResult: String? = null
)
