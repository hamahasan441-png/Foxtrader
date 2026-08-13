package com.foxtrader.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================================================
// FOX DESIGN LANGUAGE — Ember Ink
//
// Warm ink canvas + fox ember accent. Deliberately not a cool-blue terminal
// and not a TradingView clone. Financial numbers stay high-contrast; trading
// direction always pairs color with a label or arrow.
// ============================================================================

// --- Neutrals (warm ink, not cool blue-grey) ---
val FoxNeutral0 = Color(0xFF090807)   // Background
val FoxNeutral5 = Color(0xFF110E0C)   // Surface
val FoxNeutral10 = Color(0xFF191512)  // Surface elevated / card
val FoxNeutral15 = Color(0xFF221C18)  // Surface strong
val FoxNeutral20 = Color(0xFF2C261F)  // Border subtle
val FoxNeutral30 = Color(0xFF3D342B)  // Border
val FoxNeutral40 = Color(0xFF5A4E42)  // Muted strong
val FoxNeutral60 = Color(0xFF9A9084)  // Text muted / secondary
val FoxNeutral80 = Color(0xFFD4CDC3)  // Body
val FoxNeutral90 = Color(0xFFF3EDE3)  // Text primary (warm ivory)

// --- Accent (Fox ember) ---
val FoxAmber40 = Color(0xFFC4841E)
val FoxAmber50 = Color(0xFFE39B2E)    // Primary accent
val FoxAmber60 = Color(0xFFF0B354)    // Hover
val FoxAmber70 = Color(0xFFF6D08A)    // Light accent
val FoxOnAccent = Color(0xFF1A1206)

// --- Trading semantics (never used as the only state cue) ---
val FoxBullish = Color(0xFF1DB87A)
val FoxBullishText = Color(0xFF3DDB98)
val FoxBearish = Color(0xFFE24B55)
val FoxBearishText = Color(0xFFFF6E77)
val FoxMarketNeutral = Color(0xFF8A8378)

// --- System semantics ---
val FoxInfo = Color(0xFF4E9FD6)
val FoxWarning = Color(0xFFE39B2E)
val FoxError = Color(0xFFE24B55)
val FoxSuccess = Color(0xFF1DB87A)
val FoxAi = Color(0xFFC9A06A)

// --- Light theme surfaces (warm paper desk) ---
val FoxLightBg = Color(0xFFF6F1E8)
val FoxLightSurface = Color(0xFFFFFCF7)
val FoxLightSurfaceRaised = Color(0xFFEFE7DA)
val FoxLightBorder = Color(0xFFD9D0C3)
val FoxLightText = Color(0xFF1A1612)
val FoxLightTextSecondary = Color(0xFF5A5148)
val FoxAmberLight = Color(0xFFC4841E)

/**
 * Semantic color tokens for the Fox Design Language.
 * Screens should prefer [FoxTheme.colors] over raw hex / one-off Material roles.
 */
@Immutable
data class FoxColorTokens(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceStrong: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentHover: Color,
    val accentMuted: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val bullish: Color,
    val bullishText: Color,
    val bearish: Color,
    val bearishText: Color,
    val neutral: Color,
    val information: Color,
    val ai: Color,
    val overlay: Color,
) {
    fun pnl(value: Double): Color = when {
        value > 0.0 -> bullishText
        value < 0.0 -> bearishText
        else -> textSecondary
    }
}

val FoxDarkTokens = FoxColorTokens(
    background = FoxNeutral0,
    surface = FoxNeutral5,
    surfaceElevated = FoxNeutral10,
    surfaceStrong = FoxNeutral15,
    border = FoxNeutral20,
    borderStrong = FoxNeutral30,
    textPrimary = FoxNeutral90,
    textSecondary = FoxNeutral80,
    textMuted = FoxNeutral60,
    accent = FoxAmber50,
    accentHover = FoxAmber60,
    accentMuted = FoxAmber50.copy(alpha = 0.16f),
    onAccent = FoxOnAccent,
    success = FoxSuccess,
    warning = FoxWarning,
    danger = FoxError,
    bullish = FoxBullish,
    bullishText = FoxBullishText,
    bearish = FoxBearish,
    bearishText = FoxBearishText,
    neutral = FoxMarketNeutral,
    information = FoxInfo,
    ai = FoxAi,
    overlay = Color(0xCC090807),
)

val FoxLightTokens = FoxColorTokens(
    background = FoxLightBg,
    surface = FoxLightSurface,
    surfaceElevated = FoxLightSurfaceRaised,
    surfaceStrong = Color(0xFFE4DACA),
    border = FoxLightBorder,
    borderStrong = Color(0xFFC4B8A6),
    textPrimary = FoxLightText,
    textSecondary = FoxLightTextSecondary,
    textMuted = Color(0xFF7A7066),
    accent = FoxAmberLight,
    accentHover = FoxAmber50,
    accentMuted = FoxAmberLight.copy(alpha = 0.14f),
    onAccent = Color(0xFFFFF8EC),
    success = Color(0xFF0F8F5E),
    warning = FoxAmberLight,
    danger = Color(0xFFC4333C),
    bullish = Color(0xFF0F8F5E),
    bullishText = Color(0xFF0C7A50),
    bearish = Color(0xFFC4333C),
    bearishText = Color(0xFFB4232C),
    neutral = Color(0xFF6E675E),
    information = Color(0xFF2B7AB8),
    ai = Color(0xFF9A7540),
    overlay = Color(0x990A0908),
)

val LocalFoxColors = staticCompositionLocalOf { FoxDarkTokens }
