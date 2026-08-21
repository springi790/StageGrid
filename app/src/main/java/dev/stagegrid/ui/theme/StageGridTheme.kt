package dev.stagegrid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * StageGrid Neon Slate.
 *
 * The palette is intentionally closer to modern live-audio hardware than a generic Android app:
 * deep neutral surfaces, cyan for primary actions, violet for special/live states and mint for
 * healthy/ready feedback. Keep destructive actions red and reserve amber for warnings/queued state.
 */
object StageGridColors {
    val Canvas = Color(0xFF070A0F)
    val Surface = Color(0xFF0E131C)
    val SurfaceRaised = Color(0xFF141B26)
    val SurfaceBright = Color(0xFF1B2533)
    val Cyan = Color(0xFF55DDF5)
    val CyanContainer = Color(0xFF123641)
    val Violet = Color(0xFFA88BFF)
    val VioletContainer = Color(0xFF2A2147)
    val Mint = Color(0xFF75E6B5)
    val Amber = Color(0xFFFFC66D)
    val Danger = Color(0xFFFF6B7D)
    val Text = Color(0xFFF4F7FB)
    val TextMuted = Color(0xFFA5B0C0)
    val Outline = Color(0xFF293546)
    val OutlineStrong = Color(0xFF3A4B61)
}

private val StageGridDarkColors = darkColorScheme(
    primary = StageGridColors.Cyan,
    onPrimary = Color(0xFF002A32),
    primaryContainer = StageGridColors.CyanContainer,
    onPrimaryContainer = Color(0xFFC8F6FF),
    secondary = StageGridColors.Mint,
    onSecondary = Color(0xFF053526),
    secondaryContainer = Color(0xFF15382E),
    onSecondaryContainer = Color(0xFFC5F6E1),
    tertiary = StageGridColors.Violet,
    onTertiary = Color(0xFF241548),
    tertiaryContainer = StageGridColors.VioletContainer,
    onTertiaryContainer = Color(0xFFE8DFFF),
    background = StageGridColors.Canvas,
    onBackground = StageGridColors.Text,
    surface = StageGridColors.Surface,
    onSurface = StageGridColors.Text,
    surfaceVariant = StageGridColors.SurfaceRaised,
    onSurfaceVariant = StageGridColors.TextMuted,
    outline = StageGridColors.Outline,
    outlineVariant = StageGridColors.OutlineStrong,
    error = StageGridColors.Danger,
    onError = Color(0xFF3D000A),
    errorContainer = Color(0xFF4D1720),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val StageGridShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val StageGridTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.15.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.25.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
    ),
)

@Composable
fun StageGridTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StageGridDarkColors,
        typography = StageGridTypography,
        shapes = StageGridShapes,
        content = content,
    )
}
