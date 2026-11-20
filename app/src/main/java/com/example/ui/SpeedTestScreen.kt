package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SpeedTestResult
import com.example.model.VpnServer
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberOutline
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSecondary
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMain
import com.example.ui.theme.CyberTextMuted

@Composable
fun SpeedTestScreen(
    selectedServer: VpnServer?,
    speedTestResult: SpeedTestResult,
    onStartSpeedTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 104.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "TESTE DE VELOCIDADE",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberTextMain,
                letterSpacing = 1.5.sp
            )
            Text(
                text = selectedServer?.let { "Medindo conexão em tempo real (${it.countryName})" } ?: "Medindo conexão em tempo real",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextMuted
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Custom Canvas Arc Gauge
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(240.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 20.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                val arcSize = Size(diameter, diameter)

                drawArc(
                    color = CyberSurfaceVariant,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                val sweep = speedTestResult.progress * 270f
                drawArc(
                    brush = Brush.sweepGradient(listOf(CyberPrimary, CyberSecondary)),
                    startAngle = 135f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (speedTestResult.isTesting || speedTestResult.downloadMbps > 0)
                        String.format("%.1f", speedTestResult.downloadMbps)
                    else "--",
                    style = MaterialTheme.typography.displayLarge,
                    color = CyberPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Mbps (Download)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = speedTestResult.phase,
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Flat Hairline Metrics Strip (No card clutter)
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = CyberOutline, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryItem(label = "PING (RTT)", value = "${speedTestResult.pingMs} ms")
                VerticalDivider(modifier = Modifier.height(28.dp), color = CyberOutline)
                TelemetryItem(label = "JITTER", value = "${speedTestResult.jitterMs} ms")
                VerticalDivider(modifier = Modifier.height(28.dp), color = CyberOutline)
                TelemetryItem(label = "UPLOAD", value = "${speedTestResult.uploadMbps} Mbps")
            }
            HorizontalDivider(color = CyberOutline, thickness = 1.dp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartSpeedTest,
            enabled = !speedTestResult.isTesting,
            colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("start_speed_test_button")
        ) {
            Text(
                text = if (speedTestResult.isTesting) "TESTANDO..." else "INICIAR TESTE",
                style = MaterialTheme.typography.titleMedium,
                color = CyberBackground,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
    }
}
