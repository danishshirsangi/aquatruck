package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanWater,
    onPrimary = Color(0xFF030D1A),
    primaryContainer = Color(0xFF152A47),
    onPrimaryContainer = AquaHighlight,
    secondary = AquaHighlight,
    onSecondary = Color(0xFF030D1A),
    tertiary = TealAccent,
    background = Color(0xFF0B132B),
    surface = Color(0xFF16233B),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E304E),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF334B6E),
    outlineVariant = Color(0xFF1E2A3E)
)

private val LightColorScheme = lightColorScheme(
    primary = OceanNavyPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = OceanNavyPrimary,
    secondary = TealAccent,
    onSecondary = Color.White,
    tertiary = CyanWater,
    background = SurfaceLight,
    surface = CardSurfaceLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = TextSecondaryLight
)

@Composable
fun AquaTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
