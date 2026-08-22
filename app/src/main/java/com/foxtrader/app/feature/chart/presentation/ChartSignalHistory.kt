package com.foxtrader.app.feature.chart.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foxtrader.app.domain.model.ChartSignal

/**
 * Signal history is intentionally rendered by the price-canvas arrow layer.
 *
 * The former 200dp bottom panel covered price action and made the usable chart
 * substantially smaller whenever history was enabled. Keeping this composable
 * as a zero-layout compatibility shim avoids changing callers while enforcing
 * the chart UX contract: live and historical signals stay attached to their
 * bars as arrows; no second signal panel is drawn over the chart.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ChartSignalHistory(
    signals: List<ChartSignal>,
    modifier: Modifier = Modifier,
) = Unit
