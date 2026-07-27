package com.foxtrader.app.domain.usecase.chart

import kotlinx.serialization.Serializable

/**
 * Persistable snapshot of a chart camera.
 *
 * Stored outside Compose so ViewModels and preferences can preserve the user's
 * exact scroll/zoom context across recomposition, process death and restored
 * multi-chart sessions.
 */
@Serializable
data class ChartViewportState(
    val startIndex: Float = 0f,
    val visibleBars: Float = 100f,
    val priceHigh: Double = 1.0,
    val priceLow: Double = 0.0,
)
