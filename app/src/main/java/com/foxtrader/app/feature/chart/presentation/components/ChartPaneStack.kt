package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.R
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import com.foxtrader.app.feature.chart.presentation.CandleSeries
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlinx.coroutines.flow.StateFlow

/**
 * Stack of separate-pane ("study") indicators rendered below the price chart:
 * RSI, MACD and Volume. This is the explicit *pane* half of the overlay-vs-pane
 * indicator model — overlay indicators (EMA, Bollinger, SMC, …) draw on the
 * price canvas, while enabling a pane indicator auto-creates a resizable panel
 * here (TradingView-style).
 *
 * Each pane has a draggable splitter so the user can trade vertical space
 * between studies; heights persist across recomposition and process death via
 * [rememberSaveable]. The primary chart's viewport is collected here (once) so
 * only this stack recomposes as the user pans/zooms — the main [CandleChart]
 * render loop is never disturbed.
 */
@Composable
fun ChartPaneStack(
    indicators: IndicatorToggles,
    candles: CandleSeries,
    rsiValues: ImmutableDoubleSeries?,
    macdLine: ImmutableDoubleSeries?,
    macdSignal: ImmutableDoubleSeries?,
    macdHistogram: ImmutableDoubleSeries?,
    stochasticK: ImmutableDoubleSeries? = null,
    stochasticD: ImmutableDoubleSeries? = null,
    obv: ImmutableDoubleSeries? = null,
    moneyFlowIndex: ImmutableDoubleSeries? = null,
    viewportFlow: StateFlow<ChartViewportState?>,
    fallbackViewport: ChartViewportState?,
    modifier: Modifier = Modifier,
) {
    val liveViewport by viewportFlow.collectAsStateWithLifecycle()
    val vp = liveViewport ?: fallbackViewport
    val startIndex = vp?.startIndex ?: 0f
    val visibleBars = vp?.visibleBars ?: 120f

    Column(modifier = modifier.fillMaxWidth()) {
        if (indicators.rsi && rsiValues != null) {
            ResizablePane(paneKey = "rsi", paneName = "RSI") { h ->
                RsiSubChart(
                    rsiValues = rsiValues,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = h,
                )
            }
        }

        if (indicators.macd && macdLine != null && macdSignal != null && macdHistogram != null) {
            ResizablePane(paneKey = "macd", paneName = "MACD") { h ->
                MacdSubChart(
                    macdLine = macdLine,
                    macdSignal = macdSignal,
                    macdHistogram = macdHistogram,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = h,
                )
            }
        }

        if (indicators.stochastic && stochasticK != null && stochasticD != null) {
            ResizablePane(paneKey = "stochastic", paneName = "Stochastic") { h ->
                StochasticSubChart(
                    percentK = stochasticK,
                    percentD = stochasticD,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = h,
                )
            }
        }

        if (indicators.obv && obv != null) {
            ResizablePane(paneKey = "obv", paneName = "OBV") { h ->
                ObvSubChart(
                    obv = obv,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = h,
                )
            }
        }

        if (indicators.moneyFlowIndex && moneyFlowIndex != null) {
            ResizablePane(paneKey = "mfi", paneName = "MFI") { h ->
                MoneyFlowSubChart(
                    mfi = moneyFlowIndex,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = h,
                )
            }
        }

        if (indicators.volume && candles.isNotEmpty()) {
            ResizablePane(paneKey = "volume", paneName = "Volume") { h ->
                VolumePane(
                    candles = candles,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = h,
                )
            }
        }
    }
}

/**
 * A single resizable pane: a draggable splitter handle on top and the pane
 * content below at a user-controlled, persisted height.
 */
@Composable
private fun ResizablePane(
    paneKey: String,
    paneName: String,
    content: @Composable (canvasHeight: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val minDp = ChartDimens.paneMinHeight.value
    val maxDp = ChartDimens.paneMaxHeight.value
    var heightDp by rememberSaveable(key = "pane_height_$paneKey") {
        mutableStateOf(ChartDimens.paneDefaultHeight.value)
    }
    val resizeCd = stringResource(R.string.chart_pane_resize_cd, paneName)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Draggable splitter. Dragging up grows the pane, down shrinks it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartDimens.paneSplitterHeight)
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(paneKey) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaDp = dragAmount / density.density
                        heightDp = (heightDp - deltaDp).coerceIn(minDp, maxDp)
                    }
                }
                .semantics { contentDescription = resizeCd },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FoxNeutral60.copy(alpha = 0.5f)),
            )
        }
        content(heightDp.dp)
    }
}
