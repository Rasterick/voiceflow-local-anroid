package com.voiceflow.local.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    secondary = RadiantViolet,
    onSecondary = OnRadiantViolet,
    secondaryContainer = RadiantVioletContainer,
    tertiary = EmeraldEdge,
    onTertiary = OnEmerald,
    tertiaryContainer = EmeraldContainer,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = BorderOutline,
    error = ErrorRed,
    errorContainer = ErrorContainer
)

@Composable
fun WriteMyWordsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Defaulting to Dark Obsidian for battery efficiency and aesthetic fidelity
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
