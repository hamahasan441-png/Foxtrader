package com.foxtrader.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================================
// FOX DESIGN SYSTEM — Material3 theme
// Maps Fox tokens onto Material3 color roles. Dark theme is the default
// institutional experience; light theme is available.
// ============================================================================

private val FoxDarkColors = darkColorScheme(
    primary = FoxAmber50,
    onPrimary = FoxNeutral0,
    primaryContainer = FoxAmber40,
    onPrimaryContainer = FoxAmber70,
    secondary = FoxInfo,
    onSecondary = FoxNeutral90,
    background = FoxNeutral0,
    onBackground = FoxNeutral90,
    surface = FoxNeutral5,
    onSurface = FoxNeutral90,
    surfaceVariant = FoxNeutral10,
    onSurfaceVariant = FoxNeutral60,
    surfaceContainer = FoxNeutral10,
    surfaceContainerHigh = FoxNeutral15,
    outline = FoxNeutral30,
    outlineVariant = FoxNeutral20,
    error = FoxError,
    onError = FoxNeutral90,
)

private val FoxLightColors = lightColorScheme(
    primary = FoxAmberLight,
    onPrimary = FoxLightSurface,
    background = FoxLightBg,
    onBackground = FoxLightText,
    surface = FoxLightSurface,
    onSurface = FoxLightText,
    surfaceVariant = FoxLightSurfaceRaised,
    onSurfaceVariant = FoxLightTextSecondary,
    outline = FoxLightBorder,
    error = FoxError,
)

@Composable
fun FoxTraderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) FoxDarkColors else FoxLightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FoxTypography,
        content = content,
    )
}
