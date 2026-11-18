package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AetherTransportMode
import com.example.data.model.AetherWarpConfig
import com.example.data.model.PsiphonChainConfig
import com.example.data.model.PsiphonChainOrder
import com.example.data.model.PsiphonRegion
import com.example.data.model.PsiphonTransport
import com.example.data.model.WarpEndpointCandidate
import com.example.ui.BpbUiState
import com.example.ui.components.BpbPanelCard
import com.example.ui.components.BpbSectionHeader
import com.example.ui.components.InfoChip
import com.example.ui.components.LatencyPill
import com.example.ui.theme.BpbAmber
import com.example.ui.theme.BpbCyan
import com.example.ui.theme.BpbEmerald
import com.example.ui.theme.MonoBodyStyle
import com.example.ui.theme.MonoCaptionStyle

@Composable
fun AetherWarpScreen(
    uiState: BpbUiState,
    onUpdateAether: (AetherWarpConfig) -> Unit,
    onUpdatePsiphon: (PsiphonChainConfig) -> Unit,
    onDiscoverEndpoints: () -> Unit,
    onApplyCandidate: (WarpEndpointCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aether = uiState.settings.aetherConfig
    val psiphon = uiState.settings.psiphonConfig

    var endpointHost by remember(aether.endpointHost) { mutableStateOf(aether.endpointHost) }
    var endpointPortText by remember(aether.endpointPort) { mutableStateOf(aether.endpointPort.toString()) }
    var masqueSni by remember(aether.masqueSni) { mutableStateOf(aether.masqueSni) }
    var socksPortText by remember(aether.localSocksPort) { mutableStateOf(aether.localSocksPort.toString()) }

    var upstreamHost by remember(psiphon.upstreamHost) { mutableStateOf(psiphon.upstreamHost) }
    var upstreamPortText by remember(psiphon.upstreamPort) { mutableStateOf(psiphon.upstreamPort.toString()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "Aether WARP + Psiphon Chain",
                subtitle = "User-space MASQUE/Gool WARP client with PattNG-style Psiphon chaining",
                modifier = Modifier.widthIn(max = 600.dp)
            )
        }

        // Visual Chain Topology Banner
        item {
            BpbPanelCard(
                modifier = Modifier.widthIn(max = 600.dp),
                highlighted = psiphon.enabled
            ) {
                Text(
                    text = "ACTIVE ROUTE TOPOLOGY",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InfoChip(icon = Icons.Filled.Lan, label = "TUN / :${aether.localSocksPort}", tint = BpbCyan)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    if (psiphon.enabled && psiphon.chainOrder == PsiphonChainOrder.PSIPHON_OVER_WARP) {
                        InfoChip(icon = Icons.Filled.Public, label = "Psiphon ${psiphon.transport.label}", tint = BpbAmber)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        InfoChip(icon = Icons.Filled.Security, label = "Aether ${aether.transportMode.flag}", tint = BpbEmerald)
                    } else {
                        InfoChip(icon = Icons.Filled.Security, label = "Aether ${aether.transportMode.flag}", tint = BpbEmerald)
                        if (psiphon.enabled) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            InfoChip(
                                icon = Icons.Filled.Public,
                                label = "Psiphon (${if (psiphon.region.code.isEmpty()) "Auto" else psiphon.region.code})",
                                tint = BpbAmber
                            )
                        }
                    }
                    if (psiphon.enabled && psiphon.torAfterChain) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        InfoChip(icon = Icons.Filled.Hub, label = "Tor Exit", tint = BpbCyan)
                    }
                }
            }
        }

        // Aether Transport Modes
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "AETHER TRANSPORT MODE",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AetherTransportMode.entries.forEach { mode ->
                        FilterChip(
                            selected = aether.transportMode == mode,
                            onClick = {
                                endpointPortText = mode.defaultPort.toString()
                                onUpdateAether(
                                    aether.copy(
                                        transportMode = mode,
                                        endpointPort = mode.defaultPort
                                    )
                                )
                            },
                            label = { Text("${mode.label} (${mode.flag})") },
                            modifier = Modifier.testTag("aether_mode_${mode.name.lowercase()}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = aether.transportMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = endpointHost,
                        onValueChange = {
                            endpointHost = it
                            onUpdateAether(aether.copy(endpointHost = it))
                        },
                        label = { Text("WARP Gateway IP") },
                        singleLine = true,
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = endpointPortText,
                        onValueChange = {
                            endpointPortText = it
                            it.toIntOrNull()?.let { p -> onUpdateAether(aether.copy(endpointPort = p)) }
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = masqueSni,
                    onValueChange = {
                        masqueSni = it
                        onUpdateAether(aether.copy(masqueSni = it))
                    },
                    label = { Text("MASQUE SNI (--masque-sni)") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))
                AetherSwitchRow(
                    title = "Packet Obfuscation (--noize)",
                    subtitle = "Injects randomized noise frames to evade DPI",
                    checked = aether.noiseEnabled,
                    onCheckedChange = { onUpdateAether(aether.copy(noiseEnabled = it)) }
                )
                AetherSwitchRow(
                    title = "TLS ClientHello Fragmentation",
                    subtitle = "Fragments WARP/MASQUE API handshakes",
                    checked = aether.tlsFragmentEnabled,
                    onCheckedChange = { onUpdateAether(aether.copy(tlsFragmentEnabled = it)) }
                )
                AetherSwitchRow(
                    title = "Auto-Discover Clean Gateway",
                    subtitle = "Scans WARP Anycast blocks if current gateway drops",
                    checked = aether.autoDiscoverEndpoint,
                    onCheckedChange = { onUpdateAether(aether.copy(autoDiscoverEndpoint = it)) }
                )
                AetherSwitchRow(
                    title = "Local SOCKS5 / HTTP Proxy",
                    subtitle = "Exposes 127.0.0.1:${aether.localSocksPort} for external apps",
                    checked = aether.localSocksEnabled,
                    onCheckedChange = { onUpdateAether(aether.copy(localSocksEnabled = it)) }
                )

                if (aether.localSocksEnabled) {
                    OutlinedTextField(
                        value = socksPortText,
                        onValueChange = {
                            socksPortText = it
                            it.toIntOrNull()?.let { p -> onUpdateAether(aether.copy(localSocksPort = p)) }
                        },
                        label = { Text("Local SOCKS5 Port") },
                        singleLine = true,
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Psiphon Chain Configuration
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Psiphon Chain (PattNG Relay)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Chain Aether WARP with Psiphon OSSH / Domain-Fronted Meek",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = psiphon.enabled,
                        onCheckedChange = { onUpdatePsiphon(psiphon.copy(enabled = it)) },
                        modifier = Modifier.testTag("psiphon_chain_switch")
                    )
                }

                if (psiphon.enabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("CHAIN ORDER", style = MonoCaptionStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PsiphonChainOrder.entries.forEachIndexed { idx, order ->
                            SegmentedButton(
                                selected = psiphon.chainOrder == order,
                                onClick = { onUpdatePsiphon(psiphon.copy(chainOrder = order)) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = idx,
                                    count = PsiphonChainOrder.entries.size
                                )
                            ) {
                                Text(order.label, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("PSIPHON TRANSPORT", style = MonoCaptionStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PsiphonTransport.entries.forEach { tr ->
                            FilterChip(
                                selected = psiphon.transport == tr,
                                onClick = { onUpdatePsiphon(psiphon.copy(transport = tr)) },
                                label = { Text(tr.label) }
                            )
                        }
                    }
                    Text(
                        text = psiphon.transport.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("EXIT REGION", style = MonoCaptionStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PsiphonRegion.entries.forEach { region ->
                            FilterChip(
                                selected = psiphon.region == region,
                                onClick = { onUpdatePsiphon(psiphon.copy(region = region)) },
                                label = { Text(region.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = upstreamHost,
                            onValueChange = {
                                upstreamHost = it
                                onUpdatePsiphon(psiphon.copy(upstreamHost = it))
                            },
                            label = { Text("Upstream SOCKS5 Host") },
                            singleLine = true,
                            textStyle = MonoBodyStyle,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = upstreamPortText,
                            onValueChange = {
                                upstreamPortText = it
                                it.toIntOrNull()?.let { p -> onUpdatePsiphon(psiphon.copy(upstreamPort = p)) }
                            },
                            label = { Text("Port") },
                            singleLine = true,
                            textStyle = MonoBodyStyle,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    AetherSwitchRow(
                        title = "Chain Tor Exit (--tor)",
                        subtitle = "Route final tunnel exit through Tor onion relay",
                        checked = psiphon.torAfterChain,
                        onCheckedChange = { onUpdatePsiphon(psiphon.copy(torAfterChain = it)) }
                    )
                }
            }
        }

        // Endpoint Discovery & Data-Plane Validation
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "ENDPOINT DISCOVERY & VALIDATION",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Probes Cloudflare WARP CIDRs and verifies live data-plane trace responses before selecting an endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onDiscoverEndpoints,
                    enabled = !uiState.isDiscoveringWarp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("discover_warp_button")
                ) {
                    if (uiState.isDiscoveringWarp) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Explore, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (uiState.isDiscoveringWarp) "Validating WARP Gateways…" else "Discover & Validate WARP Gateways")
                }
            }
        }

        if (uiState.warpCandidates.isNotEmpty()) {
            items(uiState.warpCandidates, key = { "${it.ip}:${it.port}" }) { candidate ->
                val isSelected = candidate.ip == aether.endpointHost && candidate.port == aether.endpointPort
                BpbPanelCard(
                    modifier = Modifier.widthIn(max = 600.dp),
                    highlighted = isSelected
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${candidate.ip}:${candidate.port}",
                                style = MonoBodyStyle.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = BpbEmerald.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, BpbEmerald.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Filled.Verified, contentDescription = null, tint = BpbEmerald, modifier = Modifier.size(12.dp))
                                        Text(
                                            text = if (candidate.dataPlaneVerified) "Data-Plane OK" else "Edge OK",
                                            style = MonoCaptionStyle,
                                            color = BpbEmerald
                                        )
                                    }
                                }
                                Text(
                                    text = candidate.modeLabel,
                                    style = MonoCaptionStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LatencyPill(latencyMs = candidate.latencyMs)
                            OutlinedButton(
                                onClick = { onApplyCandidate(candidate) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(if (isSelected) "Active" else "Use")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AetherSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
