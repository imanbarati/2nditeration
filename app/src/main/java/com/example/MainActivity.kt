package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.ConnectionStatus
import com.example.data.model.TunnelMode
import com.example.ui.BpbViewModel
import com.example.ui.screens.AetherWarpScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.PacketObfuscationScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.RoutingRulesScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplitTunnelScreen
import com.example.ui.theme.BpbVpnTheme
import kotlinx.coroutines.launch

object BpbRoutes {
    const val HOME = "home"
    const val AETHER = "aether"
    const val PROFILES = "profiles"
    const val SCANNER = "scanner"
    const val SETTINGS = "settings"
    const val SETTINGS_PACKET = "settings_packet"
    const val SETTINGS_ROUTING = "settings_routing"
    const val SETTINGS_SPLIT_TUNNEL = "settings_split_tunnel"
    const val SETTINGS_LOGS = "settings_logs"
}

private data class TopLevelNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: BpbViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            BpbVpnTheme(themeMode = uiState.settings.themeMode) {
                BpbVpnAppRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun BpbVpnAppRoot(viewModel: BpbViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val dohMetrics by viewModel.dohMetrics.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var scannerInitialTab by remember { mutableIntStateOf(0) }
    var pendingServerlessDoh by remember { mutableStateOf(false) }

    fun launchRequestedVpn() {
        if (pendingServerlessDoh) {
            pendingServerlessDoh = false
            viewModel.startServerlessDohService()
        } else {
            viewModel.startVpnService()
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launchRequestedVpn()
        } else {
            pendingServerlessDoh = false
            scope.launch {
                snackbarHostState.showSnackbar("VPN permission is required to start the tunnel.")
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            launchRequestedVpn()
        }
    }

    fun requestPermissionsAndStartVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            launchRequestedVpn()
        }
    }

    fun requestStartOrToggleVpn() {
        val status = uiState.tunnelStats.status
        if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.VERIFYING) {
            viewModel.stopVpnService()
            return
        }
        pendingServerlessDoh = false
        requestPermissionsAndStartVpn()
    }

    fun requestToggleServerlessDoh() {
        val status = uiState.tunnelStats.status
        val isActive = status == ConnectionStatus.CONNECTED || status == ConnectionStatus.VERIFYING
        if (isActive && uiState.tunnelStats.mode == TunnelMode.SERVERLESS_DOH) {
            viewModel.stopVpnService()
            return
        }
        if (isActive) {
            viewModel.stopVpnService()
        }
        pendingServerlessDoh = true
        requestPermissionsAndStartVpn()
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val topLevelItems = remember {
        listOf(
            TopLevelNavItem(
                route = BpbRoutes.HOME,
                label = "Connect",
                selectedIcon = Icons.Filled.PowerSettingsNew,
                unselectedIcon = Icons.Outlined.PowerSettingsNew,
                testTag = "nav_home"
            ),
            TopLevelNavItem(
                route = BpbRoutes.AETHER,
                label = "Aether",
                selectedIcon = Icons.Filled.Security,
                unselectedIcon = Icons.Outlined.Security,
                testTag = "nav_aether"
            ),
            TopLevelNavItem(
                route = BpbRoutes.PROFILES,
                label = "Xray",
                selectedIcon = Icons.Filled.Hub,
                unselectedIcon = Icons.Outlined.Hub,
                testTag = "nav_profiles"
            ),
            TopLevelNavItem(
                route = BpbRoutes.SCANNER,
                label = "Scanner",
                selectedIcon = Icons.Filled.Radar,
                unselectedIcon = Icons.Outlined.Radar,
                testTag = "nav_scanner"
            ),
            TopLevelNavItem(
                route = BpbRoutes.SETTINGS,
                label = "Settings",
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                testTag = "nav_settings"
            )
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isTopLevelRoute = currentDestination?.route in topLevelItems.map { it.route }
    val useNavigationRail = LocalConfiguration.current.screenWidthDp >= 600

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (isTopLevelRoute && !useNavigationRail) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    topLevelItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTopLevel(item.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label, maxLines = 1) },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isTopLevelRoute && useNavigationRail) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    topLevelItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(item.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label, maxLines = 1) },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = BpbRoutes.HOME
                ) {
                    composable(BpbRoutes.HOME) {
                        HomeScreen(
                            uiState = uiState,
                            onToggleConnection = { requestStartOrToggleVpn() },
                            onModeSelected = { viewModel.setTunnelMode(it) },
                            onSelectXrayConfig = { viewModel.selectXrayConfig(it) },
                            onNavigateToAether = { navigateTopLevel(BpbRoutes.AETHER) },
                            onNavigateToProfiles = { navigateTopLevel(BpbRoutes.PROFILES) },
                            onNavigateToScanner = { openDnsTab ->
                                scannerInitialTab = if (openDnsTab) 1 else 0
                                navigateTopLevel(BpbRoutes.SCANNER)
                            },
                            onNavigateToLogs = { navController.navigate(BpbRoutes.SETTINGS_LOGS) }
                        )
                    }

                    composable(BpbRoutes.AETHER) {
                        AetherWarpScreen(
                            uiState = uiState,
                            onUpdateAether = { viewModel.updateAetherConfig(it) },
                            onUpdatePsiphon = { viewModel.updatePsiphonConfig(it) },
                            onDiscoverEndpoints = { viewModel.discoverWarpEndpoints() },
                            onApplyCandidate = { viewModel.applyWarpCandidate(it) }
                        )
                    }

                    composable(BpbRoutes.PROFILES) {
                        ProfilesScreen(
                            uiState = uiState,
                            onSelectConfig = { viewModel.selectXrayConfig(it) },
                            onTogglePin = { viewModel.togglePinXrayConfig(it) },
                            onDeleteConfig = { viewModel.deleteXrayConfig(it) },
                            onUpdateObfuscation = { viewModel.updateXrayObfuscation(it) },
                            onPingAll = { viewModel.pingAllConfigs() },
                            onImportUriOrPayload = { viewModel.importFromUriOrPayload(it) },
                            onImportSubscription = { name, url -> viewModel.importSubscription(name, url) },
                            onRefreshSubscription = { viewModel.refreshSubscription(it) },
                            onCreateWizardConfig = { n, p, addr, sni, port, uuid, tr, path, fp, fe, fpkt, flen, fint, noise, mux, psiphon ->
                                viewModel.createXrayConfigFromWizard(
                                    n, p, addr, sni, port, uuid, tr, path, fp,
                                    fe, fpkt, flen, fint, noise, mux, psiphon
                                )
                            },
                            buildXrayJson = { viewModel.buildXrayJsonFor(it) },
                            onNotify = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                        )
                    }

                    composable(BpbRoutes.SCANNER) {
                        ScannerScreen(
                            uiState = uiState,
                            dohMetrics = dohMetrics,
                            initialTab = scannerInitialTab,
                            onUpdateScannerConfig = { viewModel.updateScannerConfig(it) },
                            onStartScan = { viewModel.startCleanIpScan() },
                            onStopScan = { viewModel.stopCleanIpScan() },
                            onApplyCleanIp = { viewModel.applyCleanIpToActiveConfig(it) },
                            onApplyCleanIpAsDohBootstrap = { viewModel.applyCleanIpAsDohBootstrap(it) },
                            onToggleServerlessDohTunnel = { requestToggleServerlessDoh() },
                            onSelectDnsPreset = { viewModel.selectDnsPreset(it) },
                            onSaveCustomDoh = { url, sni, bootIp -> viewModel.saveCustomDoh(url, sni, bootIp) },
                            onTestAllDns = { viewModel.testAllDns(it) },
                            onNotify = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                        )
                    }

                    composable(BpbRoutes.SETTINGS) {
                        SettingsScreen(
                            uiState = uiState,
                            onThemeChanged = { viewModel.setThemeMode(it) },
                            onOpenPacketSettings = { navController.navigate(BpbRoutes.SETTINGS_PACKET) },
                            onOpenRoutingRules = { navController.navigate(BpbRoutes.SETTINGS_ROUTING) },
                            onOpenSplitTunnel = { navController.navigate(BpbRoutes.SETTINGS_SPLIT_TUNNEL) },
                            onOpenLogs = { navController.navigate(BpbRoutes.SETTINGS_LOGS) }
                        )
                    }

                    composable(BpbRoutes.SETTINGS_PACKET) {
                        PacketObfuscationScreen(
                            uiState = uiState,
                            onUpdateFragment = { viewModel.updateFragmentConfig(it) },
                            onSelectUdpNoise = { viewModel.setUdpNoisePreset(it) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(BpbRoutes.SETTINGS_ROUTING) {
                        RoutingRulesScreen(
                            uiState = uiState,
                            onUpdateRouting = { viewModel.updateRoutingConfig(it) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(BpbRoutes.SETTINGS_SPLIT_TUNNEL) {
                        SplitTunnelScreen(
                            uiState = uiState,
                            onLoadApps = { viewModel.loadInstalledAppsIfNeeded() },
                            onSetMode = { viewModel.setSplitTunnelMode(it) },
                            onToggleApp = { viewModel.togglePackageSelection(it) },
                            onClearAll = { viewModel.clearSelectedPackages() },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(BpbRoutes.SETTINGS_LOGS) {
                        LogsScreen(
                            logs = logs,
                            onClearLogs = { viewModel.clearLogs() },
                            onNotify = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
