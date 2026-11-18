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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.ConfigParser
import com.example.data.local.SubscriptionEntity
import com.example.data.local.VpnProfileEntity
import com.example.data.model.ProtocolType
import com.example.ui.BpbUiState
import com.example.ui.components.BpbPanelCard
import com.example.ui.components.BpbSectionHeader
import com.example.ui.components.LatencyPill
import com.example.ui.components.ProtocolBadge
import com.example.ui.theme.MonoBodyStyle
import com.example.ui.theme.MonoCaptionStyle
import java.util.UUID

@Composable
fun ProfilesScreen(
    uiState: BpbUiState,
    onSelectProfile: (VpnProfileEntity) -> Unit,
    onTogglePin: (VpnProfileEntity) -> Unit,
    onDeleteProfile: (VpnProfileEntity) -> Unit,
    onPingAll: () -> Unit,
    onImportUriOrPayload: (String) -> Unit,
    onImportSubscription: (String, String) -> Unit,
    onRefreshSubscription: (SubscriptionEntity) -> Unit,
    onCreateWizardProfile: (
        name: String,
        protocol: ProtocolType,
        workerDomain: String,
        cleanIpOrHost: String,
        port: Int,
        uuidOrSecret: String,
        transport: String,
        path: String,
        fingerprint: String,
    ) -> Unit,
    buildXrayJson: (VpnProfileEntity) -> String,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var showWizardDialog by remember { mutableStateOf(false) }
    var showImportSubDialog by remember { mutableStateOf(false) }
    var showPasteUriDialog by remember { mutableStateOf(false) }
    var jsonPreviewProfile by remember { mutableStateOf<VpnProfileEntity?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "BPB Profiles & Configs",
                subtitle = "VLESS, Trojan, Cloudflare WARP, MASQUE & Subscriptions",
                modifier = Modifier.widthIn(max = 600.dp),
                trailing = {
                    if (uiState.profiles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onPingAll,
                            enabled = !uiState.isPingingProfiles,
                            modifier = Modifier.testTag("ping_all_profiles_button")
                        ) {
                            if (uiState.isPingingProfiles) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Ping All")
                        }
                    }
                }
            )
        }

        // Action Buttons Row
        item {
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showWizardDialog = true },
                    modifier = Modifier.testTag("open_bpb_wizard_button")
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("BPB Wizard")
                }

                OutlinedButton(
                    onClick = { showImportSubDialog = true },
                    modifier = Modifier.testTag("import_sub_button")
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Subscription URL")
                }

                OutlinedButton(
                    onClick = { showPasteUriDialog = true },
                    modifier = Modifier.testTag("paste_uri_button")
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Paste URI / JSON")
                }
            }
        }

        // Subscriptions Section
        if (uiState.subscriptions.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SUBSCRIPTIONS",
                        style = MonoCaptionStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    uiState.subscriptions.forEach { sub ->
                        BpbPanelCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sub.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${sub.profileCount} profiles • ${sub.url}",
                                        style = MonoCaptionStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRefreshSubscription(sub) },
                                    enabled = !uiState.isImporting
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh subscription")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Empty State or Profiles List
        if (uiState.profiles.isEmpty()) {
            item {
                BpbPanelCard(modifier = Modifier.widthIn(max = 600.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No BPB Profiles Added Yet",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Use the BPB Wizard to configure your Cloudflare Worker / WARP endpoint, import a Base64 subscription link, or paste a VLESS / Trojan / Xray JSON config.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { showWizardDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Launch BPB Wizard")
                        }
                    }
                }
            }
        } else {
            items(uiState.profiles, key = { it.id }) { profile ->
                val isActive = profile.id == uiState.activeProfile?.id
                BpbPanelCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .testTag("profile_card_${profile.id}"),
                    highlighted = isActive,
                    onClick = { onSelectProfile(profile) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            ProtocolBadge(protocol = profile.protocolEnum())
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LatencyPill(latencyMs = profile.latencyMs)
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Active profile",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${profile.address}:${profile.port} • ${profile.transport.uppercase()}/${profile.security.uppercase()} • fp=${profile.fingerprint}",
                        style = MonoCaptionStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (profile.sni.isNotBlank()) {
                        Text(
                            text = "SNI: ${profile.sni}  Path: ${profile.path}",
                            style = MonoCaptionStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onTogglePin(profile) }) {
                            Icon(
                                imageVector = if (profile.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = "Pin profile",
                                tint = if (profile.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { jsonPreviewProfile = profile }) {
                            Icon(
                                imageVector = Icons.Filled.Code,
                                contentDescription = "Inspect Xray JSON"
                            )
                        }
                        IconButton(
                            onClick = {
                                val uri = ConfigParser.toShareUri(profile)
                                clipboard.setText(AnnotatedString(uri))
                                onNotify("Copied share URI to clipboard")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy share URI"
                            )
                        }
                        IconButton(onClick = { onDeleteProfile(profile) }) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete profile",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPasteUriDialog) {
        var rawInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteUriDialog = false },
            title = { Text("Paste Config URI or JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Supports vless://, trojan://, ss://, socks://, wg://, masque://, Base64 lists, or Xray JSON.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = rawInput,
                        onValueChange = { rawInput = it },
                        label = { Text("Config payload") },
                        minLines = 4,
                        maxLines = 8,
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            clipboard.getText()?.text?.let { rawInput = it }
                        }
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Paste from Clipboard")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportUriOrPayload(rawInput)
                        showPasteUriDialog = false
                    },
                    enabled = rawInput.isNotBlank() && !uiState.isImporting
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteUriDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showImportSubDialog) {
        var subName by remember { mutableStateOf("BPB Panel Sub") }
        var subUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportSubDialog = false },
            title = { Text("Import Subscription URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = subName,
                        onValueChange = { subName = it },
                        label = { Text("Subscription Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it },
                        label = { Text("https://your-worker.workers.dev/sub/...") },
                        singleLine = true,
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportSubscription(subName, subUrl)
                        showImportSubDialog = false
                    },
                    enabled = subUrl.isNotBlank() && !uiState.isImporting
                ) {
                    Text("Fetch & Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportSubDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showWizardDialog) {
        BpbWizardDialog(
            onDismiss = { showWizardDialog = false },
            onSave = { name, proto, domain, cleanIp, port, uuid, transport, path, fp ->
                onCreateWizardProfile(name, proto, domain, cleanIp, port, uuid, transport, path, fp)
                showWizardDialog = false
            }
        )
    }

    jsonPreviewProfile?.let { profile ->
        val xrayJson = remember(profile, uiState.settings) { buildXrayJson(profile) }
        AlertDialog(
            onDismissRequest = { jsonPreviewProfile = null },
            title = { Text("Xray-core JSON • ${profile.name}") },
            text = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = xrayJson,
                        style = MonoCaptionStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(xrayJson))
                        onNotify("Copied Xray JSON config")
                        jsonPreviewProfile = null
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Copy JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { jsonPreviewProfile = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun BpbWizardDialog(
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        protocol: ProtocolType,
        workerDomain: String,
        cleanIpOrHost: String,
        port: Int,
        uuidOrSecret: String,
        transport: String,
        path: String,
        fingerprint: String,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("BPB Worker Profile") }
    var protocol by remember { mutableStateOf(ProtocolType.VLESS) }
    var workerDomain by remember { mutableStateOf("") }
    var cleanIp by remember { mutableStateOf("") }
    var port by remember { mutableIntStateOf(443) }
    var uuidOrSecret by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var transport by remember { mutableStateOf("ws") }
    var path by remember { mutableStateOf("/?ed=2560") }
    var fingerprint by remember { mutableStateOf("chrome") }

    val ports = listOf(443, 2053, 2083, 2087, 2096, 8443)
    val protocols = listOf(ProtocolType.VLESS, ProtocolType.TROJAN, ProtocolType.WARP, ProtocolType.MASQUE)
    val transports = listOf("ws", "xhttp", "grpc", "tcp")
    val fingerprints = listOf("chrome", "firefox", "safari", "ios", "randomized")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BPB Panel Wizard") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Protocol",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    protocols.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { protocol = p },
                            label = { Text(p.label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = workerDomain,
                    onValueChange = { workerDomain = it },
                    label = { Text("Cloudflare Worker / Pages Domain (SNI)") },
                    placeholder = { Text("panel.example.workers.dev") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cleanIp,
                    onValueChange = { cleanIp = it },
                    label = { Text("Clean IP / Endpoint (Optional)") },
                    placeholder = { Text("104.16.x.x or leave blank for domain") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "TLS Port",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ports.forEach { p ->
                        FilterChip(
                            selected = port == p,
                            onClick = { port = p },
                            label = { Text(p.toString()) }
                        )
                    }
                }

                OutlinedTextField(
                    value = uuidOrSecret,
                    onValueChange = { uuidOrSecret = it },
                    label = { Text(if (protocol == ProtocolType.TROJAN) "Trojan Password" else "UUID / Key") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )

                if (protocol == ProtocolType.VLESS || protocol == ProtocolType.TROJAN) {
                    Text(
                        text = "Transport",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        transports.forEach { t ->
                            FilterChip(
                                selected = transport == t,
                                onClick = { transport = t },
                                label = { Text(t.uppercase()) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("WebSocket / xHTTP Path") },
                        singleLine = true,
                        textStyle = MonoBodyStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = "uTLS Fingerprint",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    fingerprints.forEach { fp ->
                        FilterChip(
                            selected = fingerprint == fp,
                            onClick = { fingerprint = fp },
                            label = { Text(fp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(name, protocol, workerDomain, cleanIp, port, uuidOrSecret, transport, path, fingerprint)
                },
                enabled = workerDomain.isNotBlank() || cleanIp.isNotBlank()
            ) {
                Text("Create Profile")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
