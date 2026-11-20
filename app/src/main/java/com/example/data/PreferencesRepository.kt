package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DnsProvider
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
    }

    val settingsFlow: Flow<VpnSettings> = context.dataStore.data.map { prefs ->
        val protocolName = prefs[Keys.PROTOCOL] ?: VpnProtocol.AUTO.name
        val dnsName = prefs[Keys.DNS_PROVIDER] ?: DnsProvider.CLOUDFLARE.name
        VpnSettings(
            killSwitchEnabled = prefs[Keys.KILL_SWITCH] ?: false,
            selectedProtocol = runCatching { VpnProtocol.valueOf(protocolName) }.getOrDefault(VpnProtocol.AUTO),
            dnsProvider = runCatching { DnsProvider.valueOf(dnsName) }.getOrDefault(DnsProvider.CLOUDFLARE),
            autoConnectOnStart = prefs[Keys.AUTO_CONNECT] ?: false
        )
    }

    val lastServerIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_SERVER_ID]
    }

    suspend fun saveSettings(settings: VpnSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.KILL_SWITCH] = settings.killSwitchEnabled
            prefs[Keys.PROTOCOL] = settings.selectedProtocol.name
            prefs[Keys.DNS_PROVIDER] = settings.dnsProvider.name
            prefs[Keys.AUTO_CONNECT] = settings.autoConnectOnStart
        }
    }

    suspend fun saveLastServerId(serverId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SERVER_ID] = serverId
        }
    }
}
