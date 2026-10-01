package com.deckrec.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Booth-friendly palette: near-black so the phone does not light up the DJ's face, one hot accent
 * for the transport, and meter colours that map to the same green/amber/red a mixer uses.
 */
object DeckColors {
    val Background = Color(0xFF07070A)
    val Surface = Color(0xFF111119)
    val SurfaceRaised = Color(0xFF1A1A24)
    val Outline = Color(0xFF2C2C3A)
    val Accent = Color(0xFFFF8A00)
    val AccentMuted = Color(0xFF7A4300)
    val Record = Color(0xFFFF3B30)
    val MeterLow = Color(0xFF32D74B)
    val MeterMid = Color(0xFFFFD60A)
    val MeterHigh = Color(0xFFFF9F0A)
    val MeterClip = Color(0xFFFF453A)
    val TextPrimary = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFF9A9AAE)
}

private val DarkScheme = darkColorScheme(
    primary = DeckColors.Accent,
    onPrimary = Color.Black,
    secondary = DeckColors.MeterLow,
    onSecondary = Color.Black,
    background = DeckColors.Background,
    onBackground = DeckColors.TextPrimary,
    surface = DeckColors.Surface,
    onSurface = DeckColors.TextPrimary,
    surfaceVariant = DeckColors.SurfaceRaised,
    onSurfaceVariant = DeckColors.TextSecondary,
    outline = DeckColors.Outline,
    error = DeckColors.MeterClip,
)

private val LightScheme = lightColorScheme(
    primary = DeckColors.Accent,
    background = Color(0xFFF7F7FA),
    surface = Color.White,
)

private val DeckTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 52.sp,
        letterSpacing = (-1).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun DeckRecTheme(
    // Dark is the default regardless of the system setting: this screen sits in a dark booth.
    useDarkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkScheme else LightScheme,
        typography = DeckTypography,
        content = content,
    )
}
