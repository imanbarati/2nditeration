package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.FragmentConfig
import com.example.data.model.RoutingConfig
import com.example.data.model.SplitTunnelMode
import com.example.data.model.ThemeMode
import com.example.data.model.TunnelMode
import com.example.data.model.UdpNoisePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.bpbDataStore: DataStore<Preferences> by preferencesDataStore(name = "bpb_vpn_prefs")

data class BpbSettingsSnapshot(
    val activeProfileId: Long? = null,
    val tunnelMode: TunnelMode = TunnelMode.SERVERLESS_DOH,
    val activeDnsPresetId: String = BuiltInDnsPresets.all.first().id,
    val customDohUrl: String = "",
    val fakeSniHost: String = "www.cloudflare.com",
    val fragmentConfig: FragmentConfig = FragmentConfig(),
    val udpNoisePreset: UdpNoisePreset = UdpNoisePreset.AMNEZIA_WG,
    val routingConfig: RoutingConfig = RoutingConfig(),
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.DARK,
)

class BpbPreferencesRepository(private val context: Context) {

    private object Keys {
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val TUNNEL_MODE = stringPreferencesKey("tunnel_mode")
        val DNS_PRESET_ID = stringPreferencesKey("dns_preset_id")
        val CUSTOM_DOH_URL = stringPreferencesKey("custom_doh_url")
        val FAKE_SNI_HOST = stringPreferencesKey("fake_sni_host")

        val FRAGMENT_ENABLED = booleanPreferencesKey("fragment_enabled")
        val FRAGMENT_PACKETS = stringPreferencesKey("fragment_packets")
        val FRAGMENT_LENGTH = stringPreferencesKey("fragment_length")
        val FRAGMENT_INTERVAL = stringPreferencesKey("fragment_interval")
        val UDP_NOISE_PRESET = stringPreferencesKey("udp_noise_preset")

        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val BYPASS_LOCAL_REGION = booleanPreferencesKey("bypass_local_region")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")
        val BLOCK_MALWARE = booleanPreferencesKey("block_malware")
        val BLOCK_QUIC = booleanPreferencesKey("block_quic")
        val CUSTOM_DIRECT_DOMAINS = stringPreferencesKey("custom_direct_domains")
        val CUSTOM_BLOCK_DOMAINS = stringPreferencesKey("custom_block_domains")

        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settingsFlow: Flow<BpbSettingsSnapshot> = context.bpbDataStore.data.map { prefs ->
        BpbSettingsSnapshot(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID]?.takeIf { it > 0L },
            tunnelMode = prefs[Keys.TUNNEL_MODE]
                ?.let { runCatching { TunnelMode.valueOf(it) }.getOrNull() }
                ?: TunnelMode.SERVERLESS_DOH,
            activeDnsPresetId = prefs[Keys.DNS_PRESET_ID] ?: BuiltInDnsPresets.all.first().id,
            customDohUrl = prefs[Keys.CUSTOM_DOH_URL] ?: "",
            fakeSniHost = prefs[Keys.FAKE_SNI_HOST] ?: "www.cloudflare.com",
            fragmentConfig = FragmentConfig(
                enabled = prefs[Keys.FRAGMENT_ENABLED] ?: true,
                packets = prefs[Keys.FRAGMENT_PACKETS] ?: "tlshello",
                length = prefs[Keys.FRAGMENT_LENGTH] ?: "10-20",
                interval = prefs[Keys.FRAGMENT_INTERVAL] ?: "10-20",
            ),
            udpNoisePreset = prefs[Keys.UDP_NOISE_PRESET]
                ?.let { runCatching { UdpNoisePreset.valueOf(it) }.getOrNull() }
                ?: UdpNoisePreset.AMNEZIA_WG,
            routingConfig = RoutingConfig(
                bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
                bypassLocalRegion = prefs[Keys.BYPASS_LOCAL_REGION] ?: true,
                blockAds = prefs[Keys.BLOCK_ADS] ?: true,
                blockMalware = prefs[Keys.BLOCK_MALWARE] ?: true,
                blockQuic = prefs[Keys.BLOCK_QUIC] ?: false,
                customDirectDomains = prefs[Keys.CUSTOM_DIRECT_DOMAINS] ?: "ir,local,lan",
                customBlockDomains = prefs[Keys.CUSTOM_BLOCK_DOMAINS] ?: "",
            ),
            splitTunnelMode = prefs[Keys.SPLIT_TUNNEL_MODE]
                ?.let { runCatching { SplitTunnelMode.valueOf(it) }.getOrNull() }
                ?: SplitTunnelMode.ALL_APPS,
            selectedPackages = prefs[Keys.SELECTED_PACKAGES] ?: emptySet(),
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
        )
    }

    suspend fun getSnapshot(): BpbSettingsSnapshot = settingsFlow.first()

    suspend fun setActiveProfileId(id: Long?) {
        context.bpbDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_PROFILE_ID) else prefs[Keys.ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun setTunnelMode(mode: TunnelMode) {
        context.bpbDataStore.edit { it[Keys.TUNNEL_MODE] = mode.name }
    }

    suspend fun setDnsPreset(presetId: String) {
        context.bpbDataStore.edit { it[Keys.DNS_PRESET_ID] = presetId }
    }

    suspend fun setCustomDoh(url: String, fakeSni: String) {
        context.bpbDataStore.edit {
            it[Keys.CUSTOM_DOH_URL] = url.trim()
            it[Keys.FAKE_SNI_HOST] = fakeSni.trim().ifEmpty { "www.cloudflare.com" }
        }
    }

    suspend fun updateFragmentConfig(config: FragmentConfig) {
        context.bpbDataStore.edit {
            it[Keys.FRAGMENT_ENABLED] = config.enabled
            it[Keys.FRAGMENT_PACKETS] = config.packets
            it[Keys.FRAGMENT_LENGTH] = config.length
            it[Keys.FRAGMENT_INTERVAL] = config.interval
        }
    }

    suspend fun setUdpNoisePreset(preset: UdpNoisePreset) {
        context.bpbDataStore.edit { it[Keys.UDP_NOISE_PRESET] = preset.name }
    }

    suspend fun updateRoutingConfig(config: RoutingConfig) {
        context.bpbDataStore.edit {
            it[Keys.BYPASS_LAN] = config.bypassLan
            it[Keys.BYPASS_LOCAL_REGION] = config.bypassLocalRegion
            it[Keys.BLOCK_ADS] = config.blockAds
            it[Keys.BLOCK_MALWARE] = config.blockMalware
            it[Keys.BLOCK_QUIC] = config.blockQuic
            it[Keys.CUSTOM_DIRECT_DOMAINS] = config.customDirectDomains
            it[Keys.CUSTOM_BLOCK_DOMAINS] = config.customBlockDomains
        }
    }

    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) {
        context.bpbDataStore.edit { it[Keys.SPLIT_TUNNEL_MODE] = mode.name }
    }

    suspend fun togglePackageSelection(packageName: String) {
        context.bpbDataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(packageName)) {
                current.remove(packageName)
            }
            prefs[Keys.SELECTED_PACKAGES] = current
        }
    }

    suspend fun clearSelectedPackages() {
        context.bpbDataStore.edit { it[Keys.SELECTED_PACKAGES] = emptySet() }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.bpbDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
}
