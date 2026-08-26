package com.foxtrader.app.feature.chart.presentation.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.feature.chart.presentation.ChartIndicatorRuntime
import com.foxtrader.app.feature.chart.presentation.ChartStudyId
import com.foxtrader.app.feature.chart.presentation.IndicatorReadinessCatalog
import com.foxtrader.app.feature.chart.presentation.IndicatorReadinessLevel
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles

/**
 * Full FoxTrader indicator command center.
 *
 * Analysis engines and chart studies are independent selections. Choosing a LiT
 * Adventure mode in app settings must never hide or clear EMA/RSI/SMC/volume
 * studies, and enabling another engine must not silently remove the current
 * chart stack. Every production indicator remains visible here regardless of the
 * active LiT Adventure profile.
 *
 * Technical study parameters are edited from the TradingView-style active-study
 * gear chips on the chart. The RSI Orderflow advanced divergence thresholds are
 * additionally exposed here because the compact gear card previously omitted
 * them. Signal-engine parameters remain in the app Settings screen.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndicatorPanel(
    visible: Boolean,
    toggles: IndicatorToggles,
    strategyBlueprints: List<StrategyBlueprint> = emptyList(),
    onToggle: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val density = LocalDensity.current
    val popupOffset = with(density) { IntOffset(0, POPUP_OFFSET_DP.dp.roundToPx()) }
    val maxPopupHeight = (LocalConfiguration.current.screenHeightDp * 0.72f)
        .coerceAtLeast(260f)
        .dp
    var lastToggleAt by remember { mutableLongStateOf(0L) }

    val guardedToggle: (((IndicatorToggles) -> IndicatorToggles) -> Unit) = { transform ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt >= TOGGLE_DEBOUNCE_MS) {
            lastToggleAt = now
            onToggle(transform)
        }
    }

    Popup(
        alignment = Alignment.TopCenter,
        offset = popupOffset,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = true,
        ),
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = 300.dp, max = 680.dp)
                .padding(horizontal = 10.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = maxPopupHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("INDICATORS & SIGNALS", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Add a study here, then use its small on-chart ⚙ chip to edit presets and parameters. The picker stays selection-only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                IndicatorCategory(
                    title = "Signal engines",
                    items = listOf(
                        StudyItem("LiT Adventure", ChartStudyId.LITX, toggles.litX) { t -> t.withLitXSuite(!t.litX) },
                        StudyItem("LiT May Madness", ChartStudyId.LIT, toggles.lit) { t -> t.withLitSuite(!t.lit) },
                        StudyItem("SMT", ChartStudyId.SMT, toggles.smt) { t -> t.withSmtSuite(!t.smt) },
                        StudyItem("RSI Orderflow Candle", ChartStudyId.RSI_ORDER_FLOW, toggles.rsiOrderFlow) { t ->
                            t.copy(rsiOrderFlow = !t.rsiOrderFlow)
                        },
                        StudyItem("Pivot Sweep Divergence", ChartStudyId.PIVOT_SWEEP_DIVERGENCE, toggles.pivotSweepDivergence) { t ->
                            t.copy(pivotSweepDivergence = !t.pivotSweepDivergence)
                        },
                        StudyItem("Value Area Liquidity Rejection", ChartStudyId.VALUE_AREA_LIQUIDITY_REJECTION, toggles.valueAreaLiquidityRejection) { t ->
                            t.copy(valueAreaLiquidityRejection = !t.valueAreaLiquidityRejection)
                        },
                        StudyItem("SMS", ChartStudyId.SMS, toggles.sms) { t -> t.withSmsSuite(!t.sms) },
                        StudyItem("TradePro", ChartStudyId.TRADE_PRO, toggles.tradePro) { t -> t.withTradeProSuite(!t.tradePro) },
                        StudyItem("Deriv 3m", ChartStudyId.BINARY_3M, toggles.binary3m) { t ->
                            t.copy(binary3m = !t.binary3m)
                        },
                    ),
                    onToggle = guardedToggle,
                )

                IndicatorCategory(
                    title = "Smart money / structure",
                    items = listOf(
                        StudyItem("Structure", ChartStudyId.STRUCTURE, toggles.structure) { t -> t.copy(structure = !t.structure) },
                        StudyItem("Order Blocks", ChartStudyId.ORDER_BLOCKS, toggles.orderBlocks) { t -> t.copy(orderBlocks = !t.orderBlocks) },
                        StudyItem("Fair Value Gaps", ChartStudyId.FAIR_VALUE_GAPS, toggles.fairValueGaps) { t -> t.copy(fairValueGaps = !t.fairValueGaps) },
                        StudyItem("Liquidity", ChartStudyId.LIQUIDITY, toggles.liquidity) { t -> t.copy(liquidity = !t.liquidity) },
                        StudyItem("Sessions", ChartStudyId.SESSIONS, toggles.sessions) { t -> t.copy(sessions = !t.sessions) },
                        StudyItem("MTF Confluence", ChartStudyId.CONFLUENCE, toggles.confluence) { t -> t.copy(confluence = !t.confluence) },
                    ),
                    onToggle = guardedToggle,
                )

                IndicatorCategory(
                    title = "Trend / price overlays",
                    items = listOf(
                        StudyItem("EMA", ChartStudyId.EMA, toggles.ema) { t -> t.copy(ema = !t.ema) },
                        StudyItem("Bollinger", ChartStudyId.BOLLINGER, toggles.bollinger) { t -> t.copy(bollinger = !t.bollinger) },
                        StudyItem("SuperTrend", ChartStudyId.SUPER_TREND, toggles.superTrend) { t -> t.copy(superTrend = !t.superTrend) },
                        StudyItem("Parabolic SAR", ChartStudyId.PARABOLIC_SAR, toggles.parabolicSar) { t -> t.copy(parabolicSar = !t.parabolicSar) },
                        StudyItem("VWAP", ChartStudyId.VWAP, toggles.vwap) { t -> t.copy(vwap = !t.vwap) },
                        StudyItem("Anchored VWAP", ChartStudyId.ANCHORED_VWAP, toggles.anchoredVwap) { t -> t.copy(anchoredVwap = !t.anchoredVwap) },
                        StudyItem("Ichimoku", ChartStudyId.ICHIMOKU, toggles.ichimoku) { t -> t.copy(ichimoku = !t.ichimoku) },
                        StudyItem("Keltner", ChartStudyId.KELTNER, toggles.keltner) { t -> t.copy(keltner = !t.keltner) },
                        StudyItem("Donchian", ChartStudyId.DONCHIAN, toggles.donchian) { t -> t.copy(donchian = !t.donchian) },
                        StudyItem("Daily Pivots", ChartStudyId.PIVOTS, toggles.pivotPoints) { t -> t.copy(pivotPoints = !t.pivotPoints) },
                        StudyItem("Support / Resistance", ChartStudyId.SUPPORT_RESISTANCE, toggles.supportResistance) { t ->
                            t.copy(supportResistance = !t.supportResistance)
                        },
                        StudyItem("Auto Fibonacci", ChartStudyId.FIBONACCI, toggles.fibonacci) { t -> t.copy(fibonacci = !t.fibonacci) },
                    ),
                    onToggle = guardedToggle,
                )

                IndicatorCategory(
                    title = "Oscillators / momentum",
                    items = listOf(
                        StudyItem("RSI", ChartStudyId.RSI, toggles.rsi) { t -> t.copy(rsi = !t.rsi) },
                        StudyItem("MACD", ChartStudyId.MACD, toggles.macd) { t -> t.copy(macd = !t.macd) },
                        StudyItem("Stochastic", ChartStudyId.STOCHASTIC, toggles.stochastic) { t -> t.copy(stochastic = !t.stochastic) },
                        StudyItem("MFI", ChartStudyId.MFI, toggles.moneyFlowIndex) { t -> t.copy(moneyFlowIndex = !t.moneyFlowIndex) },
                    ),
                    onToggle = guardedToggle,
                )

                IndicatorCategory(
                    title = "Volume / profile",
                    items = listOf(
                        StudyItem("Volume", ChartStudyId.VOLUME, toggles.volume) { t -> t.copy(volume = !t.volume) },
                        StudyItem("OBV", ChartStudyId.OBV, toggles.obv) { t -> t.copy(obv = !t.obv) },
                        StudyItem("Volume Profile", ChartStudyId.VOLUME_PROFILE, toggles.volumeProfile) { t -> t.copy(volumeProfile = !t.volumeProfile) },
                        StudyItem("Market Profile", ChartStudyId.MARKET_PROFILE, toggles.marketProfile) { t -> t.copy(marketProfile = !t.marketProfile) },
                    ),
                    onToggle = guardedToggle,
                )

                Text(
                    text = "Readiness is informational: incompatible studies remain visible instead of disappearing. " +
                        "Time-axis studies require standard time candles; Deriv 3m requires Deriv + M1. " +
                        "All editable study and signal-engine controls live on the active chart ⚙ card.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class StudyItem(
    val label: String,
    val studyId: ChartStudyId,
    val selected: Boolean,
    val transform: (IndicatorToggles) -> IndicatorToggles,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorCategory(
    title: String,
    items: List<StudyItem>,
    onToggle: (((IndicatorToggles) -> IndicatorToggles) -> Unit),
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items.forEach { item ->
                val readiness = IndicatorReadinessCatalog.status(
                    study = item.studyId,
                    candleCount = ChartIndicatorRuntime.candleCount,
                    barMode = ChartIndicatorRuntime.barMode,
                    settings = ChartIndicatorRuntime.settings,
                )
                val stateText = when (readiness.level) {
                    IndicatorReadinessLevel.READY -> "Ready"
                    IndicatorReadinessLevel.WARMING -> readiness.label
                    IndicatorReadinessLevel.INCOMPATIBLE -> readiness.label
                }
                FilterChip(
                    selected = item.selected,
                    onClick = { onToggle(item.transform) },
                    label = { Text("${item.label} · $stateText") },
                )
            }
        }
    }
}

@Composable
private fun RsiOrderFlowAdvancedSettings(
    toggles: IndicatorToggles,
    onToggle: (((IndicatorToggles) -> IndicatorToggles) -> Unit),
) {
    val cfg = toggles.settings.rsiOrderFlow.sanitized()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = "RSI Orderflow · Advanced",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "These divergence thresholds were previously runtime-only and are now editable on screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InlineIntSetting("Min pivot separation", cfg.minPivotSeparation, 1, 250) { value ->
            onToggle { t ->
                val next = t.settings.rsiOrderFlow.copy(minPivotSeparation = value).sanitized()
                t.copy(settings = t.settings.copy(rsiOrderFlow = next).sanitized())
            }
        }
        InlineIntSetting("Max pivot separation", cfg.maxPivotSeparation, cfg.minPivotSeparation, 500) { value ->
            onToggle { t ->
                val next = t.settings.rsiOrderFlow.copy(maxPivotSeparation = value).sanitized()
                t.copy(settings = t.settings.copy(rsiOrderFlow = next).sanitized())
            }
        }
        InlineDoubleSetting("Min RSI difference", cfg.minRsiDifference, 0.5, 0.0, 50.0) { value ->
            onToggle { t ->
                val next = t.settings.rsiOrderFlow.copy(minRsiDifference = value).sanitized()
                t.copy(settings = t.settings.copy(rsiOrderFlow = next).sanitized())
            }
        }
        InlineDoubleSetting("Min flow difference", cfg.minFlowDifference, 0.5, 0.0, 50.0) { value ->
            onToggle { t ->
                val next = t.settings.rsiOrderFlow.copy(minFlowDifference = value).sanitized()
                t.copy(settings = t.settings.copy(rsiOrderFlow = next).sanitized())
            }
        }
    }
}

@Composable
private fun InlineIntSetting(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValue: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        FilterChip(
            selected = false,
            onClick = { onValue((value - 1).coerceAtLeast(min)) },
            enabled = value > min,
            label = { Text("−") },
        )
        Text(value.toString(), style = MaterialTheme.typography.labelMedium)
        FilterChip(
            selected = false,
            onClick = { onValue((value + 1).coerceAtMost(max)) },
            enabled = value < max,
            label = { Text("+") },
        )
    }
}

@Composable
private fun InlineDoubleSetting(
    label: String,
    value: Double,
    step: Double,
    min: Double,
    max: Double,
    onValue: (Double) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        FilterChip(
            selected = false,
            onClick = { onValue((value - step).coerceAtLeast(min)) },
            enabled = value > min,
            label = { Text("−") },
        )
        Text("%.1f".format(value), style = MaterialTheme.typography.labelMedium)
        FilterChip(
            selected = false,
            onClick = { onValue((value + step).coerceAtMost(max)) },
            enabled = value < max,
            label = { Text("+") },
        )
    }
}

private const val TOGGLE_DEBOUNCE_MS = 120L
private const val POPUP_OFFSET_DP = 104
