package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.AppItem
import com.example.model.ConnectionDiagnostics
import com.example.model.ConnectionState
import com.example.model.SplitTunnelMode
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
fun SplitTunnelDialog(
    installedApps: List<AppItem>,
    isLoadingApps: Boolean,
    selectedMode: SplitTunnelMode,
    selectedApps: Set<String>,
    onModeChanged: (SplitTunnelMode) -> Unit,
    onToggleApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = CyberSurface,
            border = BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AltRoute,
                            contentDescription = null,
                            tint = CyberPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "TÚNEL DIVIDIDO",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextMain,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "${selectedApps.size} aplicações selecionadas",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberPrimary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_split_dialog")) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = CyberTextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode Selector
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CyberSurfaceVariant.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, CyberOutline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        SplitTunnelMode.values().forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onModeChanged(mode) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedMode == mode,
                                    onClick = { onModeChanged(mode) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = CyberSecondary,
                                        unselectedColor = CyberTextMuted
                                    )
                                )
                                Column(modifier = Modifier.padding(start = 6.dp)) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selectedMode == mode) CyberTextMain else CyberTextMuted,
                                        fontWeight = if (selectedMode == mode) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyberTextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Procurar aplicação...", color = CyberTextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyberPrimary) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberPrimary,
                        unfocusedBorderColor = CyberOutline,
                        focusedContainerColor = CyberSurfaceVariant,
                        unfocusedContainerColor = CyberSurfaceVariant,
                        focusedTextColor = CyberTextMain,
                        unfocusedTextColor = CyberTextMain
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("split_search_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // App list
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when {
                        isLoadingApps -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = CyberPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Listando aplicações instaladas...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyberTextMuted
                                )
                            }
                        }
                        filteredApps.isEmpty() -> {
                            Text(
                                text = if (searchQuery.isBlank()) "Nenhuma aplicação encontrada" else "Nenhuma app coincide com '$searchQuery'",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberTextMuted,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        else -> {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                items(filteredApps, key = { it.packageName }) { app ->
                                    val isChecked = selectedApps.contains(app.packageName)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isChecked) CyberSurfaceVariant else CyberSurface,
                                        border = if (isChecked) BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.4f)) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onToggleApp(app.packageName) }
                                            .testTag("app_item_${app.packageName}")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { onToggleApp(app.packageName) },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = CyberSecondary,
                                                    uncheckedColor = CyberTextMuted
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = app.label,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = CyberTextMain,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = app.packageName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = CyberTextMuted,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_split_tunnel_button")
                ) {
                    Text(
                        text = "CONCLUÍDO",
                        color = CyberBackground,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun QuickDiagnosticsDialog(
    diagnostics: ConnectionDiagnostics,
    isTestingRoute: Boolean,
    onRunRouteTest: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = CyberSurface,
            border = BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = CyberSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "DIAGNÓSTICO DE REDE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberTextMain,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_diag_dialog")) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = CyberTextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Diagnostics Summary Table
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = CyberSurfaceVariant.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, CyberOutline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagItemRow(
                            label = "Estado da Ligação",
                            value = when (diagnostics.connectionState) {
                                is ConnectionState.Connected -> "Protegido (Ativo)"
                                is ConnectionState.Paused -> "Em Pausa"
                                is ConnectionState.Connecting -> "A Conectar..."
                                is ConnectionState.Preparing -> "A Preparar..."
                                is ConnectionState.Error -> "Erro de Túnel"
                                else -> "Desconectado"
                            },
                            highlightColor = when (diagnostics.connectionState) {
                                is ConnectionState.Connected -> CyberSecondary
                                is ConnectionState.Paused -> CyberPrimary
                                is ConnectionState.Error -> CyberError
                                else -> CyberTextMuted
                            }
                        )
                        HorizontalDivider(color = CyberOutline.copy(alpha = 0.3f), thickness = 0.5.dp)

                        DiagItemRow(
                            label = "Rede Física",
                            value = diagnostics.networkType,
                            highlightColor = CyberTextMain
                        )
                        HorizontalDivider(color = CyberOutline.copy(alpha = 0.3f), thickness = 0.5.dp)

                        DiagItemRow(
                            label = "IP Virtual (Interface TUN)",
                            value = diagnostics.virtualIp,
                            highlightColor = CyberPrimary
                        )
                        HorizontalDivider(color = CyberOutline.copy(alpha = 0.3f), thickness = 0.5.dp)

                        DiagItemRow(
                            label = "IP de Saída / Relay",
                            value = diagnostics.exitIp,
                            highlightColor = CyberSecondary
                        )
                        HorizontalDivider(color = CyberOutline.copy(alpha = 0.3f), thickness = 0.5.dp)

                        DiagItemRow(
                            label = "Protocolo Ativo",
                            value = diagnostics.protocolName,
                            highlightColor = CyberTextMain
                        )
                        HorizontalDivider(color = CyberOutline.copy(alpha = 0.3f), thickness = 0.5.dp)

                        DiagItemRow(
                            label = "DNS Seguro",
                            value = diagnostics.dnsInfo,
                            highlightColor = CyberPrimary
                        )
                        HorizontalDivider(color = CyberOutline.copy(alpha = 0.3f), thickness = 0.5.dp)

                        DiagItemRow(
                            label = "Servidor Atual",
                            value = "${diagnostics.activeServerName} (${diagnostics.activeServerLocation})",
                            highlightColor = CyberTextMain
                        )
                        if (diagnostics.pingMs != null) {
                            HorizontalDivider(color = CyberOutline.copy(alpha = 0.3f), thickness = 0.5.dp)
                            DiagItemRow(
                                label = "Latência Alvo",
                                value = "${diagnostics.pingMs} ms",
                                highlightColor = if (diagnostics.pingMs < 100) CyberSecondary else CyberPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Route Test Result Banner
                if (diagnostics.routeTestResult != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (diagnostics.routeTestResult.startsWith("✓")) CyberSecondary.copy(alpha = 0.15f) else CyberError.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (diagnostics.routeTestResult.startsWith("✓")) CyberSecondary else CyberError),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = diagnostics.routeTestResult,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (diagnostics.routeTestResult.startsWith("✓")) CyberSecondary else CyberError,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Action Button: Test Route
                Button(
                    onClick = onRunRouteTest,
                    enabled = !isTestingRoute,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSecondary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("run_route_test_button")
                ) {
                    if (isTestingRoute) {
                        CircularProgressIndicator(
                            color = CyberBackground,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TESTANDO ROTA...",
                            color = CyberBackground,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = CyberBackground,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TESTAR ROTA AGORA",
                            color = CyberBackground,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagItemRow(
    label: String,
    value: String,
    highlightColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = CyberTextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = highlightColor
        )
    }
}
