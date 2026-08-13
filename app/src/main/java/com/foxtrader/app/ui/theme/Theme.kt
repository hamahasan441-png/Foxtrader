package com.foxtrader.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================================
// FOX DESIGN LANGUAGE — Material3 + semantic token providers
// Dark (Ember Ink) is the institutional default. Light is a warm paper desk.
// ============================================================================

private val ColorSurfaceStrongLight = androidx.compose.ui.graphics.Color(0xFFE4DACA)

private val FoxDarkColors = darkColorScheme(
    primary = FoxAmber50,
    onPrimary = FoxOnAccent,
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
    primaryContainer = FoxAmber40,
    onPrimaryContainer = FoxAmber70,
    secondary = FoxInfo,
    onSecondary = FoxLightText,
    background = FoxLightBg,
    onBackground = FoxLightText,
    surface = FoxLightSurface,
    onSurface = FoxLightText,
    surfaceVariant = FoxLightSurfaceRaised,
    onSurfaceVariant = FoxLightTextSecondary,
    surfaceContainer = FoxLightSurfaceRaised,
    surfaceContainerHigh = ColorSurfaceStrongLight,
    outline = FoxLightBorder,
    outlineVariant = FoxLightBorder,
    error = FoxError,
    onError = FoxLightSurface,
)

private val ColorSurfaceStrongLight = androidx.compose.ui.graphics.Color(0xFFE4DACA)

object FoxTheme {
    val colors: FoxColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxColors.current

    val type: FoxTypeTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxType.current

    val spacing: FoxSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxSpacing.current

    val shapes: FoxShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxShapes.current

    val elevation: FoxElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxElevation.current

    val motion: FoxMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalFoxMotion.current
}

@Composable
fun FoxTraderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) FoxDarkColors else FoxLightColors
    val tokens = if (darkTheme) FoxDarkTokens else FoxLightTokens
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalFoxColors provides tokens,
        LocalFoxType provides FoxTypeTokensDefault,
        LocalFoxSpacing provides FoxSpacing(),
        LocalFoxShapes provides FoxShapes(),
        LocalFoxElevation provides FoxElevation(),
        LocalFoxMotion provides FoxMotion(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FoxTypography,
            content = content,
        )
    }
}
