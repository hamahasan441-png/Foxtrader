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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import com.foxtrader.app.feature.chart.presentation.CandleSeries
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.theme.FoxTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating study deck for oscillators/volume.
 *
 * RSI/MACD/Stochastic/OBV/MFI live on their own numeric scales, so forcing them
 * into the price canvas would be mathematically misleading. The old implementation
 * stacked every enabled pane below the chart and could consume ~190dp of vertical
 * space. This deck is rendered in a Popup instead: the price chart keeps 100% of
 * its measured height while every enabled study remains one tap away in a compact
 * on-chart dock.
 *
 * Only one study canvas is expanded at a time. Enabled studies remain visible as
 * tabs, which avoids a wall of miniature panels and keeps the chart readable on
 * phones. No signal logic is changed here; this is presentation only.
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
    val active = buildList {
        if (indicators.rsi && rsiValues != null && candles.isNotEmpty()) add(StudyPane.RSI)
        if (indicators.rsiOrderFlow && candles.isNotEmpty()) add(StudyPane.RSI_ORDER_FLOW)
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
    val visibleBars = (vp?.visibleBars ?: 120f).coerceAtLeast(2f)

    var selectedKey by rememberSaveable { mutableStateOf(active.first().key) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val selected = active.firstOrNull { it.key == selectedKey } ?: active.first()

    val density = LocalDensity.current
    val popupOffset = with(density) { IntOffset(0, (-84).dp.roundToPx()) }
    val colors = FoxTheme.colors
    val shape = RoundedCornerShape(FoxTheme.shapes.lg)

    Popup(
        alignment = Alignment.BottomCenter,
        offset = popupOffset,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = true,
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 720.dp)
                .heightIn(max = 132.dp)
                .clip(shape)
                .background(colors.surfaceElevated.copy(alpha = 0.97f))
                .border(1.dp, colors.borderStrong, shape)
                .padding(horizontal = FoxTheme.spacing.sm, vertical = FoxTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                active.forEach { pane ->
                    StudyTab(
                        label = pane.label,
                        selected = pane == selected && !collapsed,
                        onClick = {
                            if (selectedKey == pane.key && !collapsed) {
                                collapsed = true
                            } else {
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
                        )
                    }
                    StudyPane.RSI_ORDER_FLOW -> RsiOrderFlowSubChart(
                        candles = candles,
                        startIndex = startIndex,
                        visibleBars = visibleBars,
                        canvasHeight = STUDY_CANVAS_HEIGHT,
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
}

@Composable
private fun StudyTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = FoxTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        color = if (selected) colors.accent else colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accentMuted else colors.surfaceStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private enum class StudyPane(val key: String, val label: String) {
    RSI("rsi", "RSI"),
    RSI_ORDER_FLOW("rsi_order_flow", "RSI OF"),
    MACD("macd", "MACD"),
    STOCHASTIC("stochastic", "Stoch"),
    OBV("obv", "OBV"),
    MFI("mfi", "MFI"),
    VOLUME("volume", "Volume"),
}

private val STUDY_CANVAS_HEIGHT = 70.dp
