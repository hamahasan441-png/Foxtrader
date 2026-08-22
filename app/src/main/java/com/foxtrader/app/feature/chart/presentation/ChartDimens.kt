package com.foxtrader.app.feature.chart.presentation

import androidx.compose.ui.unit.dp

/** Central design tokens for the Chart feature. */
object ChartDimens {
    // Keep scales readable while giving price action more of the phone width.
    val priceScaleWidth = 58.dp
    val timeAxisHeight = 20.dp
    val subPaneScaleWidth = priceScaleWidth

    // --- Stacked sub-panes (RSI / MACD / Volume / oscillators) ---
    val paneHeaderHeight = 18.dp
    val paneMinHeight = 52.dp
    val paneMaxHeight = 160.dp
    val paneDefaultHeight = 72.dp
    val paneSplitterHeight = 8.dp

    /**
     * Hard ceiling for the entire study stack. Multiple enabled oscillators may
     * scroll inside this area, but they cannot consume the price canvas.
     */
    val paneStackMaxHeight = 150.dp

    // --- Chrome spacing ---
    // Intentionally compact: the chart is the primary surface and top chrome
    // should never reserve a large empty band above price action.
    val topBarHorizontalPadding = 8.dp
    val topBarVerticalPadding = 4.dp
    val toolbarHorizontalPadding = 6.dp
    val toolbarVerticalPadding = 2.dp

    // --- Floating overlays / palettes ---
    val overlayPadding = 6.dp
    val drawingRailWidth = 44.dp
}
