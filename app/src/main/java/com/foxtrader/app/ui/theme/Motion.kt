package com.foxtrader.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

// ============================================================================
// FOX DESIGN LANGUAGE — Motion
// Short, purposeful transitions. Performance always wins over flourish.
// ============================================================================

@Immutable
data class FoxMotion(
    val instantMs: Int = 80,
    val fastMs: Int = 140,
    val mediumMs: Int = 220,
    val slowMs: Int = 320,
    val easing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
) {
    fun <T> fast() = tween<T>(durationMillis = fastMs, easing = easing)
    fun <T> medium() = tween<T>(durationMillis = mediumMs, easing = easing)
    fun <T> slow() = tween<T>(durationMillis = slowMs, easing = easing)
}

val LocalFoxMotion = staticCompositionLocalOf { FoxMotion() }
