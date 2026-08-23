package com.foxtrader.app.feature.chart.presentation

import androidx.compose.ui.unit.dp

/** Central design tokens for the Chart feature. */
object ChartDimens {
    // Phone-first: keep scales readable while reclaiming a little more room for
    // price action. Labels still fit typical FX/crypto price precision.
    val priceScaleWidth = 54.dp
    val timeAxisHeight = 18.dp
    val subPaneScaleWidth = priceScaleWidth

    // --- Stacked sub-panes (RSI / MACD / Volume / oscillators) ---
    val paneHeaderHeight = 18.dp
    val paneMinHeight = 52.dp
    val paneMaxHeight = 168.dp
    val paneDefaultHeight = 80.dp
    val paneSplitterHeight = 8.dp

    /**
     * Hard ceiling for the entire study stack. Multiple enabled oscillators may
     * scroll inside this area, but they cannot consume the price canvas.
     */
    val paneStackMaxHeight = 168.dp

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
