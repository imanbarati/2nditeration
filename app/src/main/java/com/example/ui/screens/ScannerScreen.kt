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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.ConnectionStatus
import com.example.data.model.DohTunnelMetrics
import com.example.data.model.ScannerConfig
import com.example.scanner.CloudflareCleanIpScanner
import com.example.ui.BpbUiState
import com.example.ui.components.BpbPanelCard
import com.example.ui.components.BpbSectionHeader
import com.example.ui.components.InfoChip
import com.example.ui.components.LatencyPill
import com.example.ui.theme.BpbAmber
import com.example.ui.theme.BpbCoral
import com.example.ui.theme.BpbCyan
import com.example.ui.theme.BpbEmerald
import com.example.ui.theme.MonoBodyStyle
import com.example.ui.theme.MonoCaptionStyle
import kotlin.math.roundToInt

@Composable
fun ScannerScreen(
    uiState: BpbUiState,
    dohMetrics: DohTunnelMetrics,
    initialTab: Int = 0,
    onUpdateScannerConfig: (ScannerConfig) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onApplyCleanIp: (String) -> Unit,
    onApplyCleanIpAsDohBootstrap: (String) -> Unit,
    onToggleServerlessDohTunnel: () -> Unit,
    onSelectDnsPreset: (String) -> Unit,
    onSaveCustomDoh: (String, String, String?) -> Unit,
    onTestAllDns: (String) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            listOf("Clean IP Scanner", "Serverless DoH").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    modifier = Modifier.testTag("scanner_tab_$index")
                ) {
                    Text(label)
                }
            }
        }

        if (selectedTab == 0) {
            CleanIpScannerTab(
                uiState = uiState,
                onUpdateConfig = onUpdateScannerConfig,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onApplyCleanIp = onApplyCleanIp,
                onApplyCleanIpAsDohBootstrap = onApplyCleanIpAsDohBootstrap,
                onNotify = onNotify
            )
        } else {
            ServerlessDnsTab(
                uiState = uiState,
                dohMetrics = dohMetrics,
                onToggleDohTunnel = onToggleServerlessDohTunnel,
                onSelectPreset = onSelectDnsPreset,
                onSaveCustomDoh = onSaveCustomDoh,
                onTestAllDns = onTestAllDns
            )
        }
    }
}

@Composable
private fun CleanIpScannerTab(
    uiState: BpbUiState,
    onUpdateConfig: (ScannerConfig) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onApplyCleanIp: (String) -> Unit,
    onApplyCleanIpAsDohBootstrap: (String) -> Unit,
    onNotify: (String) -> Unit,
) {
    val config = uiState.scannerConfig
    val progress = uiState.scanProgress
    val clipboard = LocalClipboardManager.current
    val ports = listOf(443, 2053, 2083, 2087, 2096, 8443)
    val optimal = uiState.cleanIps.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "Cloudflare Clean IP Scanner",
                subtitle = "Tests Anycast ranges with TLS 1.3 + /cdn-cgi/trace Colo scoring",
                modifier = Modifier.widthIn(max = 600.dp)
            )
        }

        // Optimal Endpoint Highlight Card
        if (optimal != null) {
            item {
                BpbPanelCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .testTag("optimal_clean_ip_card"),
                    highlighted = true
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = BpbEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "OPTIMAL SERVER ENDPOINT",
                                style = MonoCaptionStyle.copy(fontWeight = FontWeight.Bold),
                                color = BpbEmerald
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BpbEmerald.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, BpbEmerald.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "SCORE ${optimal.score}/100",
                                style = MonoCaptionStyle.copy(fontWeight = FontWeight.Bold),
                                color = BpbEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${optimal.ip}:${optimal.port}",
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = MonoBodyStyle.fontFamily),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (optimal.colo.isNotBlank()) {
                                    InfoChip(icon = Icons.Filled.Public, label = "COLO ${optimal.colo}", tint = BpbCyan)
                                }
                                if (optimal.tlsProtocol.isNotBlank()) {
                                    InfoChip(icon = Icons.Filled.Security, label = optimal.tlsProtocol, tint = BpbEmerald)
                                }
                            }
                        }
                        LatencyPill(latencyMs = optimal.latencyMs)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onApplyCleanIp(optimal.ip) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("apply_optimal_ip_button")
                        ) {
                            Text("Apply to VPN Config", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { onApplyCleanIpAsDohBootstrap(optimal.ip) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use for DoH", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // Scanner Configuration Card
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "TARGET CLOUDFLARE CIDRS",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = config.cidrRange.equals(CloudflareCleanIpScanner.ALL_CLOUDFLARE_TOKEN, true),
                        onClick = { onUpdateConfig(config.copy(cidrRange = CloudflareCleanIpScanner.ALL_CLOUDFLARE_TOKEN)) },
                        label = { Text("ALL RANGES", style = MonoCaptionStyle) }
                    )
                    CloudflareCleanIpScanner.officialCloudflareCidrs.forEach { cidr ->
                        FilterChip(
                            selected = config.cidrRange == cidr,
                            onClick = { onUpdateConfig(config.copy(cidrRange = cidr)) },
                            label = { Text(cidr, style = MonoCaptionStyle) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.cidrRange,
                    onValueChange = { onUpdateConfig(config.copy(cidrRange = it)) },
                    label = { Text("CIDR Range (ALL or comma-separated IPv4 CIDRs)") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "PORT & TLS SNI",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ports.forEach { p ->
                        FilterChip(
                            selected = config.port == p,
                            onClick = { onUpdateConfig(config.copy(port = p)) },
                            label = { Text(p.toString(), style = MonoCaptionStyle) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = config.sni,
                    onValueChange = { onUpdateConfig(config.copy(sni = it)) },
                    label = { Text("TLS SNI Hostname") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))
                ScannerSwitchRow(
                    title = "Verify TLS Handshake",
                    subtitle = "Validates real SSL/TLS 1.3 handshake against SNI",
                    checked = config.verifyTls,
                    onCheckedChange = { onUpdateConfig(config.copy(verifyTls = it)) }
                )
                ScannerSwitchRow(
                    title = "Verify Cloudflare Edge Colo",
                    subtitle = "Queries /cdn-cgi/trace to confirm datacenter & eliminate hijack",
                    checked = config.verifyCloudflareTrace,
                    onCheckedChange = { onUpdateConfig(config.copy(verifyCloudflareTrace = it)) }
                )
                ScannerSwitchRow(
                    title = "Throughput Speed Probe",
                    subtitle = "Estimates live download kbps on clean candidates",
                    checked = config.runSpeedProbe,
                    onCheckedChange = { onUpdateConfig(config.copy(runSpeedProbe = it)) }
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Sample IPs: ${config.sampleCount}   •   Max Latency: ${config.maxLatencyMs} ms",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.sampleCount.toFloat(),
                    onValueChange = { onUpdateConfig(config.copy(sampleCount = it.roundToInt())) },
                    valueRange = 15f..150f
                )
                Slider(
                    value = config.maxLatencyMs.toFloat(),
                    onValueChange = { onUpdateConfig(config.copy(maxLatencyMs = it.roundToInt())) },
                    valueRange = 150f..1500f
                )

                Spacer(modifier = Modifier.height(8.dp))
                if (progress.isScanning) {
                    Button(
                        onClick = onStopScan,
                        colors = ButtonDefaults.buttonColors(containerColor = BpbCoral),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stop_scan_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Stop Scan (${progress.testedCount}/${progress.totalCount})")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val fraction = if (progress.totalCount > 0) {
                        progress.testedCount.toFloat() / progress.totalCount.toFloat()
                    } else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val bestSuffix = if (progress.bestIpSoFar.isNotBlank()) {
                        " • Best: ${progress.bestIpSoFar} (${progress.bestLatencySoFarMs ?: 0}ms)"
                    } else ""
                    Text(
                        text = "Probing ${progress.currentIp} • Found ${progress.foundCount}$bestSuffix",
                        style = MonoCaptionStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Button(
                        onClick = onStartScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("start_scan_button")
                    ) {
                        Icon(Icons.Filled.Radar, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Scan Optimal Cloudflare IPs")
                    }
                }
            }
        }

        if (uiState.cleanIps.isNotEmpty()) {
            item {
                Text(
                    text = "RANKED CLEAN IPS (${uiState.cleanIps.size})",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                )
            }

            items(uiState.cleanIps, key = { "${it.ip}:${it.port}:${it.id}" }) { item ->
                BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.ip}:${item.port}",
                                style = MonoBodyStyle.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.colo.isNotBlank()) {
                                    InfoChip(icon = Icons.Filled.Public, label = item.colo, tint = BpbCyan)
                                }
                                if (item.tlsSuccess) {
                                    InfoChip(
                                        icon = Icons.Filled.Security,
                                        label = item.tlsProtocol.ifBlank { "TLS OK" },
                                        tint = BpbEmerald
                                    )
                                }
                                if (item.score > 0) {
                                    Text(
                                        text = "${item.score}pts",
                                        style = MonoCaptionStyle,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                item.downloadKbps?.let { kbps ->
                                    Text(
                                        text = "${kbps}kbps",
                                        style = MonoCaptionStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LatencyPill(latencyMs = item.latencyMs)
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(item.ip))
                                    onNotify("Copied ${item.ip}")
                                }
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy IP")
                            }
                            OutlinedButton(
                                onClick = { onApplyCleanIp(item.ip) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Use", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerlessDnsTab(
    uiState: BpbUiState,
    dohMetrics: DohTunnelMetrics,
    onToggleDohTunnel: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSaveCustomDoh: (String, String, String?) -> Unit,
    onTestAllDns: (String) -> Unit,
) {
    var domainToResolve by remember { mutableStateOf("cloudflare.com") }
    var customDoh by remember(uiState.settings.customDohUrl) {
        mutableStateOf(uiState.settings.customDohUrl)
    }
    var bootstrapIp by remember(uiState.settings.dohBootstrapIp) {
        mutableStateOf(uiState.settings.dohBootstrapIp)
    }
    var fakeSni by remember(uiState.settings.fakeSniHost) {
        mutableStateOf(uiState.settings.fakeSniHost)
    }
    val bestScannedIp = uiState.cleanIps.firstOrNull()?.ip
    val isTunnelRunning = uiState.tunnelStats.status == ConnectionStatus.CONNECTED || dohMetrics.isRunning

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "Serverless DoH DNS Tunnel",
                subtitle = "RFC 8484 encrypted DNS with Clean IP bootstrap, TTL cache & auto-failover",
                modifier = Modifier.widthIn(max = 600.dp)
            )
        }

        // Dedicated Serverless DoH Tunnel Service Control & Live Telemetry Card
        item {
            BpbPanelCard(
                modifier = Modifier.widthIn(max = 600.dp),
                highlighted = isTunnelRunning
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isTunnelRunning) "SERVERLESS DOH TUNNEL ACTIVE" else "SERVERLESS DOH TUNNEL IDLE",
                            style = MonoCaptionStyle.copy(fontWeight = FontWeight.Bold),
                            color = if (isTunnelRunning) BpbEmerald else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (isTunnelRunning) {
                                "Upstream: ${dohMetrics.activeUpstream.ifBlank { "Active" }} • Local UDP :${dohMetrics.localUdpPort ?: 5353}"
                            } else {
                                "Starts ServerlessDohVpnService to tunnel all DNS over HTTPS"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = onToggleDohTunnel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTunnelRunning) BpbCoral else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("toggle_serverless_doh_button")
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(if (isTunnelRunning) "Stop" else "Start DoH")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoChip(icon = Icons.Filled.Dns, label = "${dohMetrics.totalQueries} queries", tint = BpbCyan)
                    InfoChip(icon = Icons.Filled.Bolt, label = "${dohMetrics.cacheHits} cached", tint = BpbEmerald)
                    InfoChip(icon = Icons.Filled.Security, label = "${dohMetrics.blockedQueries} blocked", tint = BpbAmber)
                    InfoChip(icon = Icons.Filled.Public, label = "Avg ${dohMetrics.avgLatencyMs}ms", tint = BpbCyan)
                    if (dohMetrics.failoverCount > 0) {
                        InfoChip(icon = Icons.Filled.Radar, label = "${dohMetrics.failoverCount} failovers", tint = BpbCoral)
                    }
                }
            }
        }

        // Live DNS Query Inspector Stream
        if (dohMetrics.recentQueries.isNotEmpty()) {
            item {
                BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                    Text(
                        text = "LIVE TUNNELED DNS QUERIES",
                        style = MonoCaptionStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    dohMetrics.recentQueries.take(8).forEach { q ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = q.domain,
                                    style = MonoBodyStyle.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when {
                                        q.blocked -> "${q.timestamp} • BLOCKED (NXDOMAIN)"
                                        q.fromCache -> "${q.timestamp} • CACHE → ${q.resolvedIps.firstOrNull() ?: "OK"}"
                                        else -> "${q.timestamp} • ${q.upstreamLabel} → ${q.resolvedIps.firstOrNull() ?: "OK"}"
                                    },
                                    style = MonoCaptionStyle,
                                    color = if (q.blocked) BpbCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            LatencyPill(latencyMs = q.latencyMs)
                        }
                    }
                }
            }
        }

        // Private DoH, Bootstrap Clean IP & Fake SNI
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "CLEAN IP BOOTSTRAP & PRIVATE DOH",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customDoh,
                    onValueChange = { customDoh = it },
                    label = { Text("Custom DoH Endpoint (Optional)") },
                    placeholder = { Text("https://security.cloudflare-dns.com/dns-query") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = bootstrapIp,
                    onValueChange = { bootstrapIp = it },
                    label = { Text("DoH Bootstrap Clean IP (Bypasses ISP DNS)") },
                    placeholder = { Text("e.g. 104.16.x.x or 1.1.1.1") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!bestScannedIp.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            bootstrapIp = bestScannedIp
                            onSaveCustomDoh(customDoh, fakeSni, bestScannedIp)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Fill Optimal Clean IP ($bestScannedIp)")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fakeSni,
                    onValueChange = { fakeSni = it },
                    label = { Text("Fake SNI Host") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onSaveCustomDoh(customDoh, fakeSni, bootstrapIp) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Serverless DoH Config")
                }
            }
        }

        // Live Benchmark Box
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "LIVE DOH BENCHMARK",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = domainToResolve,
                    onValueChange = { domainToResolve = it },
                    label = { Text("Domain to Resolve (A/AAAA)") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onTestAllDns(domainToResolve) },
                    enabled = !uiState.isTestingDns,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_all_dns_button")
                ) {
                    if (uiState.isTestingDns) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (uiState.isTestingDns) "Resolving via DoH…" else "Benchmark All DoH Resolvers")
                }

                if (uiState.dnsTestResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    uiState.dnsTestResults.forEach { res ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(res.preset.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = res.error ?: res.resolvedIps.joinToString(", "),
                                    style = MonoCaptionStyle,
                                    color = if (res.error != null) BpbCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            LatencyPill(latencyMs = res.latencyMs)
                        }
                    }
                }
            }
        }

        // Built-in Presets
        items(BuiltInDnsPresets.all, key = { it.id }) { preset ->
            val selected = preset.id == uiState.settings.activeDnsPresetId && uiState.settings.customDohUrl.isBlank()
            BpbPanelCard(
                modifier = Modifier.widthIn(max = 600.dp),
                highlighted = selected,
                onClick = {
                    customDoh = ""
                    onSaveCustomDoh("", fakeSni, null)
                    onSelectPreset(preset.id)
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
                            Icon(
                                imageVector = Icons.Filled.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = preset.endpoint,
                            style = MonoCaptionStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected DNS",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerSwitchRow(
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
