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
    // The price chart is the primary workspace. A study pane may provide useful
    // context, but it must never consume a large fraction of a phone display.
    val paneHeaderHeight = 16.dp
    val paneMinHeight = 44.dp
    val paneMaxHeight = 112.dp
    val paneDefaultHeight = 64.dp
    val paneSplitterHeight = 6.dp

    /** Hard ceiling for the complete study deck below the price chart. */
    val paneStackMaxHeight = 112.dp

    // --- Chrome spacing ---
    // Intentionally compact: the chart is the primary surface and top chrome
    // should never reserve a large empty band above price action.
    val topBarHorizontalPadding = 8.dp
    val topBarVerticalPadding = 3.dp
    val toolbarHorizontalPadding = 6.dp
    val toolbarVerticalPadding = 1.dp

    // --- Floating overlays / palettes ---
    val overlayPadding = 4.dp
    val drawingRailWidth = 40.dp

    /**
     * Height of the collapsed Analysis handle pinned to the bottom of the chart.
     *
     * The handle is an overlay inside the chart Box, so without reserving this
     * much room it sits on top of the time axis and the lowest price grid line —
     * exactly the part of the canvas a trader reads most often.
     */
    val analysisHandleHeight = 36.dp
}
