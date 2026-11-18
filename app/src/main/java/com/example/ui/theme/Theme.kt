package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.model.ThemeMode

private val BpbDarkColorScheme = darkColorScheme(
    primary = BpbCyan,
    onPrimary = Color(0xFF04202C),
    primaryContainer = BpbCyanContainer,
    onPrimaryContainer = BpbCyanBright,
    secondary = BpbEmerald,
    onSecondary = Color(0xFF032319),
    secondaryContainer = BpbEmeraldContainer,
    onSecondaryContainer = Color(0xFF6EE7B7),
    tertiary = BpbAmber,
    onTertiary = Color(0xFF281A00),
    tertiaryContainer = BpbAmberContainer,
    onTertiaryContainer = Color(0xFFFCD34D),
    background = BpbMidnightBg,
    onBackground = BpbTextPrimary,
    surface = BpbSurface,
    onSurface = BpbTextPrimary,
    surfaceVariant = BpbSurfaceElevated,
    onSurfaceVariant = BpbTextSecondary,
    surfaceContainerLowest = BpbMidnightBg,
    surfaceContainerLow = BpbSurface,
    surfaceContainer = BpbSurfaceElevated,
    surfaceContainerHigh = BpbSurfaceHighest,
    outline = BpbBorderSubtle,
    outlineVariant = Color(0xFF1A2D4D),
    error = BpbCoral,
    onError = Color.White,
    errorContainer = BpbCoralContainer,
    onErrorContainer = Color(0xFFFCA5A5)
)

private val BpbLightColorScheme = lightColorScheme(
    primary = BpbLightPrimary,
    onPrimary = Color.White,
    primaryContainer = BpbLightPrimaryContainer,
    onPrimaryContainer = Color(0xFF075985),
    secondary = BpbLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = BpbLightSecondaryContainer,
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF92400E),
    background = BpbLightBg,
    onBackground = BpbLightTextPrimary,
    surface = BpbLightSurface,
    onSurface = BpbLightTextPrimary,
    surfaceVariant = BpbLightSurfaceVariant,
    onSurfaceVariant = BpbLightTextSecondary,
    outline = BpbLightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

val BpbShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun BpbVpnTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) BpbDarkColorScheme else BpbLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDarkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BpbTypography,
        shapes = BpbShapes,
        content = content
    )
}
