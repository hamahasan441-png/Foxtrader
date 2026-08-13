package com.foxtrader.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================================
// FOX DESIGN LANGUAGE — Spacing, radius, elevation
// 4dp base scale. Screens must use these tokens instead of one-off padding.
// ============================================================================

@Immutable
data class FoxSpacing(
    val xxxs: Dp = 2.dp,
    val xxs: Dp = 4.dp,
    val xs: Dp = 6.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val huge: Dp = 40.dp,
    val screenHorizontal: Dp = 16.dp,
    val section: Dp = 20.dp,
    val card: Dp = 14.dp,
    val touch: Dp = 44.dp,
    val iconButton: Dp = 40.dp,
    val topBar: Dp = 56.dp,
    val bottomBar: Dp = 64.dp,
    val metric: Dp = 88.dp,
) {
    val screenInsets: PaddingValues
        get() = PaddingValues(horizontal = screenHorizontal, vertical = md)
}

@Immutable
data class FoxShapes(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val pill: Dp = 100.dp,
)

@Immutable
data class FoxElevation(
    val none: Dp = 0.dp,
    val low: Dp = 1.dp,
    val mid: Dp = 3.dp,
    val high: Dp = 8.dp,
)

val LocalFoxSpacing = staticCompositionLocalOf { FoxSpacing() }
val LocalFoxShapes = staticCompositionLocalOf { FoxShapes() }
val LocalFoxElevation = staticCompositionLocalOf { FoxElevation() }
