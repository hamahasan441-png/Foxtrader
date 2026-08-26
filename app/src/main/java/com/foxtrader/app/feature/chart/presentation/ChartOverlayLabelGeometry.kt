package com.foxtrader.app.feature.chart.presentation

/**
 * Returns a safe Canvas text baseline inside the price-chart area.
 *
 * Indicator panes can legitimately reduce the main chart to less than one
 * label line. Building `requested.coerceIn(textSize, chartHeight - padding)` in
 * that state creates an inverted range and throws on the render thread. A label
 * that cannot fit is cosmetic, so callers skip it while the chart and its
 * indicators continue rendering.
 */
internal fun chartOverlayLabelBaseline(
    requested: Float,
    textSize: Float,
    chartHeight: Float,
    bottomPadding: Float = 2f,
): Float? {
    val height = chartHeight.takeIf { it.isFinite() && it > 0f } ?: return null
    val minimum = textSize.takeIf { it.isFinite() && it >= 0f } ?: return null
    val padding = bottomPadding.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val maximum = height - padding
    if (maximum < minimum) return null

    val value = requested.takeIf { it.isFinite() } ?: minimum
    return value.coerceIn(minimum, maximum)
}
