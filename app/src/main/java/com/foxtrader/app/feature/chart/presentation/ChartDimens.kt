package com.foxtrader.app.feature.chart.presentation

import androidx.compose.ui.unit.dp

/** Central design tokens for the Chart feature. */
object ChartDimens {
    // Phone-first: keep scales readable while reclaiming room for price action.
    // Labels still fit typical FX/metal/crypto precision on a handset.
    val priceScaleWidth = 52.dp
    val timeAxisHeight = 18.dp
    val subPaneScaleWidth = priceScaleWidth

    // --- Stacked sub-panes (approved study panes / volume context) ---
    // The pane starts large enough to read order-flow candles and can be resized
    // directly from its top splitter, matching TradingView's pane interaction.
    val paneHeaderHeight = 18.dp
    val paneMinHeight = 72.dp
    val paneMaxHeight = 280.dp
    val paneDefaultHeight = 132.dp
    val paneSplitterHeight = 12.dp

    /** Hard ceiling for the complete study deck below the price chart. */
    val paneStackMaxHeight = 344.dp

    // --- Chrome spacing ---
    // Intentionally compact: the chart is the primary surface and top chrome
    // should never reserve a large empty band above price action.
    val topBarContentHeight = 40.dp
    val topBarHorizontalPadding = 8.dp
    val toolbarHeight = 36.dp
    val toolbarHorizontalPadding = 6.dp
    val toolbarVerticalPadding = 1.dp

    // --- Floating overlays / palettes ---
    val overlayPadding = 4.dp
    val drawingRailWidth = 40.dp
}
