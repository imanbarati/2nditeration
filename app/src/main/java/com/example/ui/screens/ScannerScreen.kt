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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
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
import com.example.core.CloudflareScanner
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.ScannerConfig
import com.example.ui.BpbUiState
import com.example.ui.components.BpbPanelCard
import com.example.ui.components.BpbSectionHeader
import com.example.ui.components.InfoChip
import com.example.ui.components.LatencyPill
import com.example.ui.theme.BpbCoral
import com.example.ui.theme.BpbEmerald
import com.example.ui.theme.MonoBodyStyle
import com.example.ui.theme.MonoCaptionStyle
import kotlin.math.roundToInt

@Composable
fun ScannerScreen(
    uiState: BpbUiState,
    initialTab: Int = 0,
    onUpdateScannerConfig: (ScannerConfig) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onApplyCleanIp: (String) -> Unit,
    onSelectDnsPreset: (String) -> Unit,
    onSaveCustomDoh: (String, String) -> Unit,
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
            listOf("Clean IP Scanner", "Serverless DNS").forEachIndexed { index, label ->
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
                onNotify = onNotify
            )
        } else {
            ServerlessDnsTab(
                uiState = uiState,
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
    onNotify: (String) -> Unit,
) {
    val config = uiState.scannerConfig
    val progress = uiState.scanProgress
    val clipboard = LocalClipboardManager.current
    val ports = listOf(443, 2053, 2083, 2087, 8443)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "Cloudflare Clean IP Scanner",
                subtitle = "Measures live TCP + TLS SNI handshake latency across Anycast CIDRs",
                modifier = Modifier.widthIn(max = 600.dp)
            )
        }

        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "TARGET CIDR RANGE",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CloudflareScanner.defaultCloudflareCidrs.forEach { cidr ->
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
                    label = { Text("Custom CIDR (IPv4)") },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Verify TLS Handshake", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Validates real SSL handshake against SNI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.verifyTls,
                        onCheckedChange = { onUpdateConfig(config.copy(verifyTls = it)) }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("HTTPS Speed Probe", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Fetches /cdn-cgi/trace to estimate throughput",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.runSpeedProbe,
                        onCheckedChange = { onUpdateConfig(config.copy(runSpeedProbe = it)) }
                    )
                }

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
                    Text(
                        text = "Probing ${progress.currentIp} • Found ${progress.foundCount} clean IP(s)",
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
                        Text("Start Clean IP Scan")
                    }
                }
            }
        }

        if (uiState.cleanIps.isNotEmpty()) {
            item {
                Text(
                    text = "FOUND CLEAN IPS (${uiState.cleanIps.size})",
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
                                if (item.tlsSuccess) {
                                    InfoChip(
                                        icon = Icons.Filled.Security,
                                        label = "TLS OK",
                                        tint = BpbEmerald
                                    )
                                }
                                item.downloadKbps?.let { kbps ->
                                    Text(
                                        text = "$kbps kbps",
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
    onSelectPreset: (String) -> Unit,
    onSaveCustomDoh: (String, String) -> Unit,
    onTestAllDns: (String) -> Unit,
) {
    var domainToResolve by remember { mutableStateOf("cloudflare.com") }
    var customDoh by remember(uiState.settings.customDohUrl) {
        mutableStateOf(uiState.settings.customDohUrl)
    }
    var fakeSni by remember(uiState.settings.fakeSniHost) {
        mutableStateOf(uiState.settings.fakeSniHost)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "Serverless DoH & DNS Tester",
                subtitle = "RFC 8484 encrypted DNS-over-HTTPS resolver & live latency benchmark",
                modifier = Modifier.widthIn(max = 600.dp)
            )
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
                Button(
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
                    Text(if (uiState.isTestingDns) "Resolving via DoH…" else "Test All DoH Resolvers")
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

        // Custom DoH + Fake SNI
        item {
            BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                Text(
                    text = "PRIVATE DOH & FAKE SNI",
                    style = MonoCaptionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customDoh,
                    onValueChange = { customDoh = it },
                    label = { Text("Custom DoH Endpoint (Leave empty to use preset)") },
                    placeholder = { Text("https://security.cloudflare-dns.com/dns-query") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )
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
                OutlinedButton(
                    onClick = { onSaveCustomDoh(customDoh, fakeSni) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Custom DoH & Fake SNI")
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
                    onSaveCustomDoh("", fakeSni)
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
