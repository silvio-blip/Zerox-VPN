package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionState
import com.example.model.TrafficMetrics
import com.example.model.VpnServer
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberError
import com.example.ui.theme.CyberOutline
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSecondary
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMain
import com.example.ui.theme.CyberTextMuted

@Composable
fun HomeScreen(
    selectedServer: VpnServer?,
    isScanningServers: Boolean,
    isAdProcessing: Boolean = false,
    connectionState: ConnectionState,
    trafficMetrics: TrafficMetrics,
    onConnectToggle: () -> Unit,
    onPauseVpn: () -> Unit = {},
    onResumeVpn: () -> Unit = {},
    onDisconnectVpn: () -> Unit = {},
    onSelectRegionClick: () -> Unit,
    onDismissError: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val pausedState = connectionState as? ConnectionState.Paused
    val isPaused = pausedState != null
    val isConnecting = connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Preparing
    val errorState = connectionState as? ConnectionState.Error

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "connect_btn_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        CyberSurfaceVariant.copy(alpha = 0.45f),
                        CyberBackground
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 104.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header - Logo & Zerox VPN
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = CyberSurfaceVariant,
                    border = BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.4f)),
                    modifier = Modifier.size(44.dp)
                ) {
                    Image(
                        painter = painterResource(id = com.example.R.drawable.app_logo),
                        contentDescription = "Zerox VPN Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "ZEROX VPN",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextMain,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Rede Global 100% Gratuita",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberSecondary
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = if (isConnected) CyberSecondary.copy(alpha = 0.2f) else CyberPrimary.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, if (isConnected) CyberSecondary else CyberPrimary),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Proteção Ativa",
                        tint = if (isConnected) CyberSecondary else CyberPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Center 3D Particle Globe Connect Orb (Main Action Button)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
            ) {
                if (isConnected || isConnecting || isAdProcessing) {
                    Box(
                        modifier = Modifier
                            .size((190 * pulseScale).dp)
                            .clip(CircleShape)
                            .background(
                                color = if (isConnected) CyberSecondary.copy(alpha = 0.16f)
                                else CyberPrimary.copy(alpha = 0.16f),
                                shape = CircleShape
                            )
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = CyberSurface,
                    shadowElevation = if (isPressed) 6.dp else 14.dp,
                    border = BorderStroke(
                        if (isPressed) 2.5.dp else 2.dp,
                        if (isConnected) CyberSecondary.copy(alpha = if (isPressed) 0.85f else 0.6f)
                        else CyberPrimary.copy(alpha = if (isPressed) 0.85f else 0.5f)
                    ),
                    modifier = Modifier
                        .size(180.dp)
                        .graphicsLayer {
                            scaleX = buttonScale
                            scaleY = buttonScale
                            clip = true
                            shape = CircleShape
                        }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = ripple(
                                bounded = true,
                                color = if (isConnected) CyberSecondary else CyberPrimary
                            ),
                            enabled = !isAdProcessing,
                            onClick = { onConnectToggle() }
                        )
                        .testTag("connect_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .drawBehind {
                                val strokeWidth = 3.dp.toPx()
                                drawCircle(
                                    brush = Brush.sweepGradient(
                                        listOf(
                                            if (isConnected) CyberSecondary else CyberPrimary,
                                            Color.Transparent,
                                            if (isConnected) CyberSecondary else CyberPrimary
                                        )
                                    ),
                                    radius = size.minDimension / 2 - strokeWidth,
                                    style = Stroke(width = strokeWidth)
                                )
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Large 3D Particle Globe filling the center
                            ParticleGlobe3D(isConnecting = isConnecting || isAdProcessing, isConnected = isConnected)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when {
                                    isAdProcessing -> "CARREGANDO ANÚNCIO"
                                    connectionState is ConnectionState.Connected -> "PROTEGIDO"
                                    connectionState is ConnectionState.Paused -> "EM PAUSA"
                                    connectionState is ConnectionState.Connecting -> "CONECTANDO"
                                    connectionState is ConnectionState.Preparing -> "PREPARANDO"
                                    connectionState is ConnectionState.Error -> "FALHA"
                                    else -> "CONECTAR-SE"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isAdProcessing -> CyberPrimary
                                    connectionState is ConnectionState.Connected -> CyberSecondary
                                    connectionState is ConnectionState.Paused -> CyberPrimary
                                    connectionState is ConnectionState.Error -> CyberError
                                    else -> CyberTextMuted
                                },
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Smooth animated Connection Status card
            AnimatedVisibility(
                visible = isAdProcessing || isConnected || isPaused || isConnecting || errorState != null,
                enter = fadeIn(tween(400)) + scaleIn(initialScale = 0.92f, animationSpec = tween(400)) + expandVertically(),
                exit = fadeOut(tween(250)) + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = CyberSurfaceVariant.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, if (isConnected) CyberSecondary.copy(alpha = 0.35f) else CyberPrimary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when {
                            isAdProcessing -> {
                                Text(
                                    text = "PREPARANDO ANÚNCIO...",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Carregando anúncio para conexão gratuita...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberTextMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                            isConnected -> {
                                val connectedState = connectionState as ConnectionState.Connected
                                Text(
                                    text = formatTimer(trafficMetrics.elapsedSeconds),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = CyberSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Túnel persistente ativo • IP ${connectedState.activeRelayIp}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberTextMain,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = onPauseVpn,
                                        border = BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.7f)),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("pause_vpn_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Pause,
                                            contentDescription = "Pausar VPN",
                                            tint = CyberPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "PAUSAR",
                                            color = CyberPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Button(
                                        onClick = onDisconnectVpn,
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberError.copy(alpha = 0.9f)),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("disconnect_vpn_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PowerSettingsNew,
                                            contentDescription = "Desligar VPN",
                                            tint = CyberTextMain,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "DESLIGAR",
                                            color = CyberTextMain,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            pausedState != null -> {
                                Text(
                                    text = "VPN EM PAUSA",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${pausedState.server.countryName} • ⏱ ${formatTimer(pausedState.accumulatedElapsedSeconds)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberTextMain,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onResumeVpn,
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberSecondary),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("resume_vpn_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Retomar VPN",
                                            tint = CyberBackground,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "RETOMAR",
                                            color = CyberBackground,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = onDisconnectVpn,
                                        border = BorderStroke(1.dp, CyberError.copy(alpha = 0.75f)),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("disconnect_paused_vpn_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PowerSettingsNew,
                                            contentDescription = "Desligar VPN",
                                            tint = CyberError,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "DESLIGAR",
                                            color = CyberError,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            isConnecting -> {
                                val detail = (connectionState as? ConnectionState.Connecting)?.statusDetail ?: "Validando rota..."
                                Text(
                                    text = "CONECTANDO...",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberTextMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                            errorState != null -> {
                                Text(
                                    text = "FALHA NA CONEXÃO",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberError,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = errorState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberTextMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Selected Region Country Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CyberSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isAdProcessing) { onSelectRegionClick() }
                    .testTag("selected_region_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            text = getFlagEmoji(selectedServer?.countryCode ?: "PT"),
                            fontSize = 26.sp,
                            modifier = Modifier.padding(end = 14.dp)
                        )
                        Column {
                            Text(
                                text = selectedServer?.let { "${it.countryName} — ${it.city}" }
                                    ?: if (isScanningServers) "Verificando regiões ao vivo..." else "Selecionar País",
                                style = MaterialTheme.typography.titleMedium,
                                color = CyberTextMain
                            )
                            Text(
                                text = selectedServer?.let { "${it.pingMs} ms" }
                                    ?: if (isScanningServers) "Buscando menor latência..." else "Toque para buscar regiões",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberPrimary
                            )
                        }
                    }
                    if (selectedServer == null && isScanningServers) {
                        CircularProgressIndicator(
                            color = CyberPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "Trocar Região",
                            tint = CyberPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Bottom Telemetry Strip when Connected
        AnimatedVisibility(
            visible = isConnected,
            enter = fadeIn(tween(400)) + expandVertically(),
            exit = fadeOut(tween(200)) + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(color = CyberOutline, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryItem(label = "DOWNLOAD", value = formatBytes(trafficMetrics.totalDownloadBytes))
                    VerticalDivider(modifier = Modifier.height(26.dp), color = CyberOutline)
                    TelemetryItem(label = "UPLOAD", value = formatBytes(trafficMetrics.totalUploadBytes))
                    VerticalDivider(modifier = Modifier.height(26.dp), color = CyberOutline)
                    TelemetryItem(label = "PING", value = "${(connectionState as? ConnectionState.Connected)?.server?.pingMs ?: selectedServer?.pingMs ?: 20} ms")
                }
                HorizontalDivider(color = CyberOutline, thickness = 1.dp)
            }
        }
    }
}

@Composable
fun ParticleGlobe3D(isConnecting: Boolean, isConnected: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "particle_globe")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isConnected) 8000 else if (isConnecting) 900 else 4000,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "globe_rot"
    )

    val color = if (isConnected) CyberSecondary else CyberPrimary

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(92.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension / 2f * 0.9f

            val rad = Math.toRadians(rotationAngle.toDouble())

            // Draw particle meridians and parallels for 3D rotating globe effect
            for (i in 0 until 6) {
                val angleOffset = i * (Math.PI / 3.0) + rad
                val pCos = kotlin.math.cos(angleOffset).toFloat()

                for (lat in -3..3) {
                    val latAngle = lat * (Math.PI / 7.0)
                    val y = cy + radius * kotlin.math.sin(latAngle).toFloat() * 0.88f
                    val x = cx + radius * kotlin.math.cos(latAngle).toFloat() * pCos
                    val alpha = (0.35f + 0.65f * ((pCos + 1f) / 2f)).coerceIn(0.15f, 1f)
                    val dotSize = (2f + 2f * ((pCos + 1f) / 2f)) * density

                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = dotSize,
                        center = androidx.compose.ui.geometry.Offset(x, y)
                    )
                }
            }

            for (j in 0 until 12) {
                val theta = j * (2.0 * Math.PI / 12.0) + rad
                val x = cx + radius * kotlin.math.cos(theta).toFloat()
                val z = kotlin.math.sin(theta).toFloat()
                val y = cy + z * radius * 0.28f
                val alpha = (0.4f + 0.6f * ((z + 1f) / 2f)).coerceIn(0.2f, 1f)
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = (2.2f + 1.8f * ((z + 1f) / 2f)) * density,
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            }
        }
    }
}

@Composable
fun TelemetryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = CyberTextMuted)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = CyberPrimary)
    }
}

fun formatTimer(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format("%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

fun getFlagEmoji(countryCode: String): String {
    if (countryCode.length != 2) return "🌐"
    val codePoints = countryCode.uppercase().toCharArray().map {
        it.code + 127397
    }
    return String(codePoints.toIntArray(), 0, codePoints.size)
}
