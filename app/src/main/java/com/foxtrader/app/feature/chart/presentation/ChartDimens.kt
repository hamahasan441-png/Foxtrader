package com.foxtrader.app.feature.chart.presentation

import androidx.compose.ui.unit.dp

/**
 * Central design tokens for the Chart feature.
 *
 * Single source of truth for axis gutters, chrome spacing, and sub-pane sizing
 * so the chart renders consistently across screens and orientations. This
 * replaces the magic numbers that were previously scattered across
 * [com.foxtrader.app.feature.chart.presentation.components.CandleChart],
 * the oscillator sub-charts, and [ChartScreen] (e.g. the hard-coded
 * `padding(top = 24.dp)`, `height(80.dp)`, and `priceScaleWidth = 48f`).
 *
 * `RULE` Prefer these tokens over inline `.dp` literals in chart code so a
 * layout change lands in exactly one place.
 */
object ChartDimens {
    // --- Axis gutters (main price chart) ---
    /** Right gutter reserved for the price (Y) scale + last-price tag. */
    val priceScaleWidth = 64.dp

    /** Bottom gutter reserved for the time (X) axis labels. */
    val timeAxisHeight = 24.dp

    /**
     * Price-scale gutter for the oscillator/volume panes. Deliberately equal to
     * [priceScaleWidth]: the panes map bar index → x across `width - gutter`,
     * so any difference between the two gutters horizontally shears every
     * sub-pane bar/line off its candle above (previously 56dp vs 64dp, an
     * ~8dp drift that grew across the pane). TradingView keeps these equal.
     */
    val subPaneScaleWidth = priceScaleWidth

    // --- Stacked sub-panes (RSI / MACD / Volume) ---
    /** Header strip (indicator name + latest value) above each pane's canvas. */
    val paneHeaderHeight = 18.dp

    /** Floor for a resizable pane's drawing area (keeps a pane usable). */
    val paneMinHeight = 60.dp

    /** Ceiling for a resizable pane so it can never starve the price chart. */
    val paneMaxHeight = 280.dp

    /** Default height a pane is created at before the user resizes it. */
    val paneDefaultHeight = 104.dp

    /** Draggable splitter handle height between the price chart and panes. */
    val paneSplitterHeight = 14.dp

    // --- Chrome spacing ---
    val topBarHorizontalPadding = 12.dp
    val topBarVerticalPadding = 8.dp
    val toolbarHorizontalPadding = 8.dp
    val toolbarVerticalPadding = 4.dp

    // --- Floating overlays / palettes ---
    /** Standard inset for overlays floated over the chart canvas. */
    val overlayPadding = 8.dp

    /** Width of the edge-docked floating drawing tool rail. */
    val drawingRailWidth = 46.dp
}
