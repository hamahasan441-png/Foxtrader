package com.foxtrader.app.feature.chart.presentation

import androidx.compose.ui.unit.dp

/** Central design tokens for the Chart feature. */
object ChartDimens {
    val priceScaleWidth = 64.dp
    val timeAxisHeight = 24.dp
    val subPaneScaleWidth = priceScaleWidth

    // --- Stacked sub-panes (RSI / MACD / Volume / oscillators) ---
    val paneHeaderHeight = 18.dp
    val paneMinHeight = 52.dp
    val paneMaxHeight = 180.dp
    val paneDefaultHeight = 76.dp
    val paneSplitterHeight = 10.dp

    /**
     * Hard ceiling for the entire study stack. Multiple enabled oscillators may
     * scroll inside this area, but they can no longer consume the whole screen
     * and collapse the main price chart to a tiny strip.
     */
    val paneStackMaxHeight = 190.dp

    // --- Chrome spacing ---
    val topBarHorizontalPadding = 12.dp
    val topBarVerticalPadding = 8.dp
    val toolbarHorizontalPadding = 8.dp
    val toolbarVerticalPadding = 4.dp

    // --- Floating overlays / palettes ---
    val overlayPadding = 8.dp
    val drawingRailWidth = 46.dp
}
