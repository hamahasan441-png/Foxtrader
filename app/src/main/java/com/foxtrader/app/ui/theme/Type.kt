package com.foxtrader.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================================================================
// FOX DESIGN LANGUAGE — Typography
// System sans for UI. Monospace + tabular figures for prices, percents,
// quantities and timestamps so columns stay scannable.
// ============================================================================

val FoxMono = FontFamily.Monospace

private const val TABULAR = "tnum, lnum"

val FoxTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Financial type ramp used on top of Material roles. */
@Immutable
data class FoxTypeTokens(
    val display: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val numeric: TextStyle,
    val price: TextStyle,
    val percentage: TextStyle,
    val timestamp: TextStyle,
)

val FoxTypeTokensDefault = FoxTypeTokens(
    display = FoxTypography.displaySmall,
    h1 = FoxTypography.headlineLarge,
    h2 = FoxTypography.titleLarge,
    h3 = FoxTypography.titleMedium,
    body = FoxTypography.bodyMedium,
    label = FoxTypography.labelLarge,
    caption = FoxTypography.labelSmall,
    numeric = TextStyle(
        fontFamily = FoxMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR,
    ),
    price = TextStyle(
        fontFamily = FoxMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = TABULAR,
    ),
    percentage = TextStyle(
        fontFamily = FoxMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR,
    ),
    timestamp = TextStyle(
        fontFamily = FoxMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR,
    ),
)

/** Monospace style for price displays (tabular numbers). */
val FoxPriceStyle = FoxTypeTokensDefault.price

val LocalFoxType = staticCompositionLocalOf { FoxTypeTokensDefault }
