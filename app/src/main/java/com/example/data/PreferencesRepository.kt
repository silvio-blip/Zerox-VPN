package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DnsProvider
import com.example.model.SplitTunnelMode
import com.example.model.VpnProtocol
import com.example.model.VpnSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "atlas_vpn_prefs")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val KILL_SWITCH = booleanPreferencesKey("kill_switch")
        val PROTOCOL = stringPreferencesKey("protocol")
        val DNS_PROVIDER = stringPreferencesKey("dns_provider")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val LAST_SERVER_ID = stringPreferencesKey("last_server_id")
        val AUTO_RECONNECT_NETWORK = booleanPreferencesKey("auto_reconnect_network")
        val SPLIT_TUNNEL_ENABLED = booleanPreferencesKey("split_tunnel_enabled")
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_TUNNEL_APPS = stringSetPreferencesKey("split_tunnel_apps")
        val FAVORITE_SERVERS = stringSetPreferencesKey("favorite_servers")
        val RECENT_SERVERS = stringPreferencesKey("recent_servers")
    }

    val settingsFlow: Flow<VpnSettings> = context.dataStore.data.map { prefs ->
        val protocolName = prefs[Keys.PROTOCOL] ?: VpnProtocol.AUTO.name
        val dnsName = prefs[Keys.DNS_PROVIDER] ?: DnsProvider.CLOUDFLARE.name
        val splitModeName = prefs[Keys.SPLIT_TUNNEL_MODE] ?: SplitTunnelMode.BYPASS.name
        VpnSettings(
            killSwitchEnabled = prefs[Keys.KILL_SWITCH] ?: false,
            selectedProtocol = runCatching { VpnProtocol.valueOf(protocolName) }.getOrDefault(VpnProtocol.AUTO),
            dnsProvider = runCatching { DnsProvider.valueOf(dnsName) }.getOrDefault(DnsProvider.CLOUDFLARE),
            autoConnectOnStart = prefs[Keys.AUTO_CONNECT] ?: false,
            autoReconnectOnNetworkChange = prefs[Keys.AUTO_RECONNECT_NETWORK] ?: true,
            splitTunnelingEnabled = prefs[Keys.SPLIT_TUNNEL_ENABLED] ?: false,
            splitTunnelMode = runCatching { SplitTunnelMode.valueOf(splitModeName) }.getOrDefault(SplitTunnelMode.BYPASS),
            splitTunnelApps = prefs[Keys.SPLIT_TUNNEL_APPS] ?: emptySet()
        )
    }

    val lastServerIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_SERVER_ID]
    }

    val favoriteServersFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.FAVORITE_SERVERS] ?: emptySet()
    }

    val recentServersFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.RECENT_SERVERS] ?: ""
        if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    suspend fun saveSettings(settings: VpnSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.KILL_SWITCH] = settings.killSwitchEnabled
            prefs[Keys.PROTOCOL] = settings.selectedProtocol.name
            prefs[Keys.DNS_PROVIDER] = settings.dnsProvider.name
            prefs[Keys.AUTO_CONNECT] = settings.autoConnectOnStart
            prefs[Keys.AUTO_RECONNECT_NETWORK] = settings.autoReconnectOnNetworkChange
            prefs[Keys.SPLIT_TUNNEL_ENABLED] = settings.splitTunnelingEnabled
            prefs[Keys.SPLIT_TUNNEL_MODE] = settings.splitTunnelMode.name
            prefs[Keys.SPLIT_TUNNEL_APPS] = settings.splitTunnelApps
        }
    }

    suspend fun saveLastServerId(serverId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SERVER_ID] = serverId
        }
    }

    suspend fun toggleFavoriteServer(serverId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_SERVERS]?.toMutableSet() ?: mutableSetOf()
            if (current.contains(serverId)) {
                current.remove(serverId)
            } else {
                current.add(serverId)
            }
            prefs[Keys.FAVORITE_SERVERS] = current
        }
    }

    suspend fun addRecentServer(serverId: String) {
        if (serverId.isBlank()) return
        context.dataStore.edit { prefs ->
            val raw = prefs[Keys.RECENT_SERVERS] ?: ""
            val list = raw.split(",").filter { it.isNotBlank() }.toMutableList()
            list.remove(serverId)
            list.add(0, serverId)
            val trimmed = list.take(6)
            prefs[Keys.RECENT_SERVERS] = trimmed.joinToString(",")
            prefs[Keys.LAST_SERVER_ID] = serverId
        }
    }

    suspend fun updateAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_RECONNECT_NETWORK] = enabled
        }
    }

    suspend fun updateSplitTunnelEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPLIT_TUNNEL_ENABLED] = enabled
        }
    }

    suspend fun updateSplitTunnelMode(mode: SplitTunnelMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPLIT_TUNNEL_MODE] = mode.name
        }
    }

    suspend fun updateSplitTunnelApps(apps: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPLIT_TUNNEL_APPS] = apps
        }
    }

    suspend fun updateKillSwitch(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.KILL_SWITCH] = enabled
        }
    }

    suspend fun updateProtocol(protocol: VpnProtocol) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROTOCOL] = protocol.name
        }
    }

    suspend fun updateDnsProvider(dnsProvider: DnsProvider) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DNS_PROVIDER] = dnsProvider.name
        }
    }

    suspend fun updateAutoConnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_CONNECT] = enabled
        }
    }
}
