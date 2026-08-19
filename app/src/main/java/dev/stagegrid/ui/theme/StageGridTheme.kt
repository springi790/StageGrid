package dev.stagegrid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StageGridDarkColors = darkColorScheme(
    primary = Color(0xFF79A7FF),
    secondary = Color(0xFF58D6B6),
    tertiary = Color(0xFFC59BFF),
    background = Color(0xFF0B0E13),
    surface = Color(0xFF11161E),
    surfaceVariant = Color(0xFF1A202B),
    error = Color(0xFFFF6B78),
)

@Composable
fun StageGridTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StageGridDarkColors, content = content)
}
