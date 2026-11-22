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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.ads.AdConsentManager
import com.example.ads.AdLoadState
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
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSecondary
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberTextMain
import com.example.ui.theme.CyberTextMuted

class MainActivity : ComponentActivity() {

    private val vpnViewModel: VpnViewModel by viewModels()
    private var isAdProcessing by mutableStateOf(false)
    private var permissionsStatus by mutableStateOf(PermanentPermissionsStatus())
    private var showPrivacyConsentDialog by mutableStateOf(false)

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
        vpnViewModel.ensureServersLoaded()

        RewardedAdManager.updateActivity(this)
        if (AdConsentManager.hasUserMadeChoice(this)) {
            RewardedAdManager.initializeSdk(this) {
                RewardedAdManager.preloadSilently(this)
            }
        } else {
            RewardedAdManager.initializeSdk(this)
            showPrivacyConsentDialog = true
        }

        refreshPermissionStatus()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionsStatus.hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            com.example.ui.theme.MyApplicationTheme {
                val adConsentState by AdConsentManager.consentState.collectAsStateWithLifecycle()
                val adLoadState by RewardedAdManager.adState.collectAsStateWithLifecycle()
                val isSdkInitialized by RewardedAdManager.sdkInitialized.collectAsStateWithLifecycle()

                MainAppScreen(
                    viewModel = vpnViewModel,
                    isAdProcessing = isAdProcessing,
                    permissionsStatus = permissionsStatus,
                    adConsentState = adConsentState,
                    adLoadState = adLoadState,
                    isSdkInitialized = isSdkInitialized,
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
                    onRequestVpnPermission = { requestVpnAuthorizationOnly() },
                    onUpdateAdConsent = { granted ->
                        RewardedAdManager.invalidateCachedAd()
                        RewardedAdManager.initializeSdk(this@MainActivity) {
                            AdConsentManager.saveConsentChoice(this@MainActivity, granted)
                            RewardedAdManager.preloadSilently(this@MainActivity, force = true)
                        }
                    },
                    onTestLoadAd = {
                        RewardedAdManager.loadAd(
                            context = this@MainActivity,
                            onLoaded = {
                                Toast.makeText(
                                    applicationContext,
                                    "Anúncio pré-carregado em cache e pronto!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFailed = { detail ->
                                Toast.makeText(
                                    applicationContext,
                                    "Anúncio indisponível ($detail). A conexão VPN permanece bloqueada até haver anúncio válido.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    },
                    onTestShowAd = {
                        RewardedAdManager.showLoadedAd(
                            activity = this@MainActivity,
                            onFinished = { success, message ->
                                Toast.makeText(
                                    applicationContext,
                                    message,
                                    if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                )

                if (showPrivacyConsentDialog) {
                    PrivacyConsentDialog(
                        onDecision = { granted ->
                            showPrivacyConsentDialog = false
                            RewardedAdManager.invalidateCachedAd()
                            RewardedAdManager.initializeSdk(this@MainActivity) {
                                AdConsentManager.saveConsentChoice(this@MainActivity, granted)
                                RewardedAdManager.preloadSilently(this@MainActivity, force = true)
                            }
                        }
                    )
                }
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

        RewardedAdManager.showRequiredAdOrBlock(
            activity = this@MainActivity,
            onAdClosedConnectVpn = {
                isAdProcessing = false
                executeConnect(targetServer)
            },
            onBlocked = { reason ->
                isAdProcessing = false
                Toast.makeText(
                    applicationContext,
                    reason,
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    override fun onResume() {
        super.onResume()
        RewardedAdManager.updateActivity(this)
        if (AdConsentManager.hasUserMadeChoice(this)) {
            RewardedAdManager.preloadSilently(this)
        }
        refreshPermissionStatus()
        VpnStateHolder.requestSync(applicationContext)
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
    adConsentState: com.example.ads.AdConsentState,
    adLoadState: AdLoadState,
    isSdkInitialized: Boolean,
    onInitiateConnect: (VpnServer) -> Unit,
    onInitiatePause: () -> Unit,
    onInitiateResume: () -> Unit,
    onInitiateDisconnect: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestVpnPermission: () -> Unit,
    onUpdateAdConsent: (Boolean) -> Unit,
    onTestLoadAd: () -> Unit,
    onTestShowAd: () -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(0) }
    var navExpandedState by rememberSaveable { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val navExpandProgress = remember { Animatable(if (navExpandedState) 1f else 0f) }
    val manualScrubRef = remember { floatArrayOf(if (navExpandedState) 1f else 0f) }
    val scrubDistancePx = with(LocalDensity.current) { 72.dp.toPx() }

    fun snapNavBar(expand: Boolean) {
        navExpandedState = expand
        val target = if (expand) 1f else 0f
        manualScrubRef[0] = target
        coroutineScope.launch {
            navExpandProgress.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

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

    val nestedScrollConnection = remember(scrubDistancePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    val next = (manualScrubRef[0] + (available.y / scrubDistancePx)).coerceIn(0f, 1f)
                    if (next != manualScrubRef[0]) {
                        manualScrubRef[0] = next
                        navExpandedState = next >= 0.5f
                        coroutineScope.launch {
                            navExpandProgress.snapTo(next)
                        }
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val totalVelocityY = consumed.y + available.y
                val targetExpand = when {
                    totalVelocityY > 250f -> true
                    totalVelocityY < -250f -> false
                    else -> manualScrubRef[0] >= 0.5f
                }
                snapNavBar(targetExpand)
                return super.onPostFling(consumed, available)
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
                                        snapNavBar(true)
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
                            snapNavBar(true)
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
                                snapNavBar(true)
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
                        adConsentState = adConsentState,
                        adLoadState = adLoadState,
                        isSdkInitialized = isSdkInitialized,
                        onSettingsChanged = { viewModel.updateSettings(it) },
                        onOpenBatterySettings = onOpenBatterySettings,
                        onRequestVpnPermission = onRequestVpnPermission,
                        onUpdateAdConsent = onUpdateAdConsent,
                        onTestLoadAd = onTestLoadAd,
                        onTestShowAd = onTestShowAd
                    )
                }

                // Finger-tracked continuous floating bottom navigation bar with symmetric auto-layout slots
                val expandProgress = navExpandProgress.value.coerceIn(0f, 1f)
                val barHorizontalPadding = lerp(44.dp, 18.dp, expandProgress)
                val barVerticalPadding = lerp(6.dp, 8.dp, expandProgress)

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
                                .padding(horizontal = 8.dp, vertical = barVerticalPadding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FloatingNavItem(
                                selected = currentTab == 0,
                                expandProgress = expandProgress,
                                label = "Início",
                                selectedIcon = Icons.Default.Security,
                                unselectedIcon = Icons.Outlined.Security,
                                testTag = "nav_tab_home",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    snapNavBar(true)
                                    currentTab = 0
                                }
                            )
                            FloatingNavItem(
                                selected = currentTab == 1,
                                expandProgress = expandProgress,
                                label = "Regiões",
                                selectedIcon = Icons.Default.Public,
                                unselectedIcon = Icons.Outlined.Public,
                                testTag = "nav_tab_regions",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    viewModel.ensureServersLoaded()
                                    snapNavBar(true)
                                    currentTab = 1
                                }
                            )
                            FloatingNavItem(
                                selected = currentTab == 2,
                                expandProgress = expandProgress,
                                label = "Velocidade",
                                selectedIcon = Icons.Default.Speed,
                                unselectedIcon = Icons.Outlined.Speed,
                                testTag = "nav_tab_speed",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    snapNavBar(true)
                                    currentTab = 2
                                }
                            )
                            FloatingNavItem(
                                selected = currentTab == 3,
                                expandProgress = expandProgress,
                                label = "Definições",
                                selectedIcon = Icons.Default.Settings,
                                unselectedIcon = Icons.Outlined.Settings,
                                testTag = "nav_tab_settings",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    snapNavBar(true)
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
    expandProgress: Float,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val progress = expandProgress.coerceIn(0f, 1f)
    val tint = if (selected) CyberPrimary else CyberTextMuted
    val iconSize = lerp(26.dp, 22.dp, progress)
    val itemVerticalPadding = lerp(6.dp, 7.dp, progress)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .padding(horizontal = 3.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                color = if (selected) CyberPrimary.copy(alpha = 0.14f) else Color.Transparent,
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = CyberPrimary),
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = itemVerticalPadding)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val scaledHeight = (placeable.height * progress).roundToInt()
                    layout(placeable.width, scaledHeight) {
                        if (scaledHeight > 0) {
                            placeable.placeRelative(0, 0)
                        }
                    }
                }
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = tint,
                modifier = Modifier
                    .padding(top = lerp(0.dp, 3.dp, progress))
                    .graphicsLayer {
                        alpha = progress
                        val scale = 0.78f + (0.22f * progress)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
            )
        }
    }
}

@Composable
private fun PrivacyConsentDialog(
    onDecision: (Boolean) -> Unit
) {
    Dialog(
        onDismissRequest = { onDecision(false) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true
        )
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CyberSurface,
            border = BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("privacy_consent_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Privacidade e Anúncios",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextMain
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "A Zerox VPN mantém os servidores gratuitos com apoio de anúncios da rede Start.io. Deseja permitir anúncios personalizados?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextMuted
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { onDecision(false) },
                        border = BorderStroke(1.dp, CyberPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("consent_decline_button")
                    ) {
                        Text(
                            text = "Recusar",
                            color = CyberPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { onDecision(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberSecondary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("consent_accept_button")
                    ) {
                        Text(
                            text = "Aceitar",
                            color = CyberBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
