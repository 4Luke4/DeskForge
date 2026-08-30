package com.deskforge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GraphiteColorScheme = darkColorScheme(
    primary = Color(0xFFFF9B52),
    onPrimary = Color(0xFF351000),
    primaryContainer = Color(0xFF5B2A0D),
    onPrimaryContainer = Color(0xFFFFDBC5),
    secondary = Color(0xFFB8C7D9),
    onSecondary = Color(0xFF22313F),
    secondaryContainer = Color(0xFF394957),
    onSecondaryContainer = Color(0xFFD4E4F6),
    background = Color(0xFF101317),
    onBackground = Color(0xFFE2E7EC),
    surface = Color(0xFF171B20),
    onSurface = Color(0xFFE2E7EC),
    surfaceVariant = Color(0xFF30363D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun DeskForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GraphiteColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
