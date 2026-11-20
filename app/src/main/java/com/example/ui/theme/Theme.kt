package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyberPrimary,
    onPrimary = CyberBackground,
    primaryContainer = CyberSurfaceVariant,
    onPrimaryContainer = CyberPrimary,
    secondary = CyberSecondary,
    onSecondary = CyberBackground,
    background = CyberBackground,
    onBackground = CyberTextMain,
    surface = CyberSurface,
    onSurface = CyberTextMain,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = CyberTextMuted,
    error = CyberError,
    onError = CyberBackground,
    outline = CyberOutline
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force stunning dark theme for Cyber Blue VPN
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
