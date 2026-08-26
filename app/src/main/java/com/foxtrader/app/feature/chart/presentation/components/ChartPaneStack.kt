package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import com.foxtrader.app.feature.chart.presentation.CandleSeries
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.theme.FoxTheme
import kotlinx.coroutines.flow.StateFlow

/** Compact bottom study deck, locked to the primary chart viewport. */
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
    val active = buildList {
        if (indicators.rsi && rsiValues != null && candles.isNotEmpty()) add(StudyPane.RSI)
        if (indicators.rsiOrderFlow && candles.isNotEmpty()) add(StudyPane.RSI_ORDER_FLOW)
        if (indicators.rsiReversal && candles.isNotEmpty()) add(StudyPane.RSI_CANDLE)
        if (indicators.macd && macdLine != null && macdSignal != null && macdHistogram != null) add(StudyPane.MACD)
        if (indicators.stochastic && stochasticK != null && stochasticD != null) add(StudyPane.STOCHASTIC)
        if (indicators.obv && obv != null) add(StudyPane.OBV)
        if (indicators.moneyFlowIndex && moneyFlowIndex != null) add(StudyPane.MFI)
        if (indicators.volume && candles.isNotEmpty()) add(StudyPane.VOLUME)
    }
    if (active.isEmpty()) return

    val liveViewport by viewportFlow.collectAsStateWithLifecycle()
    val vp = liveViewport ?: fallbackViewport
    val startIndex = (vp?.startIndex ?: 0f).coerceAtLeast(0f)
    val visibleBars = (vp?.visibleBars ?: 80f).coerceAtLeast(2f)

    var selectedKey by rememberSaveable { mutableStateOf(active.first().key) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val selected = active.firstOrNull { it.key == selectedKey } ?: active.first()

    val colors = FoxTheme.colors
    val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = ChartDimens.paneStackMaxHeight)
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.borderStrong, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            active.forEach { pane ->
                StudyTab(
                    label = pane.label,
                    selected = pane == selected && !collapsed,
                    onClick = {
                        if (selectedKey == pane.key && !collapsed) collapsed = true
                        else {
                            selectedKey = pane.key
                            collapsed = false
                        }
                    },
                )
            }
            StudyTab(
                label = if (collapsed) "Show" else "Hide",
                selected = collapsed,
                onClick = { collapsed = !collapsed },
            )
        }

        if (!collapsed) {
            when (selected) {
                StudyPane.RSI -> rsiValues?.let {
                    RsiSubChart(
                        rsiValues = it,
                        startIndex = startIndex,
                        visibleBars = visibleBars,
                        canvasHeight = STUDY_CANVAS_HEIGHT,
                        settings = indicators.settings.rsi,
                    )
                }

                StudyPane.RSI_ORDER_FLOW -> RsiOrderFlowSubChart(
                    candles = candles,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = STUDY_CANVAS_HEIGHT,
                    settings = indicators.settings.rsiOrderFlow,
                )

                StudyPane.RSI_CANDLE -> RsiCandleSubChart(
                    candles = candles,
                    startIndex = startIndex,
                    visibleBars = visibleBars,
                    canvasHeight = STUDY_CANVAS_HEIGHT,
                    settings = indicators.settings.rsiReversal,
                )

                StudyPane.MACD -> if (macdLine != null && macdSignal != null && macdHistogram != null) {
                    MacdSubChart(
                        macdLine = macdLine,
                        macdSignal = macdSignal,
                        macdHistogram = macdHistogram,
                        startIndex = startIndex,
                        visibleBars = visibleBars,
                        canvasHeight = STUDY_CANVAS_HEIGHT,
                    )
                }

                StudyPane.STOCHASTIC -> if (stochasticK != null && stochasticD != null) {
                    StochasticSubChart(
                        percentK = stochasticK,
                        percentD = stochasticD,
                        startIndex = startIndex,
                        visibleBars = visibleBars,
                        canvasHeight = STUDY_CANVAS_HEIGHT,
                        settings = indicators.settings.stochastic,
                    )
                }

                StudyPane.OBV -> obv?.let {
                    ObvSubChart(
                        obv = it,
                        startIndex = startIndex,
                        visibleBars = visibleBars,
                        canvasHeight = STUDY_CANVAS_HEIGHT,
                    )
                }

                StudyPane.MFI -> moneyFlowIndex?.let {
                    MoneyFlowSubChart(
                        mfi = it,
                        startIndex = startIndex,
                        visibleBars = visibleBars,
                        canvasHeight = STUDY_CANVAS_HEIGHT,
                    )
                }

                StudyPane.VOLUME -> if (candles.isNotEmpty()) {
                    VolumePane(
                        candles = candles,
                        startIndex = startIndex,
                        visibleBars = visibleBars,
                        canvasHeight = STUDY_CANVAS_HEIGHT,
                    )
                }
            }
        }
    }
}

@Composable
private fun StudyTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FoxTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        color = if (selected) colors.accent else colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) colors.accentMuted else colors.surfaceStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private enum class StudyPane(val key: String, val label: String) {
    RSI("rsi", "RSI"),
    RSI_ORDER_FLOW("rsi_order_flow", "RSI OF"),
    RSI_CANDLE("rsi_candle", "RSI Candle"),
    MACD("macd", "MACD"),
    STOCHASTIC("stochastic", "Stoch"),
    OBV("obv", "OBV"),
    MFI("mfi", "MFI"),
    VOLUME("volume", "Volume"),
}

private val STUDY_CANVAS_HEIGHT = 64.dp
