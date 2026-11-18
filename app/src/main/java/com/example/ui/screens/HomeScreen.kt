package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddModerator
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.XrayConfigEntity
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.ConnectionStatus
import com.example.data.model.TunnelMode
import com.example.ui.BpbUiState
import com.example.ui.components.BpbPanelCard
import com.example.ui.components.InfoChip
import com.example.ui.components.LatencyPill
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.formatByteCount
import com.example.ui.components.formatDurationSeconds
import com.example.ui.theme.BpbAmber
import com.example.ui.theme.BpbCoral
import com.example.ui.theme.BpbCyan
import com.example.ui.theme.BpbEmerald
import com.example.ui.theme.MonoBodyStyle
import com.example.ui.theme.MonoCaptionStyle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: BpbUiState,
    onToggleConnection: () -> Unit,
    onModeSelected: (TunnelMode) -> Unit,
    onSelectXrayConfig: (XrayConfigEntity) -> Unit,
    onNavigateToAether: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToScanner: (Boolean) -> Unit,
    onNavigateToLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = uiState.tunnelStats
    val activeXray = uiState.activeXrayConfig
    val aether = uiState.settings.aetherConfig
    val psiphon = uiState.settings.psiphonConfig
    val activeDns = BuiltInDnsPresets.all.find { it.id == uiState.settings.activeDnsPresetId }
        ?: BuiltInDnsPresets.all.first()

    var showProfileSheet by remember { mutableStateOf(false) }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stats.status, stats.startedAtMs) {
        if (stats.status == ConnectionStatus.CONNECTED && stats.startedAtMs > 0L) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }
    val elapsedSeconds = if (stats.status == ConnectionStatus.CONNECTED && stats.startedAtMs > 0L) {
        ((nowMs - stats.startedAtMs) / 1000L).coerceAtLeast(0L)
    } else {
        0L
    }

    val statusColor by animateColorAsState(
        targetValue = when (stats.status) {
            ConnectionStatus.CONNECTED -> BpbEmerald
            ConnectionStatus.VERIFYING, ConnectionStatus.DISCONNECTING -> BpbAmber
            ConnectionStatus.ERROR -> BpbCoral
            ConnectionStatus.IDLE -> BpbCyan
        },
        label = "status_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (stats.status == ConnectionStatus.VERIFYING) 1.18f else 1.09f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (stats.status == ConnectionStatus.VERIFYING) 750 else 1800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Box(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_tunnel_mesh_1795011690454),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.32f)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "BPB • Aether",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Aether WARP + Psiphon + Xray Engine",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = statusColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = stats.status.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor
                                )
                            }
                        }
                    }

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TunnelMode.entries.forEachIndexed { idx, mode ->
                            SegmentedButton(
                                selected = uiState.settings.tunnelMode == mode,
                                onClick = { onModeSelected(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = idx,
                                    count = TunnelMode.entries.size
                                ),
                                modifier = Modifier.testTag("mode_${mode.name.lowercase()}")
                            ) {
                                Text(
                                    text = mode.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(164.dp)
                    ) {
                        if (stats.status == ConnectionStatus.CONNECTED || stats.status == ConnectionStatus.VERIFYING) {
                            Box(
                                modifier = Modifier
                                    .size(152.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(statusColor.copy(alpha = 0.16f))
                            )
                        }

                        Surface(
                            onClick = onToggleConnection,
                            modifier = Modifier
                                .size(132.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("connect_orb_button"),
                            shape = CircleShape,
                            color = if (stats.status == ConnectionStatus.CONNECTED) {
                                statusColor.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            border = BorderStroke(2.5.dp, statusColor),
                            shadowElevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = when (stats.status) {
                                        ConnectionStatus.CONNECTED -> Icons.Filled.VerifiedUser
                                        ConnectionStatus.ERROR -> Icons.Filled.ErrorOutline
                                        else -> Icons.Filled.PowerSettingsNew
                                    },
                                    contentDescription = if (stats.status == ConnectionStatus.CONNECTED) {
                                        "Disconnect VPN"
                                    } else {
                                        "Connect VPN"
                                    },
                                    tint = statusColor,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = when (stats.status) {
                                        ConnectionStatus.CONNECTED -> "TAP TO STOP"
                                        ConnectionStatus.VERIFYING -> "VERIFYING"
                                        ConnectionStatus.DISCONNECTING -> "STOPPING"
                                        else -> "CONNECT"
                                    },
                                    style = MonoCaptionStyle.copy(fontWeight = FontWeight.Bold),
                                    color = statusColor
                                )
                            }
                        }
                    }

                    if (stats.status == ConnectionStatus.CONNECTED) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = formatDurationSeconds(elapsedSeconds),
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = MonoBodyStyle.fontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                LatencyPill(latencyMs = stats.verifiedLatencyMs)
                            }
                            if (stats.localSocksAddress != null) {
                                InfoChip(
                                    icon = Icons.Filled.Lan,
                                    label = "SOCKS5 ${stats.localSocksAddress}",
                                    tint = BpbEmerald
                                )
                            }
                        }
                    } else {
                        Text(
                            text = uiState.settings.tunnelMode.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = !stats.errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BpbCoral.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, BpbCoral.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stats.errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = BpbCoral,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Active Endpoint / Chain Card
        item {
            BpbPanelCard(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("active_endpoint_card"),
                onClick = {
                    when (uiState.settings.tunnelMode) {
                        TunnelMode.AETHER_WARP -> onNavigateToAether()
                        TunnelMode.PROFILE_TUNNEL -> {
                            if (uiState.xrayConfigs.isNotEmpty()) showProfileSheet = true
                            else onNavigateToProfiles()
                        }
                        TunnelMode.SERVERLESS_DOH -> onNavigateToScanner(true)
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when (uiState.settings.tunnelMode) {
                                TunnelMode.AETHER_WARP -> "AETHER WARP + PSIPHON CHAIN"
                                TunnelMode.PROFILE_TUNNEL -> "ACTIVE XRAY ROOM CONFIG"
                                TunnelMode.SERVERLESS_DOH -> "SERVERLESS DOH ENDPOINT"
                            },
                            style = MonoCaptionStyle,
                            color = MaterialTheme.colorScheme.primary
                        )

                        when (uiState.settings.tunnelMode) {
                            TunnelMode.AETHER_WARP -> {
                                val chainSuffix = if (psiphon.enabled) " + Psiphon (${psiphon.transport.label})" else ""
                                Text(
                                    text = "${aether.transportMode.label}$chainSuffix",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${aether.endpointHost}:${aether.endpointPort} • SNI ${aether.masqueSni} • SOCKS :${aether.localSocksPort}",
                                    style = MonoCaptionStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TunnelMode.PROFILE_TUNNEL -> {
                                if (activeXray != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ProtocolBadge(protocol = activeXray.protocolEnum())
                                        Text(
                                            text = activeXray.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${activeXray.serverAddress}:${activeXray.port} • Frag=${activeXray.fragmentPackets} • Noise=${activeXray.udpNoiseEnabled}",
                                        style = MonoCaptionStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    Text("No Xray config saved yet", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Tap to import URI, subscription, or build in Wizard",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TunnelMode.SERVERLESS_DOH -> {
                                val endpoint = uiState.settings.customDohUrl.ifBlank { activeDns.endpoint }
                                Text(text = activeDns.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "$endpoint • Fake SNI: ${uiState.settings.fakeSniHost}",
                                    style = MonoCaptionStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (uiState.settings.tunnelMode == TunnelMode.PROFILE_TUNNEL && activeXray != null) {
                            LatencyPill(latencyMs = activeXray.latencyMs)
                        }
                        Icon(
                            imageVector = if (uiState.settings.tunnelMode == TunnelMode.PROFILE_TUNNEL && uiState.xrayConfigs.isNotEmpty()) {
                                Icons.Filled.SwapHoriz
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = "Configure endpoint",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Live Telemetry 2x2 Grid
        item {
            Column(
                modifier = Modifier.widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryTile(
                        title = "TUN + PROXY",
                        value = "↓ ${formatByteCount(stats.rxBytes)}",
                        subtitle = "↑ ${formatByteCount(stats.txBytes)} (${stats.proxiedConnections} conn)",
                        icon = Icons.Filled.Bolt,
                        accent = BpbCyan,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryTile(
                        title = "DOH QUERIES",
                        value = "${stats.dnsQueriesHandled}",
                        subtitle = "${stats.blockedQueriesCount} blocked",
                        icon = Icons.Filled.Dns,
                        accent = BpbEmerald,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val frag = uiState.settings.fragmentConfig
                    TelemetryTile(
                        title = "DPI FRAGMENT",
                        value = if (frag.enabled) frag.packets else "Off",
                        subtitle = if (frag.enabled) "${frag.length}B / ${frag.interval}ms" else "Direct TLS",
                        icon = Icons.Filled.Shield,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryTile(
                        title = "UDP NOISE",
                        value = uiState.settings.udpNoisePreset.label,
                        subtitle = uiState.settings.udpNoisePreset.packetSpec,
                        icon = Icons.Filled.AddModerator,
                        accent = BpbAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Quick Tools Strip
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "QUICK TOOLS",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        icon = Icons.Filled.Radar,
                        label = "Clean IP",
                        onClick = { onNavigateToScanner(false) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_scan_ip")
                    )
                    QuickActionButton(
                        icon = Icons.Filled.Dns,
                        label = "DNS Test",
                        onClick = { onNavigateToScanner(true) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_dns_test")
                    )
                    QuickActionButton(
                        icon = Icons.Filled.Terminal,
                        label = "Live Logs",
                        onClick = onNavigateToLogs,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_logs")
                    )
                }
            }
        }
    }

    if (showProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Switch Active Xray Configuration", style = MaterialTheme.typography.titleLarge)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(uiState.xrayConfigs, key = { it.id }) { cfg ->
                        val isSelected = cfg.id == activeXray?.id
                        BpbPanelCard(
                            highlighted = isSelected,
                            onClick = {
                                onSelectXrayConfig(cfg)
                                showProfileSheet = false
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ProtocolBadge(protocol = cfg.protocolEnum())
                                        Text(
                                            text = cfg.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${cfg.serverAddress}:${cfg.port}",
                                        style = MonoCaptionStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LatencyPill(latencyMs = cfg.latencyMs)
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    BpbPanelCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MonoCaptionStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = MonoBodyStyle.fontFamily,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MonoCaptionStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
