package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults.colors
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ads.AdConsentState
import com.example.ads.AdLoadState
import com.example.ads.RewardedAdManager
import com.example.model.DnsProvider
import com.example.model.PermanentPermissionsStatus
import com.example.model.VpnProtocol
import com.example.model.VpnSettings
import com.example.ui.theme.CyberBackground
import com.example.ui.theme.CyberError
import com.example.ui.theme.CyberOutline
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSecondary
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMain
import com.example.ui.theme.CyberTextMuted

@Composable
fun SettingsScreen(
    settings: VpnSettings,
    permissionsStatus: PermanentPermissionsStatus = PermanentPermissionsStatus(),
    adConsentState: AdConsentState = AdConsentState(),
    adLoadState: AdLoadState = AdLoadState.Idle,
    isSdkInitialized: Boolean = false,
    onSettingsChanged: (VpnSettings) -> Unit,
    onOpenBatterySettings: () -> Unit = {},
    onRequestVpnPermission: () -> Unit = {},
    onUpdateAdConsent: (Boolean) -> Unit = {},
    onTestLoadAd: () -> Unit = {},
    onTestShowAd: () -> Unit = {}
) {
    val context = LocalContext.current
    var showPrivacyModal by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 104.dp)
    ) {
        Text(
            text = "DEFINIÇÕES",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextMain,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Segurança, protocolo de túnel e DNS sem rastreamento",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextMuted
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Kill Switch Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Kill Switch Estrito",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberTextMain
                )
                Text(
                    text = "Bloqueia qualquer saída fora do proxy (desative para jogos em nós só TCP)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextMuted
                )
            }
            Switch(
                checked = settings.killSwitchEnabled,
                onCheckedChange = { onSettingsChanged(settings.copy(killSwitchEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CyberSecondary,
                    checkedTrackColor = CyberPrimary.copy(alpha = 0.4f)
                ),
                modifier = Modifier.testTag("kill_switch_toggle")
            )
        }

        HorizontalDivider(color = CyberOutline, thickness = 1.dp)

        // Auto Connect Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Conectar ao Iniciar",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberTextMain
                )
                Text(
                    text = "Ativa o túnel automaticamente ao abrir o aplicativo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextMuted
                )
            }
            Switch(
                checked = settings.autoConnectOnStart,
                onCheckedChange = { onSettingsChanged(settings.copy(autoConnectOnStart = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CyberSecondary,
                    checkedTrackColor = CyberPrimary.copy(alpha = 0.4f)
                ),
                modifier = Modifier.testTag("auto_connect_toggle")
            )
        }

        HorizontalDivider(color = CyberOutline, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        // Permanent Mode Permissions Card
        val tunnelAndBgGranted = permissionsStatus.hasVpnPermission && permissionsStatus.isBatteryUnrestricted
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CyberSurfaceVariant,
            border = BorderStroke(
                1.dp,
                if (tunnelAndBgGranted) CyberSecondary.copy(alpha = 0.45f) else CyberPrimary.copy(alpha = 0.45f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Modo Permanente",
                        tint = if (tunnelAndBgGranted) CyberSecondary else CyberPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Permissões • Ligação Permanente",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextMain
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Permite que a VPN permaneça sempre ativa em segundo plano, sem fechar sozinha.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextMuted
                )
                Spacer(modifier = Modifier.height(12.dp))

                PermissionActionRow(
                    title = "Autorização do Túnel VPN",
                    subtitle = "Permite reconexão automática do túnel",
                    granted = permissionsStatus.hasVpnPermission,
                    actionLabel = "Autorizar",
                    testTag = "perm_vpn_button",
                    onClick = onRequestVpnPermission
                )
                Spacer(modifier = Modifier.height(8.dp))
                PermissionActionRow(
                    title = "Segundo Plano Irrestrito",
                    subtitle = "Impede o Android de encerrar a VPN por bateria",
                    granted = permissionsStatus.isBatteryUnrestricted,
                    actionLabel = "Configurar",
                    testTag = "perm_battery_button",
                    onClick = onOpenBatterySettings
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = CyberOutline, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        // Protocol Selector
        Text(
            text = "PROTOCOLO DE TÚNEL",
            style = MaterialTheme.typography.labelLarge,
            color = CyberPrimary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        VpnProtocol.values().forEach { protocol ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsChanged(settings.copy(selectedProtocol = protocol)) }
                    .padding(vertical = 6.dp)
                    .testTag("protocol_option_${protocol.name}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.selectedProtocol == protocol,
                    onClick = { onSettingsChanged(settings.copy(selectedProtocol = protocol)) },
                    colors = colors(selectedColor = CyberPrimary, unselectedColor = CyberTextMuted)
                )
                Text(
                    text = protocol.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (settings.selectedProtocol == protocol) CyberTextMain else CyberTextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = CyberOutline, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        // DNS Selector
        Text(
            text = "SERVIDOR DNS CRIPTOGRAFADO",
            style = MaterialTheme.typography.labelLarge,
            color = CyberPrimary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        DnsProvider.values().forEach { dns ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsChanged(settings.copy(dnsProvider = dns)) }
                    .padding(vertical = 6.dp)
                    .testTag("dns_option_${dns.name}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.dnsProvider == dns,
                    onClick = { onSettingsChanged(settings.copy(dnsProvider = dns)) },
                    colors = colors(selectedColor = CyberSecondary, unselectedColor = CyberTextMuted)
                )
                Text(
                    text = dns.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (settings.dnsProvider == dns) CyberTextMain else CyberTextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = CyberOutline, thickness = 1.dp)
        Spacer(modifier = Modifier.height(20.dp))

        // Privacy Policy & Terms Section
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CyberSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Lock, contentDescription = "Privacidade", tint = CyberSecondary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Política de Privacidade e Uso",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextMain
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "O Zerox VPN opera com política estrita de zero registro (zero logs). Todo o tráfego de dados, jogos e navegação é criptografado por túneis seguros SOCKS5/HTTPS para garantir total privacidade e autenticação correta em servidores internacionais.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Google AdMob Diagnostics & GDPR/CCPA Consent Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CyberSurfaceVariant,
            border = BorderStroke(
                1.dp,
                when (adLoadState) {
                    is AdLoadState.Ready -> CyberSecondary.copy(alpha = 0.5f)
                    is AdLoadState.Error -> CyberError.copy(alpha = 0.5f)
                    else -> CyberPrimary.copy(alpha = 0.3f)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Google AdMob",
                        tint = if (isSdkInitialized) CyberSecondary else CyberPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Anúncios Google AdMob & Consentimento",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextMain
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "App ID: ${RewardedAdManager.ADMOB_APP_ID}\n" +
                        "Bloco Recompensado: ${RewardedAdManager.ADMOB_REWARDED_AD_UNIT_ID}\n" +
                        "Pacote: ${BuildConfig.APPLICATION_ID}\n" +
                        "SDK: ${if (isSdkInitialized) "Inicializado (Google AdMob)" else "Aguardando inicialização"}\n" +
                        "Estado do anúncio: ${
                            when (adLoadState) {
                                AdLoadState.Idle -> "Ocioso (pronto para carregar)"
                                is AdLoadState.Loading -> "Carregando (${adLoadState.modeLabel})..."
                                is AdLoadState.Ready -> "Carregado e pronto (${adLoadState.modeLabel})"
                                is AdLoadState.Error -> "Falha: ${adLoadState.detail}"
                            }
                        }",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (adLoadState is AdLoadState.Error) CyberError else CyberTextMuted
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Anúncios Personalizados (GDPR/CCPA)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = CyberTextMain
                        )
                        Text(
                            text = if (adConsentState.personalizedAdsGranted) {
                                "Consentimento ativo (anúncios personalizados)"
                            } else {
                                "Apenas anúncios não personalizados (npa=1)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextMuted
                        )
                    }
                    Switch(
                        checked = adConsentState.personalizedAdsGranted,
                        onCheckedChange = { onUpdateAdConsent(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberSecondary,
                            checkedTrackColor = CyberPrimary.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("ad_consent_toggle")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestLoadAd,
                        enabled = adLoadState !is AdLoadState.Loading,
                        border = BorderStroke(1.dp, CyberPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("test_load_ad_button")
                    ) {
                        Text(
                            text = "1. Carregar",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = onTestShowAd,
                        enabled = adLoadState is AdLoadState.Ready,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberSecondary,
                            disabledContainerColor = CyberOutline
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("test_show_ad_button")
                    ) {
                        Text(
                            text = "2. Exibir",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A ligação VPN só é iniciada após exibir e fechar um anúncio válido do Google AdMob. Ao publicar na Google Play Store, basta substituir o seu App ID e Bloco de Anúncios de produção.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Creator Credits Section (Zerox & TikTok)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CyberSurfaceVariant,
            border = BorderStroke(1.dp, CyberPrimary.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "Criador", tint = CyberPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Sobre o Criador • Zerox",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextMain
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Aplicativo desenvolvido por Zerox, especialista em otimização de redes, túneis de alta velocidade e bypass de servidores para jogos e aplicativos em qualquer parte do mundo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        openExternalUrl(
                            context = context,
                            primaryUrl = "https://www.tiktok.com/@.zerox.00?is_from_webapp=1&sender_device=pc",
                            fallbackUrl = null,
                            errorMsg = "Nenhum navegador encontrado para abrir o TikTok."
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("tiktok_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "TikTok",
                        tint = CyberBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Seguir Zerox no TikTok",
                        style = MaterialTheme.typography.titleSmall,
                        color = CyberBackground,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun openExternalUrl(
    context: android.content.Context,
    primaryUrl: String,
    fallbackUrl: String?,
    errorMsg: String
) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(primaryUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        if (fallbackUrl != null) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                return
            } catch (_: Exception) {
            }
        }
        android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PermissionActionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CyberTextMain
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CyberTextMuted
            )
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Ativo",
                    tint = CyberSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Ativo",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                border = BorderStroke(1.dp, CyberPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag(testTag)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
