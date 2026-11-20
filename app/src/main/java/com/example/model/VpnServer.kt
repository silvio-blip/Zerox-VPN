package com.example.model

enum class Continent(val displayName: String) {
    SOUTH_AMERICA("América do Sul"),
    AFRICA("África"),
    EUROPE("Europa"),
    NORTH_AMERICA("América do Norte"),
    ASIA("Ásia"),
    OCEANIA("Oceania")
}

enum class VpnProtocol(val label: String) {
    AUTO("Automático (Mais Rápido)"),
    SOCKS5_TUNNEL("Túnel SOCKS5 (TCP/UDP)"),
    HTTP_TUNNEL("Túnel HTTPS CONNECT")
}

enum class RelayType {
    SOCKS5,
    SOCKS4,
    HTTP_CONNECT
}

data class RelayEndpoint(
    val ip: String,
    val port: Int,
    val type: RelayType = RelayType.SOCKS5,
    val measuredPingMs: Int? = null,
    val supportsUdp: Boolean = false,
    val supportsCustomPorts: Boolean = false,
    val exitIp: String = "",
    val exitCountryCode: String = ""
)

data class VpnServer(
    val id: String,
    val hostName: String,
    val ip: String,
    val port: Int,
    val countryName: String,
    val countryCode: String,
    val city: String,
    val continent: Continent,
    val protocol: VpnProtocol = VpnProtocol.AUTO,
    val pingMs: Int,
    val speedMbps: Double,
    val relayPool: List<RelayEndpoint> = emptyList(),
    val isVerified: Boolean = false,
    val supportsUdp: Boolean = false
)
