package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.SmcVisualMode
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.feature.chart.presentation.ChartStudyId
import com.foxtrader.app.feature.chart.presentation.IndicatorReadinessCatalog
import com.foxtrader.app.feature.chart.presentation.IndicatorReadinessLevel
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.components.FoxChip
import com.foxtrader.app.ui.theme.FoxTheme

/**
 * Premium indicator command center.
 *
 * The important UX contract is that an enabled-but-not-yet-renderable study can
 * no longer look silently broken. Active studies expose their runtime readiness
 * (missing bars or incompatible bar mode), while institutional suites continue
 * to enable the visual dependencies they require.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndicatorPanel(
    visible: Boolean,
    toggles: IndicatorToggles,
    strategyBlueprints: List<StrategyBlueprint> = emptyList(),
    candleCount: Int = 0,
    barMode: ChartBarMode = ChartBarMode.TIME,
    signalCount: Int = 0,
    onToggle: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FoxTheme.colors
    val activeCount = activeStudyCount(toggles)
    val readinessIssues = activeReadinessIssues(toggles, candleCount, barMode)
    val shape = RoundedCornerShape(FoxTheme.shapes.lg)

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it / 2 },
        exit = slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp)
                .padding(horizontal = FoxTheme.spacing.sm, vertical = FoxTheme.spacing.xs)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FoxTheme.spacing.md, vertical = FoxTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Indicator Command Center",
                        style = FoxTheme.type.h3,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "$activeCount active · $candleCount bars · $signalCount plotted signals",
                        style = FoxTheme.type.caption,
                        color = colors.textMuted,
                    )
                }
                Text(
                    text = if (readinessIssues.isEmpty()) "READY" else "${readinessIssues.size} WAITING",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (readinessIssues.isEmpty()) colors.success else colors.warning,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (readinessIssues.isNotEmpty()) {
                Text(
                    text = readinessIssues.take(4).joinToString("  ·  ") { it },
                    style = FoxTheme.type.caption,
                    color = colors.warning,
                )
            } else if (activeCount > 0) {
                Text(
                    text = "Enabled studies have enough history for this bar mode. Empty SMC zones simply mean no valid setup is present yet.",
                    style = FoxTheme.type.caption,
                    color = colors.textSecondary,
                )
            }

            FlowGroup("Premium presets") {
                Chip("Institutional", toggles.institutionalSuiteActive) {
                    onToggle { it.withInstitutionalSuite(!it.institutionalSuiteActive) }
                }
                Chip("SMC Pro", toggles.smcSuiteActive) {
                    onToggle { it.withSmcSuite(!it.smcSuiteActive) }
                }
                Chip("LiTX Pro", toggles.litX) {
                    onToggle { it.withLitXSuite(!it.litX) }
                }
                Chip("LiT Pro", toggles.lit) {
                    onToggle { it.withLitSuite(!it.lit) }
                }
                Chip("SMT Pro", toggles.smt) {
                    onToggle { it.withSmtSuite(!it.smt) }
                }
                Chip("Technical Pro", technicalSuiteActive(toggles)) {
                    onToggle { current ->
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
                    Chip("Clean chart", active = false) {
                        onToggle { current -> IndicatorToggles(smcVisualMode = current.smcVisualMode) }
                    }
                }
            }

            FlowGroup("Trend") {
                StudyChip(stringResource(R.string.chart_indicator_ema), ChartStudyId.EMA, toggles.ema, candleCount, barMode) {
                    onToggle { it.copy(ema = !it.ema) }
                }
                StudyChip(stringResource(R.string.chart_indicator_supertrend), ChartStudyId.SUPER_TREND, toggles.superTrend, candleCount, barMode) {
                    onToggle { it.copy(superTrend = !it.superTrend) }
                }
                StudyChip(stringResource(R.string.chart_indicator_ichimoku), ChartStudyId.ICHIMOKU, toggles.ichimoku, candleCount, barMode) {
                    onToggle { it.copy(ichimoku = !it.ichimoku) }
                }
                StudyChip(stringResource(R.string.chart_indicator_psar), ChartStudyId.PARABOLIC_SAR, toggles.parabolicSar, candleCount, barMode) {
                    onToggle { it.copy(parabolicSar = !it.parabolicSar) }
                }
            }

            FlowGroup("Momentum") {
                StudyChip("RSI", ChartStudyId.RSI, toggles.rsi, candleCount, barMode) { onToggle { it.copy(rsi = !it.rsi) } }
                StudyChip("MACD", ChartStudyId.MACD, toggles.macd, candleCount, barMode) { onToggle { it.copy(macd = !it.macd) } }
                StudyChip("Stochastic", ChartStudyId.STOCHASTIC, toggles.stochastic, candleCount, barMode) { onToggle { it.copy(stochastic = !it.stochastic) } }
            }

            FlowGroup("Volatility") {
                StudyChip(stringResource(R.string.chart_indicator_bollinger), ChartStudyId.BOLLINGER, toggles.bollinger, candleCount, barMode) {
                    onToggle { it.copy(bollinger = !it.bollinger) }
                }
                StudyChip("Keltner", ChartStudyId.KELTNER, toggles.keltner, candleCount, barMode) { onToggle { it.copy(keltner = !it.keltner) } }
                StudyChip("Donchian", ChartStudyId.DONCHIAN, toggles.donchian, candleCount, barMode) { onToggle { it.copy(donchian = !it.donchian) } }
            }

            FlowGroup("Volume") {
                StudyChip(stringResource(R.string.chart_pane_volume_title), ChartStudyId.VOLUME, toggles.volume, candleCount, barMode) {
                    onToggle { it.copy(volume = !it.volume) }
                }
                StudyChip(stringResource(R.string.chart_indicator_vwap), ChartStudyId.VWAP, toggles.vwap, candleCount, barMode) {
                    onToggle { it.copy(vwap = !it.vwap) }
                }
                StudyChip("Anchored VWAP", ChartStudyId.ANCHORED_VWAP, toggles.anchoredVwap, candleCount, barMode) {
                    onToggle { it.copy(anchoredVwap = !it.anchoredVwap) }
                }
                StudyChip("OBV", ChartStudyId.OBV, toggles.obv, candleCount, barMode) { onToggle { it.copy(obv = !it.obv) } }
                StudyChip("MFI", ChartStudyId.MFI, toggles.moneyFlowIndex, candleCount, barMode) { onToggle { it.copy(moneyFlowIndex = !it.moneyFlowIndex) } }
                StudyChip(stringResource(R.string.chart_indicator_volume_profile), ChartStudyId.VOLUME_PROFILE, toggles.volumeProfile, candleCount, barMode) {
                    onToggle { it.copy(volumeProfile = !it.volumeProfile) }
                }
                StudyChip(stringResource(R.string.chart_indicator_market_profile), ChartStudyId.MARKET_PROFILE, toggles.marketProfile, candleCount, barMode) {
                    onToggle { it.copy(marketProfile = !it.marketProfile) }
                }
            }

            FlowGroup("Market structure") {
                StudyChip(stringResource(R.string.chart_indicator_structure), ChartStudyId.STRUCTURE, toggles.structure, candleCount, barMode) {
                    onToggle { it.copy(structure = !it.structure) }
                }
                StudyChip(stringResource(R.string.chart_indicator_support_resistance), ChartStudyId.SUPPORT_RESISTANCE, toggles.supportResistance, candleCount, barMode) {
                    onToggle { it.copy(supportResistance = !it.supportResistance) }
                }
                StudyChip(stringResource(R.string.chart_indicator_fibonacci), ChartStudyId.FIBONACCI, toggles.fibonacci, candleCount, barMode) {
                    onToggle { it.copy(fibonacci = !it.fibonacci) }
                }
                StudyChip(stringResource(R.string.chart_indicator_sessions), ChartStudyId.SESSIONS, toggles.sessions, candleCount, barMode) {
                    onToggle { it.copy(sessions = !it.sessions) }
                }
                StudyChip("Pivots", ChartStudyId.PIVOTS, toggles.pivotPoints, candleCount, barMode) { onToggle { it.copy(pivotPoints = !it.pivotPoints) } }
                StudyChip(stringResource(R.string.chart_indicator_confluence), ChartStudyId.CONFLUENCE, toggles.confluence, candleCount, barMode) {
                    onToggle { it.copy(confluence = !it.confluence) }
                }
            }

            FlowGroup("Smart money · institutional") {
                Chip("SMC Suite", toggles.smcSuiteActive) {
                    onToggle { it.withSmcSuite(!it.smcSuiteActive) }
                }
                StudyChip(stringResource(R.string.chart_indicator_order_blocks), ChartStudyId.ORDER_BLOCKS, toggles.orderBlocks, candleCount, barMode) {
                    onToggle { it.copy(orderBlocks = !it.orderBlocks) }
                }
                StudyChip(stringResource(R.string.chart_indicator_fvg), ChartStudyId.FAIR_VALUE_GAPS, toggles.fairValueGaps, candleCount, barMode) {
                    onToggle { it.copy(fairValueGaps = !it.fairValueGaps) }
                }
                StudyChip(stringResource(R.string.chart_indicator_liquidity), ChartStudyId.LIQUIDITY, toggles.liquidity, candleCount, barMode) {
                    onToggle { it.copy(liquidity = !it.liquidity) }
                }
                StudyChip("LiTX", ChartStudyId.LITX, toggles.litX, candleCount, barMode) {
                    onToggle { it.withLitXSuite(!it.litX) }
                }
                StudyChip("LiT", ChartStudyId.LIT, toggles.lit, candleCount, barMode) {
                    onToggle { it.withLitSuite(!it.lit) }
                }
                StudyChip("SMS", ChartStudyId.SMS, toggles.sms, candleCount, barMode) {
                    onToggle { it.withSmsSuite(!it.sms) }
                }
                StudyChip("SMT", ChartStudyId.SMT, toggles.smt, candleCount, barMode) {
                    onToggle { it.withSmtSuite(!it.smt) }
                }
                StudyChip("TradePro", ChartStudyId.TRADE_PRO, toggles.tradePro, candleCount, barMode) {
                    onToggle { it.withTradeProSuite(!it.tradePro) }
                }
            }

            FlowGroup("Strategy & template signals") {
                StudyChip("Deriv 3m (M1)", ChartStudyId.BINARY_3M, toggles.binary3m, candleCount, barMode) {
                    onToggle { current ->
                        val enable = !current.binary3m
                        current.copy(
                            binary3m = enable,
                            activeStrategy = if (enable) null else current.activeStrategy,
                            activeBlueprintId = if (enable) null else current.activeBlueprintId,
                            allStrategies = if (enable) false else current.allStrategies,
                        )
                    }
                }
                Chip(
                    "Off",
                    !toggles.binary3m && toggles.activeStrategy == null &&
                        toggles.activeBlueprintId == null && !toggles.allStrategies,
                ) {
                    onToggle {
                        it.copy(binary3m = false, activeStrategy = null, activeBlueprintId = null, allStrategies = false)
                    }
                }
                Chip("All built-in", toggles.allStrategies) {
                    onToggle {
                        it.copy(
                            binary3m = false,
                            allStrategies = !it.allStrategies,
                            activeStrategy = null,
                            activeBlueprintId = null,
                        )
                    }
                }
                StrategyType.entries.forEach { type ->
                    Chip(type.label, toggles.activeStrategy == type) {
                        onToggle { current ->
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
                        onToggle { current ->
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

            FlowGroup("Visual density") {
                SmcVisualMode.entries.forEach { mode ->
                    Chip(mode.label, toggles.smcVisualMode == mode) {
                        onToggle { it.copy(smcVisualMode = mode) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = FoxTheme.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
        )
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

private fun technicalSuiteActive(toggles: IndicatorToggles): Boolean =
    toggles.ema && toggles.bollinger && toggles.superTrend && toggles.vwap && toggles.rsi && toggles.macd

private fun activeStudyCount(t: IndicatorToggles): Int = listOf(
    t.ema, t.bollinger, t.superTrend, t.parabolicSar, t.vwap, t.anchoredVwap, t.ichimoku,
    t.keltner, t.donchian, t.pivotPoints, t.volumeProfile, t.marketProfile, t.supportResistance,
    t.fibonacci, t.confluence, t.orderBlocks, t.fairValueGaps, t.liquidity, t.sessions, t.structure,
    t.litX, t.lit, t.sms, t.smt, t.tradePro, t.binary3m, t.rsi, t.macd, t.volume, t.stochastic,
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
