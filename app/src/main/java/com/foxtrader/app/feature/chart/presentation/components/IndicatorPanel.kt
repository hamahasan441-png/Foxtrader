package com.foxtrader.app.feature.chart.presentation.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.SmcVisualMode
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.feature.chart.presentation.ChartIndicatorRuntime
import com.foxtrader.app.feature.chart.presentation.ChartStudyId
import com.foxtrader.app.feature.chart.presentation.IndicatorReadinessCatalog
import com.foxtrader.app.feature.chart.presentation.IndicatorReadinessLevel
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.components.FoxChip
import com.foxtrader.app.ui.theme.FoxTheme

/**
 * Floating, bounded indicator command center.
 *
 * It never participates in the price-chart layout, so opening Indicators cannot
 * shrink the chart. Rapid chip taps are coalesced at the UI boundary: each tap
 * may start several CPU-heavy institutional engines, and allowing dozens of
 * recomputes inside one frame was a major source of jank/crash reports.
 */
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
    val popupOffset = with(density) { IntOffset(0, 104.dp.roundToPx()) }

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
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = modifier,
        ) {
            IndicatorCommandCenter(
                toggles = toggles,
                strategyBlueprints = strategyBlueprints,
                onToggle = onToggle,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorCommandCenter(
    toggles: IndicatorToggles,
    strategyBlueprints: List<StrategyBlueprint>,
    onToggle: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
) {
    val colors = FoxTheme.colors
    val candleCount = ChartIndicatorRuntime.candleCount
    val barMode = ChartIndicatorRuntime.barMode
    val activeCount = activeStudyCount(toggles)
    val readinessIssues = activeReadinessIssues(toggles, candleCount, barMode)
    val shape = RoundedCornerShape(FoxTheme.shapes.lg)

    var lastToggleAt by remember { mutableLongStateOf(0L) }
    val requestToggle: (((IndicatorToggles) -> IndicatorToggles) -> Unit) = remember(onToggle) {
        { transform ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastToggleAt >= TOGGLE_DEBOUNCE_MS) {
                lastToggleAt = now
                onToggle(transform)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .widthIn(max = 720.dp)
            .heightIn(max = 330.dp)
            .padding(horizontal = FoxTheme.spacing.sm)
            .clip(shape)
            .background(colors.surfaceElevated.copy(alpha = 0.98f))
            .border(1.dp, colors.borderStrong, shape)
            .verticalScroll(rememberScrollState())
            .padding(FoxTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.md),
    ) {
        Header(
            activeCount = activeCount,
            candleCount = candleCount,
            barMode = barMode,
            readinessIssues = readinessIssues,
        )

        FlowGroup("Quick setups") {
            Chip("Institutional", toggles.institutionalSuiteActive) {
                requestToggle { current ->
                    current.withInstitutionalSuite(!current.institutionalSuiteActive).let { next ->
                        if (next.institutionalSuiteActive) next.copy(smcVisualMode = SmcVisualMode.MINIMAL) else next
                    }
                }
            }
            Chip("SMC", toggles.smcSuiteActive) {
                requestToggle { current ->
                    current.withSmcSuite(!current.smcSuiteActive).let { next ->
                        if (next.smcSuiteActive) next.copy(smcVisualMode = SmcVisualMode.MINIMAL) else next
                    }
                }
            }
            Chip("LiTX", toggles.litX) {
                requestToggle { current ->
                    current.withLitXSuite(!current.litX).let { next ->
                        if (next.litX) next.copy(smcVisualMode = SmcVisualMode.MINIMAL) else next
                    }
                }
            }
            Chip("LiT", toggles.lit) {
                requestToggle { current ->
                    current.withLitSuite(!current.lit).let { next ->
                        if (next.lit) next.copy(smcVisualMode = SmcVisualMode.MINIMAL) else next
                    }
                }
            }
            Chip("SMT", toggles.smt) {
                requestToggle { current ->
                    current.withSmtSuite(!current.smt).let { next ->
                        if (next.smt) next.copy(smcVisualMode = SmcVisualMode.MINIMAL) else next
                    }
                }
            }
            Chip("Technical", technicalSuiteActive(toggles)) {
                requestToggle { current ->
                    val enable = !technicalSuiteActive(current)
                    current.copy(
                        ema = enable,
                        bollinger = enable,
                        superTrend = enable,
                        vwap = enable,
                        rsi = enable,
                        macd = enable,
                    )
                }
            }
            if (toggles.anyActive) {
                Chip("Clean", false) {
                    requestToggle { current -> IndicatorToggles(smcVisualMode = current.smcVisualMode) }
                }
            }
        }

        FlowGroup("Trend & momentum") {
            StudyChip(stringResource(R.string.chart_indicator_ema), ChartStudyId.EMA, toggles.ema, candleCount, barMode) { requestToggle { it.copy(ema = !it.ema) } }
            StudyChip(stringResource(R.string.chart_indicator_supertrend), ChartStudyId.SUPER_TREND, toggles.superTrend, candleCount, barMode) { requestToggle { it.copy(superTrend = !it.superTrend) } }
            StudyChip(stringResource(R.string.chart_indicator_ichimoku), ChartStudyId.ICHIMOKU, toggles.ichimoku, candleCount, barMode) { requestToggle { it.copy(ichimoku = !it.ichimoku) } }
            StudyChip(stringResource(R.string.chart_indicator_psar), ChartStudyId.PARABOLIC_SAR, toggles.parabolicSar, candleCount, barMode) { requestToggle { it.copy(parabolicSar = !it.parabolicSar) } }
            StudyChip("RSI", ChartStudyId.RSI, toggles.rsi, candleCount, barMode) { requestToggle { it.copy(rsi = !it.rsi) } }
            StudyChip("RSI OrderFlow", ChartStudyId.RSI_ORDER_FLOW, toggles.rsiOrderFlow, candleCount, barMode) { requestToggle { it.copy(rsiOrderFlow = !it.rsiOrderFlow) } }
            StudyChip("MACD", ChartStudyId.MACD, toggles.macd, candleCount, barMode) { requestToggle { it.copy(macd = !it.macd) } }
            StudyChip("Stoch", ChartStudyId.STOCHASTIC, toggles.stochastic, candleCount, barMode) { requestToggle { it.copy(stochastic = !it.stochastic) } }
        }

        FlowGroup("Volatility & volume") {
            StudyChip(stringResource(R.string.chart_indicator_bollinger), ChartStudyId.BOLLINGER, toggles.bollinger, candleCount, barMode) { requestToggle { it.copy(bollinger = !it.bollinger) } }
            StudyChip("Keltner", ChartStudyId.KELTNER, toggles.keltner, candleCount, barMode) { requestToggle { it.copy(keltner = !it.keltner) } }
            StudyChip("Donchian", ChartStudyId.DONCHIAN, toggles.donchian, candleCount, barMode) { requestToggle { it.copy(donchian = !it.donchian) } }
            StudyChip(stringResource(R.string.chart_pane_volume_title), ChartStudyId.VOLUME, toggles.volume, candleCount, barMode) { requestToggle { it.copy(volume = !it.volume) } }
            StudyChip(stringResource(R.string.chart_indicator_vwap), ChartStudyId.VWAP, toggles.vwap, candleCount, barMode) { requestToggle { it.copy(vwap = !it.vwap) } }
            StudyChip("A-VWAP", ChartStudyId.ANCHORED_VWAP, toggles.anchoredVwap, candleCount, barMode) { requestToggle { it.copy(anchoredVwap = !it.anchoredVwap) } }
            StudyChip("OBV", ChartStudyId.OBV, toggles.obv, candleCount, barMode) { requestToggle { it.copy(obv = !it.obv) } }
            StudyChip("MFI", ChartStudyId.MFI, toggles.moneyFlowIndex, candleCount, barMode) { requestToggle { it.copy(moneyFlowIndex = !it.moneyFlowIndex) } }
            StudyChip(stringResource(R.string.chart_indicator_volume_profile), ChartStudyId.VOLUME_PROFILE, toggles.volumeProfile, candleCount, barMode) { requestToggle { it.copy(volumeProfile = !it.volumeProfile) } }
            StudyChip(stringResource(R.string.chart_indicator_market_profile), ChartStudyId.MARKET_PROFILE, toggles.marketProfile, candleCount, barMode) { requestToggle { it.copy(marketProfile = !it.marketProfile) } }
        }

        FlowGroup("Structure") {
            StudyChip(stringResource(R.string.chart_indicator_structure), ChartStudyId.STRUCTURE, toggles.structure, candleCount, barMode) { requestToggle { it.copy(structure = !it.structure) } }
            StudyChip(stringResource(R.string.chart_indicator_support_resistance), ChartStudyId.SUPPORT_RESISTANCE, toggles.supportResistance, candleCount, barMode) { requestToggle { it.copy(supportResistance = !it.supportResistance) } }
            StudyChip(stringResource(R.string.chart_indicator_fibonacci), ChartStudyId.FIBONACCI, toggles.fibonacci, candleCount, barMode) { requestToggle { it.copy(fibonacci = !it.fibonacci) } }
            StudyChip(stringResource(R.string.chart_indicator_sessions), ChartStudyId.SESSIONS, toggles.sessions, candleCount, barMode) { requestToggle { it.copy(sessions = !it.sessions) } }
            StudyChip("Pivots", ChartStudyId.PIVOTS, toggles.pivotPoints, candleCount, barMode) { requestToggle { it.copy(pivotPoints = !it.pivotPoints) } }
            StudyChip(stringResource(R.string.chart_indicator_confluence), ChartStudyId.CONFLUENCE, toggles.confluence, candleCount, barMode) { requestToggle { it.copy(confluence = !it.confluence) } }
        }

        FlowGroup("Smart money") {
            StudyChip(stringResource(R.string.chart_indicator_order_blocks), ChartStudyId.ORDER_BLOCKS, toggles.orderBlocks, candleCount, barMode) { requestToggle { it.copy(orderBlocks = !it.orderBlocks) } }
            StudyChip(stringResource(R.string.chart_indicator_fvg), ChartStudyId.FAIR_VALUE_GAPS, toggles.fairValueGaps, candleCount, barMode) { requestToggle { it.copy(fairValueGaps = !it.fairValueGaps) } }
            StudyChip(stringResource(R.string.chart_indicator_liquidity), ChartStudyId.LIQUIDITY, toggles.liquidity, candleCount, barMode) { requestToggle { it.copy(liquidity = !it.liquidity) } }
            StudyChip("LiTX", ChartStudyId.LITX, toggles.litX, candleCount, barMode) { requestToggle { it.withLitXSuite(!it.litX) } }
            StudyChip("LiT", ChartStudyId.LIT, toggles.lit, candleCount, barMode) { requestToggle { it.withLitSuite(!it.lit) } }
            StudyChip("SMS", ChartStudyId.SMS, toggles.sms, candleCount, barMode) { requestToggle { it.withSmsSuite(!it.sms) } }
            StudyChip("SMT", ChartStudyId.SMT, toggles.smt, candleCount, barMode) { requestToggle { it.withSmtSuite(!it.smt) } }
            StudyChip("TradePro", ChartStudyId.TRADE_PRO, toggles.tradePro, candleCount, barMode) { requestToggle { it.withTradeProSuite(!it.tradePro) } }
        }

        FlowGroup("Signal engines") {
            StudyChip("Deriv 3m", ChartStudyId.BINARY_3M, toggles.binary3m, candleCount, barMode) {
                requestToggle { current ->
                    val enable = !current.binary3m
                    current.copy(
                        binary3m = enable,
                        activeStrategy = if (enable) null else current.activeStrategy,
                        activeBlueprintId = if (enable) null else current.activeBlueprintId,
                        allStrategies = if (enable) false else current.allStrategies,
                    )
                }
            }
            Chip("Off", !toggles.binary3m && toggles.activeStrategy == null && toggles.activeBlueprintId == null && !toggles.allStrategies) {
                requestToggle { it.copy(binary3m = false, activeStrategy = null, activeBlueprintId = null, allStrategies = false) }
            }
            Chip("All strategies", toggles.allStrategies) {
                requestToggle { it.copy(binary3m = false, allStrategies = !it.allStrategies, activeStrategy = null, activeBlueprintId = null) }
            }
            StrategyType.entries.forEach { type ->
                Chip(type.label, toggles.activeStrategy == type) {
                    requestToggle { current ->
                        current.copy(
                            binary3m = false,
                            activeStrategy = if (current.activeStrategy == type) null else type,
                            activeBlueprintId = null,
                            allStrategies = false,
                        )
                    }
                }
            }
            strategyBlueprints.forEach { blueprint ->
                Chip("My · ${blueprint.name}", toggles.activeBlueprintId == blueprint.id) {
                    requestToggle { current ->
                        current.copy(
                            binary3m = false,
                            activeStrategy = null,
                            activeBlueprintId = if (current.activeBlueprintId == blueprint.id) null else blueprint.id,
                            allStrategies = false,
                        )
                    }
                }
            }
        }

        FlowGroup("Density") {
            SmcVisualMode.entries.forEach { mode ->
                Chip(mode.label, toggles.smcVisualMode == mode) { requestToggle { it.copy(smcVisualMode = mode) } }
            }
        }
    }
}

@Composable
private fun Header(
    activeCount: Int,
    candleCount: Int,
    barMode: ChartBarMode,
    readinessIssues: List<String>,
) {
    val colors = FoxTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Indicators", style = FoxTheme.type.h3, color = colors.textPrimary, fontWeight = FontWeight.Bold)
            Text("$activeCount active · $candleCount bars · ${barMode.label}", style = FoxTheme.type.caption, color = colors.textMuted)
        }
        Text(
            text = if (readinessIssues.isEmpty()) "READY" else "${readinessIssues.size} WAIT",
            style = MaterialTheme.typography.labelSmall,
            color = if (readinessIssues.isEmpty()) colors.success else colors.warning,
            fontWeight = FontWeight.Bold,
        )
    }
    if (readinessIssues.isNotEmpty()) {
        Text(readinessIssues.take(3).joinToString("  ·  "), style = FoxTheme.type.caption, color = colors.warning)
    } else if (activeCount > 0) {
        Text(
            "Studies are ready. Signals appear only after a confirmed setup; no look-ahead arrows are fabricated.",
            style = FoxTheme.type.caption,
            color = colors.textSecondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = FoxTheme.colors.textMuted, fontWeight = FontWeight.SemiBold)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs),
        ) { content() }
    }
}

@Composable
private fun StudyChip(
    label: String,
    study: ChartStudyId,
    active: Boolean,
    candleCount: Int,
    barMode: ChartBarMode,
    onClick: () -> Unit,
) {
    val readiness = IndicatorReadinessCatalog.status(study, candleCount, barMode)
    val renderedLabel = when {
        !active -> label
        readiness.level == IndicatorReadinessLevel.READY -> "$label · ready"
        else -> "$label · ${readiness.label}"
    }
    FoxChip(label = renderedLabel, selected = active, onClick = onClick)
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    FoxChip(label = label, selected = active, onClick = onClick)
}

private fun technicalSuiteActive(t: IndicatorToggles): Boolean =
    t.ema && t.bollinger && t.superTrend && t.vwap && t.rsi && t.macd

private fun activeStudyCount(t: IndicatorToggles): Int = listOf(
    t.ema, t.bollinger, t.superTrend, t.parabolicSar, t.vwap, t.anchoredVwap, t.ichimoku,
    t.keltner, t.donchian, t.pivotPoints, t.volumeProfile, t.marketProfile, t.supportResistance,
    t.fibonacci, t.confluence, t.orderBlocks, t.fairValueGaps, t.liquidity, t.sessions, t.structure,
    t.litX, t.lit, t.sms, t.smt, t.tradePro, t.binary3m, t.rsi, t.rsiOrderFlow, t.macd, t.volume, t.stochastic,
    t.obv, t.moneyFlowIndex,
).count { it } + if (t.activeStrategy != null || t.activeBlueprintId != null || t.allStrategies) 1 else 0

private fun activeReadinessIssues(
    t: IndicatorToggles,
    candleCount: Int,
    barMode: ChartBarMode,
): List<String> {
    val active = buildList {
        if (t.ema) add(ChartStudyId.EMA)
        if (t.bollinger) add(ChartStudyId.BOLLINGER)
        if (t.superTrend) add(ChartStudyId.SUPER_TREND)
        if (t.parabolicSar) add(ChartStudyId.PARABOLIC_SAR)
        if (t.vwap) add(ChartStudyId.VWAP)
        if (t.anchoredVwap) add(ChartStudyId.ANCHORED_VWAP)
        if (t.ichimoku) add(ChartStudyId.ICHIMOKU)
        if (t.keltner) add(ChartStudyId.KELTNER)
        if (t.donchian) add(ChartStudyId.DONCHIAN)
        if (t.pivotPoints) add(ChartStudyId.PIVOTS)
        if (t.volumeProfile) add(ChartStudyId.VOLUME_PROFILE)
        if (t.marketProfile) add(ChartStudyId.MARKET_PROFILE)
        if (t.supportResistance) add(ChartStudyId.SUPPORT_RESISTANCE)
        if (t.fibonacci) add(ChartStudyId.FIBONACCI)
        if (t.confluence) add(ChartStudyId.CONFLUENCE)
        if (t.orderBlocks) add(ChartStudyId.ORDER_BLOCKS)
        if (t.fairValueGaps) add(ChartStudyId.FAIR_VALUE_GAPS)
        if (t.liquidity) add(ChartStudyId.LIQUIDITY)
        if (t.sessions) add(ChartStudyId.SESSIONS)
        if (t.structure) add(ChartStudyId.STRUCTURE)
        if (t.litX) add(ChartStudyId.LITX)
        if (t.lit) add(ChartStudyId.LIT)
        if (t.sms) add(ChartStudyId.SMS)
        if (t.smt) add(ChartStudyId.SMT)
        if (t.tradePro) add(ChartStudyId.TRADE_PRO)
        if (t.binary3m) add(ChartStudyId.BINARY_3M)
        if (t.rsi) add(ChartStudyId.RSI)
        if (t.rsiOrderFlow) add(ChartStudyId.RSI_ORDER_FLOW)
        if (t.macd) add(ChartStudyId.MACD)
        if (t.volume) add(ChartStudyId.VOLUME)
        if (t.stochastic) add(ChartStudyId.STOCHASTIC)
        if (t.obv) add(ChartStudyId.OBV)
        if (t.moneyFlowIndex) add(ChartStudyId.MFI)
    }
    return active.mapNotNull { study ->
        val status = IndicatorReadinessCatalog.status(study, candleCount, barMode)
        if (status.level == IndicatorReadinessLevel.READY) null else "${study.label}: ${status.label}"
    }
}

private const val TOGGLE_DEBOUNCE_MS = 90L
