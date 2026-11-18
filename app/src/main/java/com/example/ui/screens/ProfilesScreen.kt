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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Switch
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
import com.example.data.local.XrayConfigEntity
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
    onSelectConfig: (XrayConfigEntity) -> Unit,
    onTogglePin: (XrayConfigEntity) -> Unit,
    onDeleteConfig: (XrayConfigEntity) -> Unit,
    onUpdateObfuscation: (XrayConfigEntity) -> Unit,
    onPingAll: () -> Unit,
    onImportUriOrPayload: (String) -> Unit,
    onImportSubscription: (String, String) -> Unit,
    onRefreshSubscription: (SubscriptionEntity) -> Unit,
    onCreateWizardConfig: (
        name: String,
        protocol: ProtocolType,
        serverAddress: String,
        sni: String,
        port: Int,
        uuidOrSecret: String,
        transport: String,
        path: String,
        fingerprint: String,
        fragmentEnabled: Boolean,
        fragmentPackets: String,
        fragmentLength: String,
        fragmentInterval: String,
        udpNoiseEnabled: Boolean,
        muxEnabled: Boolean,
        psiphonChainEnabled: Boolean,
    ) -> Unit,
    buildXrayJson: (XrayConfigEntity) -> String,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var showWizardDialog by remember { mutableStateOf(false) }
    var showImportSubDialog by remember { mutableStateOf(false) }
    var showPasteUriDialog by remember { mutableStateOf(false) }
    var jsonPreviewConfig by remember { mutableStateOf<XrayConfigEntity?>(null) }
    var obfuscationEditConfig by remember { mutableStateOf<XrayConfigEntity?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            BpbSectionHeader(
                title = "Xray Configurations (Room DB)",
                subtitle = "Manage server addresses, ports, transports & per-config obfuscation",
                modifier = Modifier.widthIn(max = 600.dp),
                trailing = {
                    if (uiState.xrayConfigs.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onPingAll,
                            enabled = !uiState.isPingingConfigs,
                            modifier = Modifier.testTag("ping_all_profiles_button")
                        ) {
                            if (uiState.isPingingConfigs) {
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
                    Text("New Xray Config")
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
                                        text = "${sub.profileCount} configs • ${sub.url}",
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

        if (uiState.xrayConfigs.isEmpty()) {
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
                            text = "No Xray Configurations Stored",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Create a custom Xray config with server address, port, and fragment/noise obfuscation parameters, or import a subscription/URI.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { showWizardDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Create Xray Config")
                        }
                    }
                }
            }
        } else {
            items(uiState.xrayConfigs, key = { it.id }) { cfg ->
                val isActive = cfg.id == uiState.activeXrayConfig?.id
                BpbPanelCard(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .testTag("profile_card_${cfg.id}"),
                    highlighted = isActive,
                    onClick = { onSelectConfig(cfg) }
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
                            ProtocolBadge(protocol = cfg.protocolEnum())
                            Text(
                                text = cfg.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LatencyPill(latencyMs = cfg.latencyMs)
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Active config",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${cfg.serverAddress}:${cfg.port} • ${cfg.networkTransport.uppercase()}/${cfg.securityType.uppercase()} • fp=${cfg.tlsFingerprint}",
                        style = MonoCaptionStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("Frag: ${if (cfg.fragmentEnabled) "${cfg.fragmentPackets} (${cfg.fragmentLength}B)" else "Off"}")
                            append(" • Noise: ${if (cfg.udpNoiseEnabled) cfg.udpNoiseType else "Off"}")
                            if (cfg.muxEnabled) append(" • Mux:${cfg.muxConcurrency}")
                            if (cfg.psiphonChainEnabled) append(" • +Psiphon")
                        },
                        style = MonoCaptionStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { obfuscationEditConfig = cfg }) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Edit obfuscation parameters"
                            )
                        }
                        IconButton(onClick = { onTogglePin(cfg) }) {
                            Icon(
                                imageVector = if (cfg.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = "Pin config",
                                tint = if (cfg.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { jsonPreviewConfig = cfg }) {
                            Icon(Icons.Filled.Code, contentDescription = "Inspect Xray JSON")
                        }
                        IconButton(
                            onClick = {
                                val uri = ConfigParser.toShareUri(cfg.toVpnProfileEntity())
                                clipboard.setText(AnnotatedString(uri))
                                onNotify("Copied share URI to clipboard")
                            }
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy share URI")
                        }
                        IconButton(onClick = { onDeleteConfig(cfg) }) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete config",
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
                    TextButton(onClick = { clipboard.getText()?.text?.let { rawInput = it } }) {
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
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteUriDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showImportSubDialog) {
        var subName by remember { mutableStateOf("BPB / PattNG Sub") }
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
                        label = { Text("https://...") },
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
                ) { Text("Fetch & Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportSubDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showWizardDialog) {
        XrayConfigWizardDialog(
            onDismiss = { showWizardDialog = false },
            onSave = { n, p, addr, sni, port, uuid, tr, path, fp, fe, fpkt, flen, fint, noise, mux, psiphon ->
                onCreateWizardConfig(
                    n, p, addr, sni, port, uuid, tr, path, fp,
                    fe, fpkt, flen, fint, noise, mux, psiphon
                )
                showWizardDialog = false
            }
        )
    }

    obfuscationEditConfig?.let { target ->
        ObfuscationEditorDialog(
            config = target,
            onDismiss = { obfuscationEditConfig = null },
            onSave = { updated ->
                onUpdateObfuscation(updated)
                obfuscationEditConfig = null
            }
        )
    }

    jsonPreviewConfig?.let { cfg ->
        val xrayJson = remember(cfg, uiState.settings) { buildXrayJson(cfg) }
        AlertDialog(
            onDismissRequest = { jsonPreviewConfig = null },
            title = { Text("Xray-core JSON • ${cfg.name}") },
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
                        jsonPreviewConfig = null
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Copy JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { jsonPreviewConfig = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ObfuscationEditorDialog(
    config: XrayConfigEntity,
    onDismiss: () -> Unit,
    onSave: (XrayConfigEntity) -> Unit,
) {
    var fragEnabled by remember { mutableStateOf(config.fragmentEnabled) }
    var fragPackets by remember { mutableStateOf(config.fragmentPackets) }
    var fragLength by remember { mutableStateOf(config.fragmentLength) }
    var fragInterval by remember { mutableStateOf(config.fragmentInterval) }
    var noiseEnabled by remember { mutableStateOf(config.udpNoiseEnabled) }
    var noisePacket by remember { mutableStateOf(config.udpNoisePacket) }
    var noiseDelay by remember { mutableStateOf(config.udpNoiseDelay) }
    var muxEnabled by remember { mutableStateOf(config.muxEnabled) }
    var psiphonChain by remember { mutableStateOf(config.psiphonChainEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Obfuscation • ${config.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TLS Fragment", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = fragEnabled, onCheckedChange = { fragEnabled = it })
                }
                if (fragEnabled) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("tlshello", "1-1", "1-3", "1-5").forEach { mode ->
                            FilterChip(
                                selected = fragPackets == mode,
                                onClick = { fragPackets = mode },
                                label = { Text(mode, style = MonoCaptionStyle) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = fragLength,
                            onValueChange = { fragLength = it },
                            label = { Text("Length") },
                            singleLine = true,
                            textStyle = MonoBodyStyle,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = fragInterval,
                            onValueChange = { fragInterval = it },
                            label = { Text("Interval") },
                            singleLine = true,
                            textStyle = MonoBodyStyle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("UDP Noise Padding", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = noiseEnabled, onCheckedChange = { noiseEnabled = it })
                }
                if (noiseEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = noisePacket,
                            onValueChange = { noisePacket = it },
                            label = { Text("Noise Packet") },
                            singleLine = true,
                            textStyle = MonoBodyStyle,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = noiseDelay,
                            onValueChange = { noiseDelay = it },
                            label = { Text("Delay (ms)") },
                            singleLine = true,
                            textStyle = MonoBodyStyle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Xray Mux Multiplexing", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = muxEnabled, onCheckedChange = { muxEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chain via Psiphon", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = psiphonChain, onCheckedChange = { psiphonChain = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        config.copy(
                            fragmentEnabled = fragEnabled,
                            fragmentPackets = fragPackets,
                            fragmentLength = fragLength,
                            fragmentInterval = fragInterval,
                            udpNoiseEnabled = noiseEnabled,
                            udpNoisePacket = noisePacket,
                            udpNoiseDelay = noiseDelay,
                            muxEnabled = muxEnabled,
                            psiphonChainEnabled = psiphonChain
                        )
                    )
                }
            ) { Text("Save to Room") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun XrayConfigWizardDialog(
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        protocol: ProtocolType,
        serverAddress: String,
        sni: String,
        port: Int,
        uuidOrSecret: String,
        transport: String,
        path: String,
        fingerprint: String,
        fragmentEnabled: Boolean,
        fragmentPackets: String,
        fragmentLength: String,
        fragmentInterval: String,
        udpNoiseEnabled: Boolean,
        muxEnabled: Boolean,
        psiphonChainEnabled: Boolean,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("BPB / PattNG Xray Node") }
    var protocol by remember { mutableStateOf(ProtocolType.VLESS) }
    var serverAddress by remember { mutableStateOf("") }
    var sni by remember { mutableStateOf("") }
    var port by remember { mutableIntStateOf(443) }
    var uuidOrSecret by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var transport by remember { mutableStateOf("ws") }
    var path by remember { mutableStateOf("/?ed=2560") }
    var fingerprint by remember { mutableStateOf("chrome") }
    var fragmentEnabled by remember { mutableStateOf(true) }
    var fragmentPackets by remember { mutableStateOf("tlshello") }
    var fragmentLength by remember { mutableStateOf("10-20") }
    var fragmentInterval by remember { mutableStateOf("10-20") }
    var udpNoiseEnabled by remember { mutableStateOf(true) }
    var muxEnabled by remember { mutableStateOf(false) }
    var psiphonChain by remember { mutableStateOf(false) }

    val ports = listOf(443, 2053, 2083, 2087, 2096, 8443)
    val protocols = listOf(ProtocolType.VLESS, ProtocolType.TROJAN, ProtocolType.WARP, ProtocolType.MASQUE)
    val transports = listOf("ws", "xhttp", "grpc", "tcp")
    val fingerprints = listOf("chrome", "firefox", "safari", "ios", "randomized")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xray Config Builder") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    label = { Text("Config Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it },
                    label = { Text("Server Address / Clean IP") },
                    placeholder = { Text("104.16.x.x or domain") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("SNI / Worker Domain") },
                    placeholder = { Text("panel.example.workers.dev") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
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
                    label = { Text("Path / ServiceName") },
                    singleLine = true,
                    textStyle = MonoBodyStyle,
                    modifier = Modifier.fillMaxWidth()
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TLS Fragment ($fragmentPackets)", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = fragmentEnabled, onCheckedChange = { fragmentEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("UDP Noise Obfuscation", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = udpNoiseEnabled, onCheckedChange = { udpNoiseEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mux Multiplexing", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = muxEnabled, onCheckedChange = { muxEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chain with Psiphon", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = psiphonChain, onCheckedChange = { psiphonChain = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name, protocol, serverAddress, sni, port, uuidOrSecret,
                        transport, path, fingerprint, fragmentEnabled,
                        fragmentPackets, fragmentLength, fragmentInterval,
                        udpNoiseEnabled, muxEnabled, psiphonChain
                    )
                },
                enabled = serverAddress.isNotBlank() || sni.isNotBlank()
            ) { Text("Save Config") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
