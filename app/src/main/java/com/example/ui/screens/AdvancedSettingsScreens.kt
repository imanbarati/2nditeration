package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.FragmentConfig
import com.example.data.model.RoutingConfig
import com.example.data.model.SplitTunnelMode
import com.example.data.model.UdpNoisePreset
import com.example.ui.BpbUiState
import com.example.ui.components.BpbPanelCard
import com.example.ui.theme.MonoBodyStyle
import com.example.ui.theme.MonoCaptionStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketObfuscationScreen(
    uiState: BpbUiState,
    onUpdateFragment: (FragmentConfig) -> Unit,
    onSelectUdpNoise: (UdpNoisePreset) -> Unit,
    onBack: () -> Unit,
) {
    val frag = uiState.settings.fragmentConfig
    var lengthText by remember(frag.length) { mutableStateOf(frag.length) }
    var intervalText by remember(frag.interval) { mutableStateOf(frag.interval) }
    val packetModes = listOf("tlshello", "1-1", "1-3", "1-5")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Serverless Packet & Noise") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TLS ClientHello Fragmentation", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Splits initial TLS handshake packets to bypass SNI DPI inspection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = frag.enabled,
                            onCheckedChange = { onUpdateFragment(frag.copy(enabled = it)) }
                        )
                    }

                    if (frag.enabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Fragment Packets Mode", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            packetModes.forEach { mode ->
                                FilterChip(
                                    selected = frag.packets == mode,
                                    onClick = { onUpdateFragment(frag.copy(packets = mode)) },
                                    label = { Text(mode, style = MonoCaptionStyle) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = lengthText,
                                onValueChange = {
                                    lengthText = it
                                    onUpdateFragment(frag.copy(length = it))
                                },
                                label = { Text("Length (bytes)") },
                                singleLine = true,
                                textStyle = MonoBodyStyle,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = {
                                    intervalText = it
                                    onUpdateFragment(frag.copy(interval = it))
                                },
                                label = { Text("Interval (ms)") },
                                singleLine = true,
                                textStyle = MonoBodyStyle,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "UDP OBFUSCATION PRESETS",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                )
            }

            items(UdpNoisePreset.entries) { preset ->
                val selected = uiState.settings.udpNoisePreset == preset
                BpbPanelCard(
                    modifier = Modifier.widthIn(max = 600.dp),
                    highlighted = selected,
                    onClick = { onSelectUdpNoise(preset) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Packet: ${preset.packetSpec} • Delay: ${preset.delayRangeMs} ms",
                                style = MonoCaptionStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onSelectUdpNoise(preset) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingRulesScreen(
    uiState: BpbUiState,
    onUpdateRouting: (RoutingConfig) -> Unit,
    onBack: () -> Unit,
) {
    val routing = uiState.settings.routingConfig
    var directDomains by remember(routing.customDirectDomains) {
        mutableStateOf(routing.customDirectDomains)
    }
    var blockDomains by remember(routing.customBlockDomains) {
        mutableStateOf(routing.customBlockDomains)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routing & Filter Rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                    RoutingToggleRow(
                        title = "Bypass LAN & Private Subnets",
                        subtitle = "Keep 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 direct",
                        checked = routing.bypassLan,
                        onCheckedChange = { onUpdateRouting(routing.copy(bypassLan = it)) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RoutingToggleRow(
                        title = "Bypass Local Region Domains",
                        subtitle = "Export direct regional rules in Xray routing config",
                        checked = routing.bypassLocalRegion,
                        onCheckedChange = { onUpdateRouting(routing.copy(bypassLocalRegion = it)) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RoutingToggleRow(
                        title = "Block Ads & Trackers (DNS Filter)",
                        subtitle = "Enforced live inside the DoH TUN resolver",
                        checked = routing.blockAds,
                        onCheckedChange = { onUpdateRouting(routing.copy(blockAds = it)) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RoutingToggleRow(
                        title = "Block Malware & Phishing",
                        subtitle = "Drops known malicious domains with NXDOMAIN",
                        checked = routing.blockMalware,
                        onCheckedChange = { onUpdateRouting(routing.copy(blockMalware = it)) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RoutingToggleRow(
                        title = "Block Unwanted QUIC (UDP:443)",
                        subtitle = "Forces browsers to fall back to TCP/TLS fragmentation",
                        checked = routing.blockQuic,
                        onCheckedChange = { onUpdateRouting(routing.copy(blockQuic = it)) }
                    )
                }
            }

            item {
                BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                    Text("CUSTOM DOMAIN LISTS", style = MonoCaptionStyle)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = directDomains,
                        onValueChange = {
                            directDomains = it
                            onUpdateRouting(routing.copy(customDirectDomains = it))
                        },
                        label = { Text("Direct / Bypass Domains (comma-separated)") },
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = blockDomains,
                        onValueChange = {
                            blockDomains = it
                            onUpdateRouting(routing.copy(customBlockDomains = it))
                        },
                        label = { Text("Custom Blocked Domains (comma-separated)") },
                        placeholder = { Text("ads.example.com, tracker.net") },
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    uiState: BpbUiState,
    onLoadApps: () -> Unit,
    onSetMode: (SplitTunnelMode) -> Unit,
    onToggleApp: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onLoadApps()
    }

    val filteredApps = remember(uiState.installedApps, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) uiState.installedApps
        else uiState.installedApps.filter {
            it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-App Proxy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.settings.selectedPackages.isNotEmpty()) {
                        IconButton(onClick = onClearAll) {
                            Icon(Icons.Filled.ClearAll, contentDescription = "Clear selection")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Row(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SplitTunnelMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.settings.splitTunnelMode == mode,
                            onClick = { onSetMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }

            item {
                BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                    Text(
                        text = uiState.settings.splitTunnelMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.settings.splitTunnelMode != SplitTunnelMode.ALL_APPS) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            label = { Text("Filter installed apps") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (uiState.settings.splitTunnelMode != SplitTunnelMode.ALL_APPS) {
                if (uiState.isLoadingApps) {
                    item {
                        CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                    }
                } else {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in uiState.settings.selectedPackages
                        BpbPanelCard(
                            modifier = Modifier.widthIn(max = 600.dp),
                            highlighted = checked,
                            onClick = { onToggleApp(app.packageName) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MonoCaptionStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggleApp(app.packageName) }
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = { onSetMode(SplitTunnelMode.EXCLUDE_SELECTED) },
                        modifier = Modifier.widthIn(max = 600.dp)
                    ) {
                        Text("Switch to Bypass Selected to choose apps")
                    }
                }
            }
        }
    }
}
