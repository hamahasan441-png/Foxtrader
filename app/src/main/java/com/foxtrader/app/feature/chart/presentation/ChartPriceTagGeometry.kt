package com.foxtrader.app.feature.chart.presentation

/**
 * Geometry for the live last-price tag drawn on the price scale.
 *
 * Pure math, extracted from the draw layer so the "tiny chart area" crash is
 * unit-testable on the JVM: stacking indicator panes (RSI + MACD + volume) or
 * opening the indicator panel shrinks the chart area until the tag no longer
 * fits. The previous inline math built `coerceIn(0f, ch - tagH)` with a
 * negative upper bound in that state, which threw IllegalArgumentException on
 * the render thread and killed the app — the "touching indicators crashes the
 * app" failure.
 *
 * The geometry degrades gracefully: the tag never exceeds the available chart
 * height, and its top is always inside `[0, chartHeight - tagHeight]`.
 */
internal data class PriceTagGeometry(
    /** Top edge of the tag within the chart area. */
    val top: Float,
    /** Tag height, clamped to the available chart height (never negative). */
    val height: Float,
)

internal fun priceTagGeometry(
    chartHeight: Float,
    textSize: Float,
    lastY: Float,
): PriceTagGeometry {
    // `CRASH-SAFETY` Sanitise every input first: a non-finite or negative
    // chart height must degenerate to an empty tag, never to a NaN geometry
    // that propagates into Canvas calls (NaN rects are a native-crash class
    // on some renderers).
    val h = if (chartHeight.isFinite()) chartHeight.coerceAtLeast(0f) else 0f
    val safeText = if (textSize.isFinite()) textSize.coerceAtLeast(0f) else 0f
    val safeY = if (lastY.isFinite()) lastY else 0f
    val tagH = (safeText + PRICE_TAG_PADDING).coerceAtMost(h)
    val top = if (tagH > 0f) {
        (safeY - tagH / 2f).coerceIn(0f, h - tagH)
    } else {
        0f
    }
    return PriceTagGeometry(top = top, height = tagH)
}

/** Vertical padding added to the price text to form the filled tag. */
internal const val PRICE_TAG_PADDING = 7f
