package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Continent
import com.example.model.VpnServer
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSecondary
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMain
import com.example.ui.theme.CyberTextMuted

@Composable
fun RegionsScreen(
    servers: List<VpnServer>,
    isLoading: Boolean,
    scanStatus: String,
    scanProgress: Float,
    selectedServer: VpnServer?,
    selectedContinent: Continent?,
    searchQuery: String,
    onServerSelected: (VpnServer) -> Unit,
    onConnectSelectedClick: (VpnServer) -> Unit,
    onRefreshClick: () -> Unit,
    onContinentSelected: (Continent?) -> Unit,
    onSearchQueryChanged: (String) -> Unit
) {
    val filteredServers = servers.filter { server ->
        val matchesContinent = selectedContinent == null || server.continent == selectedContinent
        val matchesSearch = searchQuery.isBlank() ||
            server.countryName.contains(searchQuery, ignoreCase = true) ||
            server.city.contains(searchQuery, ignoreCase = true) ||
            server.countryCode.contains(searchQuery, ignoreCase = true)
        matchesContinent && matchesSearch
    }.sortedBy { it.pingMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "REDE GLOBAL",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextMain,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = scanStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isLoading) CyberPrimary else CyberTextMuted
                )
            }
            IconButton(
                onClick = onRefreshClick,
                enabled = !isLoading,
                modifier = Modifier.testTag("refresh_servers_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Atualizar servidores",
                    tint = if (isLoading) CyberTextMuted else CyberPrimary
                )
            }
        }

        AnimatedVisibility(visible = isLoading) {
            if (scanProgress > 0f) {
                LinearProgressIndicator(
                    progress = { scanProgress },
                    color = CyberPrimary,
                    trackColor = CyberSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp)
                )
            } else {
                LinearProgressIndicator(
                    color = CyberPrimary,
                    trackColor = CyberSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = { Text("Buscar país verificado...", color = CyberTextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar", tint = CyberPrimary) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberPrimary,
                unfocusedBorderColor = CyberSurfaceVariant,
                focusedContainerColor = CyberSurfaceVariant,
                unfocusedContainerColor = CyberSurfaceVariant,
                focusedTextColor = CyberTextMain,
                unfocusedTextColor = CyberTextMain
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_servers_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedContinent == null,
                    onClick = { onContinentSelected(null) },
                    label = { Text("Todos") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberPrimary,
                        selectedLabelColor = CyberBackground,
                        containerColor = CyberSurfaceVariant,
                        labelColor = CyberTextMain
                    )
                )
            }
            items(Continent.values()) { continent ->
                FilterChip(
                    selected = selectedContinent == continent,
                    onClick = { onContinentSelected(continent) },
                    label = { Text(continent.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberPrimary,
                        selectedLabelColor = CyberBackground,
                        containerColor = CyberSurfaceVariant,
                        labelColor = CyberTextMain
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                servers.isEmpty() && isLoading -> {
                    ScanningEmptyState(scanStatus = scanStatus)
                }
                servers.isEmpty() && !isLoading -> {
                    NoServersFoundState(
                        title = "Nenhum servidor ativo encontrado",
                        subtitle = "Sua rede não alcançou nenhum nó gratuito neste ciclo.",
                        buttonText = "TENTAR NOVAMENTE",
                        onAction = onRefreshClick
                    )
                }
                filteredServers.isEmpty() -> {
                    NoServersFoundState(
                        title = "Nenhuma região neste filtro",
                        subtitle = "Ainda não há servidores verificados para esse filtro no momento.",
                        buttonText = "MOSTRAR TODOS",
                        onAction = {
                            onContinentSelected(null)
                            onSearchQueryChanged("")
                        }
                    )
                }
                else -> {
                    // Tag-free clean Country Cards (ONLY verified connectable servers)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            bottom = if (selectedServer != null) 8.dp else 96.dp
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredServers, key = { it.id }) { server ->
                            val isSelected = selectedServer?.id == server.id
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) CyberSurfaceVariant else MaterialTheme.colorScheme.surface,
                                border = if (isSelected) BorderStroke(1.dp, CyberPrimary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onServerSelected(server) }
                                    .testTag("server_item_${server.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = getFlagEmoji(server.countryCode),
                                            fontSize = 26.sp,
                                            modifier = Modifier.padding(end = 14.dp)
                                        )
                                        Column {
                                            Text(
                                                text = server.countryName,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = CyberTextMain,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${server.city} • ${server.continent.displayName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = CyberTextMuted
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${server.pingMs} ms",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (server.pingMs < 120) CyberSecondary else CyberPrimary
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Selecionado",
                                                tint = CyberSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Functional Connect Button for Selected Region — cleanly positioned ABOVE the floating navigation bar
        selectedServer?.let { server ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 86.dp)
            ) {
                Button(
                    onClick = { onConnectSelectedClick(server) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("connect_selected_region_button")
                ) {
                    Text(
                        text = "CONECTAR A ${server.countryName.uppercase()}",
                        style = MaterialTheme.typography.titleMedium,
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
private fun ScanningEmptyState(scanStatus: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(CyberPrimary.copy(alpha = 0.14f))
            )
            CircularProgressIndicator(
                color = CyberPrimary,
                trackColor = CyberSurfaceVariant,
                strokeWidth = 3.dp,
                modifier = Modifier.size(64.dp)
            )
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = "Buscando regiões",
                tint = CyberSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "Buscando servidores reais...",
            style = MaterialTheme.typography.titleMedium,
            color = CyberTextMain,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = scanStatus,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NoServersFoundState(
    title: String,
    subtitle: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.WifiFind,
            contentDescription = title,
            tint = CyberPrimary,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = CyberTextMain,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextMuted,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onAction,
            border = BorderStroke(1.dp, CyberPrimary),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("retry_scan_button")
        ) {
            Text(text = buttonText, color = CyberPrimary, fontWeight = FontWeight.Bold)
        }
    }
}
