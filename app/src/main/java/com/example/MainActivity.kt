package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ads.RewardedAdManager
import com.example.model.ConnectionState
import com.example.model.PermanentPermissionsStatus
import com.example.model.VpnServer
import com.example.service.AtlasVpnService
import com.example.tunnel.VpnIpc
import com.example.tunnel.VpnStateHolder
import com.example.viewmodel.VpnViewModel
import com.example.ui.HomeScreen
import com.example.ui.RegionsScreen
import com.example.ui.SettingsScreen
import com.example.ui.SpeedTestScreen
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberTextMuted

class MainActivity : ComponentActivity() {

    private val vpnViewModel: VpnViewModel by viewModels()
    private var isAdProcessing by mutableStateOf(false)
    private var permissionsStatus by mutableStateOf(PermanentPermissionsStatus())

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        refreshPermissionStatus()
        if (result.resultCode == RESULT_OK) {
            startVpnServiceInternal()
        } else {
            isAdProcessing = false
            vpnViewModel.setConnectionState(ConnectionState.Error("Permissão de VPN negada pelo sistema."))
        }
    }

    private val vpnAuthorizeOnlyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        refreshPermissionStatus()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VpnStateHolder.initClientSync(applicationContext)
        RewardedAdManager.initialize(applicationContext)
        vpnViewModel.ensureServersLoaded()

        refreshPermissionStatus()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionsStatus.hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            com.example.ui.theme.MyApplicationTheme {
                MainAppScreen(
                    viewModel = vpnViewModel,
                    isAdProcessing = isAdProcessing,
                    permissionsStatus = permissionsStatus,
                    onInitiateConnect = { targetServer ->
                        triggerRewardedAdThenConnect(targetServer)
                    },
                    onInitiatePause = {
                        pauseVpnServiceInternal()
                    },
                    onInitiateResume = {
                        resumeVpnServiceInternal()
                    },
                    onInitiateDisconnect = {
                        isAdProcessing = false
                        stopVpnServiceInternal()
                    },
                    onOpenBatterySettings = { openBatteryOptimizationSettings() },
                    onRequestVpnPermission = { requestVpnAuthorizationOnly() }
                )
            }
        }
    }

    private fun executeConnect(targetServer: VpnServer) {
        vpnViewModel.selectServer(targetServer)
        VpnStateHolder.activeTargetServer = targetServer
        val intent = VpnService.prepare(this@MainActivity)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnServiceInternal()
        }
    }

    private fun triggerRewardedAdThenConnect(targetServer: VpnServer) {
        if (isAdProcessing) return
        isAdProcessing = true

        RewardedAdManager.showOrWaitAndReport(
            activity = this@MainActivity,
            onRewardEarned = {
                isAdProcessing = false
                executeConnect(targetServer)
            },
            onDismissedWithoutReward = {
                isAdProcessing = false
                Toast.makeText(
                    applicationContext,
                    "Conexão cancelada. Assista ao anúncio completo para conectar.",
                    Toast.LENGTH_LONG
                ).show()
            },
            onAdUnavailable = { errorDetail ->
                isAdProcessing = false
                RewardedAdManager.preload(applicationContext)
                val msg = if (errorDetail.contains("NETWORK_ERROR") || errorDetail.contains("Código: 2")) {
                    "Erro de rede ao carregar anúncio. Verifique sua conexão com a internet."
                } else if (!RewardedAdManager.FALLBACK_TO_TEST_ON_FAIL) {
                    "Anúncio indisponível no momento. Tente novamente mais tarde."
                } else {
                    "Anúncio indisponível no momento. Tentando novamente em instantes."
                }
                Toast.makeText(
                    applicationContext,
                    msg,
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        VpnStateHolder.requestSync(applicationContext)
        RewardedAdManager.preload(applicationContext)
        vpnViewModel.ensureServersLoaded()
    }

    private fun refreshPermissionStatus() {
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val batteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) ?: true
        } else {
            true
        }
        val vpnOk = VpnService.prepare(this) == null
        permissionsStatus = PermanentPermissionsStatus(
            hasNotificationPermission = notifOk,
            isBatteryUnrestricted = batteryOk,
            hasVpnPermission = vpnOk
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!permissionsStatus.hasNotificationPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        Toast.makeText(
            applicationContext,
            "Selecione 'Zerox VPN' e escolha 'Sem restrições' para manter a VPN permanente.",
            Toast.LENGTH_LONG
        ).show()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                return
            }
        } catch (_: Exception) {
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun requestVpnAuthorizationOnly() {
        val intent = VpnService.prepare(this@MainActivity)
        if (intent != null) {
            vpnAuthorizeOnlyLauncher.launch(intent)
        } else {
            refreshPermissionStatus()
        }
    }

    private fun startVpnServiceInternal() {
        val server = vpnViewModel.selectedServer.value ?: return
        requestNotificationPermission()
        VpnStateHolder.activeTargetServer = server
        val settings = vpnViewModel.settings.value

        vpnViewModel.setConnectionState(ConnectionState.Connecting(server, "Iniciando túnel seguro..."))

        val intent = Intent(this, AtlasVpnService::class.java).apply {
            action = AtlasVpnService.ACTION_START_VPN
            putExtra(AtlasVpnService.EXTRA_SERVER_JSON, VpnIpc.serverToJson(server).toString())
            putExtra(AtlasVpnService.EXTRA_SERVER_IP, server.ip)
            putExtra(AtlasVpnService.EXTRA_SERVER_PORT, server.port)
            putExtra(AtlasVpnService.EXTRA_SERVER_NAME, server.countryName)
            putExtra(AtlasVpnService.EXTRA_KILL_SWITCH, settings.killSwitchEnabled)
            putExtra(AtlasVpnService.EXTRA_DNS_PROVIDER, settings.dnsProvider.name)
            putExtra(AtlasVpnService.EXTRA_PROTOCOL, settings.selectedProtocol.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun pauseVpnServiceInternal() {
        requestNotificationPermission()
        val intent = Intent(this, AtlasVpnService::class.java).apply {
            action = AtlasVpnService.ACTION_PAUSE_VPN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun resumeVpnServiceInternal() {
        requestNotificationPermission()
        val intent = Intent(this, AtlasVpnService::class.java).apply {
            action = AtlasVpnService.ACTION_RESUME_VPN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnServiceInternal() {
        val intent = Intent(this, AtlasVpnService::class.java).apply {
            action = AtlasVpnService.ACTION_STOP_VPN
        }
        startService(intent)
        vpnViewModel.setConnectionState(ConnectionState.Disconnected)
    }
}

@Composable
fun MainAppScreen(
    viewModel: VpnViewModel,
    isAdProcessing: Boolean,
    permissionsStatus: PermanentPermissionsStatus,
    onInitiateConnect: (VpnServer) -> Unit,
    onInitiatePause: () -> Unit,
    onInitiateResume: () -> Unit,
    onInitiateDisconnect: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestVpnPermission: () -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(0) }
    var showNavLabels by rememberSaveable { mutableStateOf(true) }

    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val isLoadingServers by viewModel.isLoadingServers.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val trafficMetrics by viewModel.trafficMetrics.collectAsStateWithLifecycle()
    val speedTestResult by viewModel.speedTestResult.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selectedContinent by viewModel.selectedContinentFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val isConnectedOrConnecting = connectionState is ConnectionState.Connected ||
        connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Preparing

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -5f && showNavLabels) {
                    showNavLabels = false
                } else if (available.y > 5f && !showNavLabels) {
                    showNavLabels = true
                }
                return Offset.Zero
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(nestedScrollConnection)
            ) {
                when (currentTab) {
                    0 -> HomeScreen(
                        selectedServer = selectedServer,
                        isScanningServers = isLoadingServers,
                        isAdProcessing = isAdProcessing,
                        connectionState = connectionState,
                        trafficMetrics = trafficMetrics,
                        onConnectToggle = {
                            when {
                                connectionState is ConnectionState.Paused -> onInitiateResume()
                                isConnectedOrConnecting -> onInitiateDisconnect()
                                else -> {
                                    val target = selectedServer ?: servers.firstOrNull()
                                    if (target != null) {
                                        onInitiateConnect(target)
                                    } else {
                                        viewModel.ensureServersLoaded()
                                        showNavLabels = true
                                        currentTab = 1
                                    }
                                }
                            }
                        },
                        onPauseVpn = onInitiatePause,
                        onResumeVpn = onInitiateResume,
                        onDisconnectVpn = onInitiateDisconnect,
                        onSelectRegionClick = {
                            viewModel.ensureServersLoaded()
                            showNavLabels = true
                            currentTab = 1
                        },
                        onDismissError = { viewModel.clearError() }
                    )
                    1 -> RegionsScreen(
                        servers = servers,
                        isLoading = isLoadingServers,
                        scanStatus = scanStatus,
                        scanProgress = scanProgress,
                        selectedServer = selectedServer,
                        selectedContinent = selectedContinent,
                        searchQuery = searchQuery,
                        onServerSelected = { server ->
                            viewModel.selectServer(server)
                        },
                        onConnectSelectedClick = { server ->
                            if (!isAdProcessing) {
                                currentTab = 0
                                showNavLabels = true
                                onInitiateConnect(server)
                            }
                        },
                        onRefreshClick = { viewModel.loadServers(forceRestart = true) },
                        onContinentSelected = { viewModel.setContinentFilter(it) },
                        onSearchQueryChanged = { viewModel.setSearchQuery(it) }
                    )
                    2 -> SpeedTestScreen(
                        selectedServer = selectedServer,
                        speedTestResult = speedTestResult,
                        onStartSpeedTest = { viewModel.runSpeedTest() }
                    )
                    3 -> SettingsScreen(
                        settings = settings,
                        permissionsStatus = permissionsStatus,
                        onSettingsChanged = { viewModel.updateSettings(it) },
                        onOpenBatterySettings = onOpenBatterySettings,
                        onRequestVpnPermission = onRequestVpnPermission
                    )
                }

                // Subtle smooth animated floating bottom navigation bar
                val barHorizontalPadding by animateDpAsState(
                    targetValue = if (showNavLabels) 20.dp else 48.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "nav_h_pad"
                )
                val barVerticalPadding by animateDpAsState(
                    targetValue = if (showNavLabels) 10.dp else 8.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "nav_v_pad"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = barHorizontalPadding, vertical = 12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(30.dp),
                        color = CyberSurface.copy(alpha = 0.96f),
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("floating_navigation_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = barVerticalPadding),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FloatingNavItem(
                                selected = currentTab == 0,
                                showLabel = showNavLabels,
                                label = "Início",
                                selectedIcon = Icons.Default.Security,
                                unselectedIcon = Icons.Outlined.Security,
                                testTag = "nav_tab_home",
                                onClick = {
                                    showNavLabels = true
                                    currentTab = 0
                                }
                            )
                            FloatingNavItem(
                                selected = currentTab == 1,
                                showLabel = showNavLabels,
                                label = "Regiões",
                                selectedIcon = Icons.Default.Public,
                                unselectedIcon = Icons.Outlined.Public,
                                testTag = "nav_tab_regions",
                                onClick = {
                                    viewModel.ensureServersLoaded()
                                    showNavLabels = true
                                    currentTab = 1
                                }
                            )
                            FloatingNavItem(
                                selected = currentTab == 2,
                                showLabel = showNavLabels,
                                label = "Velocidade",
                                selectedIcon = Icons.Default.Speed,
                                unselectedIcon = Icons.Outlined.Speed,
                                testTag = "nav_tab_speed",
                                onClick = {
                                    showNavLabels = true
                                    currentTab = 2
                                }
                            )
                            FloatingNavItem(
                                selected = currentTab == 3,
                                showLabel = showNavLabels,
                                label = "Definições",
                                selectedIcon = Icons.Default.Settings,
                                unselectedIcon = Icons.Outlined.Settings,
                                testTag = "nav_tab_settings",
                                onClick = {
                                    showNavLabels = true
                                    currentTab = 3
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    selected: Boolean,
    showLabel: Boolean,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    val tint = if (selected) CyberPrimary else CyberTextMuted
    val iconSize by animateDpAsState(
        targetValue = if (showLabel) 24.dp else 29.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "icon_size"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = CyberPrimary),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = if (showLabel) 6.dp else 8.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        AnimatedVisibility(
            visible = showLabel,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = tint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
