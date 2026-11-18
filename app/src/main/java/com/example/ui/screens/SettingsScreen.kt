package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.ThemeMode
import com.example.ui.BpbUiState
import com.example.ui.components.BpbPanelCard
import com.example.ui.components.BpbSectionHeader
import com.example.ui.theme.MonoCaptionStyle

@Composable
fun SettingsScreen(
    uiState: BpbUiState,
    onThemeChanged: (ThemeMode) -> Unit,
    onOpenPacketSettings: () -> Unit,
    onOpenRoutingRules: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = uiState.settings

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "Engine & Tunnel Settings",
                subtitle = "DPI bypass, packet fragmentation, routing rules & split tunneling",
                modifier = Modifier.widthIn(max = 600.dp)
            )
        }

        item {
            BpbPanelCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("settings_packet_card"),
                onClick = onOpenPacketSettings
            ) {
                SettingsRowItem(
                    icon = Icons.Filled.Grain,
                    title = "Serverless Packet & UDP Noise",
                    subtitle = if (settings.fragmentConfig.enabled) {
                        "Fragment ${settings.fragmentConfig.packets} (${settings.fragmentConfig.length}B) • Noise: ${settings.udpNoisePreset.label}"
                    } else {
                        "Fragment disabled • Noise: ${settings.udpNoisePreset.label}"
                    }
                )
            }
        }

        item {
            BpbPanelCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("settings_routing_card"),
                onClick = onOpenRoutingRules
            ) {
                val activeRules = buildList {
                    if (settings.routingConfig.bypassLan) add("Bypass LAN")
                    if (settings.routingConfig.blockAds) add("Block Ads")
                    if (settings.routingConfig.blockMalware) add("Malware")
                    if (settings.routingConfig.blockQuic) add("No QUIC")
                }.joinToString(" • ").ifEmpty { "Default pass-through" }

                SettingsRowItem(
                    icon = Icons.Filled.AltRoute,
                    title = "Routing & DNS Filter Rules",
                    subtitle = activeRules
                )
            }
        }

        item {
            BpbPanelCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("settings_split_tunnel_card"),
                onClick = onOpenSplitTunnel
            ) {
                SettingsRowItem(
                    icon = Icons.Filled.Apps,
                    title = "Per-App Proxy (Split Tunneling)",
                    subtitle = "${settings.splitTunnelMode.label} (${settings.selectedPackages.size} app(s) selected)"
                )
            }
        }

        item {
            BpbPanelCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("settings_logs_card"),
                onClick = onOpenLogs
            ) {
                SettingsRowItem(
                    icon = Icons.Filled.Terminal,
                    title = "Live Connection Logs",
                    subtitle = "Inspect real-time TUN packets, DoH queries, and scanner output"
                )
            }
        }

        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DarkMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeChanged(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = ThemeMode.entries.size
                            )
                        ) {
                            Text(mode.label, maxLines = 1)
                        }
                    }
                }
            }
        }

        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Column {
                        Text("BPB VPN for Android", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Inspired by BPB VPN Desktop • Android VpnService + DoH + Cloudflare Scanner",
                            style = MonoCaptionStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
