package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.SmcVisualMode
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.ui.components.FoxChip
import com.foxtrader.app.ui.theme.FoxTheme

/**
 * Grouped indicator manager.
 *
 * Institutional engines are exposed as suites rather than isolated switches:
 * LiTX/LiT/SMT need structure/liquidity context to be useful on a chart, so
 * enabling one of those engines also enables the visual layers it depends on.
 */
@Composable
fun IndicatorPanel(
    visible: Boolean,
    toggles: IndicatorToggles,
    strategyBlueprints: List<StrategyBlueprint> = emptyList(),
    onToggle: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FoxTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = FoxTheme.spacing.md, vertical = FoxTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.sm),
        ) {
            Group("Premium presets") {
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
            }

            if (toggles.anyActive) {
                Group("Quick actions") {
                    Chip("Clear all", active = false) {
                        onToggle { current ->
                            IndicatorToggles(smcVisualMode = current.smcVisualMode)
                        }
                    }
                }
            }

            Group("Trend") {
                Chip(stringResource(R.string.chart_indicator_ema), toggles.ema) {
                    onToggle { it.copy(ema = !it.ema) }
                }
                Chip(stringResource(R.string.chart_indicator_supertrend), toggles.superTrend) {
                    onToggle { it.copy(superTrend = !it.superTrend) }
                }
                Chip(stringResource(R.string.chart_indicator_ichimoku), toggles.ichimoku) {
                    onToggle { it.copy(ichimoku = !it.ichimoku) }
                }
                Chip(stringResource(R.string.chart_indicator_psar), toggles.parabolicSar) {
                    onToggle { it.copy(parabolicSar = !it.parabolicSar) }
                }
            }

            Group("Momentum") {
                Chip("RSI", toggles.rsi) { onToggle { it.copy(rsi = !it.rsi) } }
                Chip("MACD", toggles.macd) { onToggle { it.copy(macd = !it.macd) } }
                Chip("Stochastic", toggles.stochastic) { onToggle { it.copy(stochastic = !it.stochastic) } }
            }

            Group("Volatility") {
                Chip(stringResource(R.string.chart_indicator_bollinger), toggles.bollinger) {
                    onToggle { it.copy(bollinger = !it.bollinger) }
                }
                Chip("Keltner", toggles.keltner) { onToggle { it.copy(keltner = !it.keltner) } }
                Chip("Donchian", toggles.donchian) { onToggle { it.copy(donchian = !it.donchian) } }
            }

            Group("Volume") {
                Chip(stringResource(R.string.chart_pane_volume_title), toggles.volume) {
                    onToggle { it.copy(volume = !it.volume) }
                }
                Chip(stringResource(R.string.chart_indicator_vwap), toggles.vwap) {
                    onToggle { it.copy(vwap = !it.vwap) }
                }
                Chip("Anchored VWAP", toggles.anchoredVwap) {
                    onToggle { it.copy(anchoredVwap = !it.anchoredVwap) }
                }
                Chip("OBV", toggles.obv) { onToggle { it.copy(obv = !it.obv) } }
                Chip("MFI", toggles.moneyFlowIndex) { onToggle { it.copy(moneyFlowIndex = !it.moneyFlowIndex) } }
                Chip(stringResource(R.string.chart_indicator_volume_profile), toggles.volumeProfile) {
                    onToggle { it.copy(volumeProfile = !it.volumeProfile) }
                }
                Chip(stringResource(R.string.chart_indicator_market_profile), toggles.marketProfile) {
                    onToggle { it.copy(marketProfile = !it.marketProfile) }
                }
            }

            Group("Market structure") {
                Chip(stringResource(R.string.chart_indicator_structure), toggles.structure) {
                    onToggle { it.copy(structure = !it.structure) }
                }
                Chip(stringResource(R.string.chart_indicator_support_resistance), toggles.supportResistance) {
                    onToggle { it.copy(supportResistance = !it.supportResistance) }
                }
                Chip(stringResource(R.string.chart_indicator_fibonacci), toggles.fibonacci) {
                    onToggle { it.copy(fibonacci = !it.fibonacci) }
                }
                Chip(stringResource(R.string.chart_indicator_sessions), toggles.sessions) {
                    onToggle { it.copy(sessions = !it.sessions) }
                }
                Chip("Pivots", toggles.pivotPoints) { onToggle { it.copy(pivotPoints = !it.pivotPoints) } }
                Chip(stringResource(R.string.chart_indicator_confluence), toggles.confluence) {
                    onToggle { it.copy(confluence = !it.confluence) }
                }
            }

            Group("Smart money") {
                Chip("SMC", toggles.smcSuiteActive) {
                    onToggle { it.withSmcSuite(!it.smcSuiteActive) }
                }
                Chip(stringResource(R.string.chart_indicator_order_blocks), toggles.orderBlocks) {
                    onToggle { it.copy(orderBlocks = !it.orderBlocks) }
                }
                Chip(stringResource(R.string.chart_indicator_fvg), toggles.fairValueGaps) {
                    onToggle { it.copy(fairValueGaps = !it.fairValueGaps) }
                }
                Chip(stringResource(R.string.chart_indicator_liquidity), toggles.liquidity) {
                    onToggle { it.copy(liquidity = !it.liquidity) }
                }
                Chip("LiTX", toggles.litX) {
                    onToggle { it.withLitXSuite(!it.litX) }
                }
                Chip("LiT", toggles.lit) {
                    onToggle { it.withLitSuite(!it.lit) }
                }
                Chip("SMS", toggles.sms) {
                    onToggle { it.withSmsSuite(!it.sms) }
                }
                Chip("SMT", toggles.smt) {
                    onToggle { it.withSmtSuite(!it.smt) }
                }
                Chip("TradePro", toggles.tradePro) {
                    onToggle { it.withTradeProSuite(!it.tradePro) }
                }
            }

            Group("Strategy signals") {
                Chip("Deriv 3m (M1)", toggles.binary3m) {
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
                        it.copy(
                            binary3m = false,
                            activeStrategy = null,
                            activeBlueprintId = null,
                            allStrategies = false,
                        )
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
                    Chip("My: ${blueprint.name}", toggles.activeBlueprintId == blueprint.id) {
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

            Group("Visual intensity") {
                SmcVisualMode.entries.forEach { mode ->
                    Chip(mode.label, toggles.smcVisualMode == mode) {
                        onToggle { it.copy(smcVisualMode = mode) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xxs)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = FoxTheme.colors.textMuted)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs),
        ) { content() }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    FoxChip(label = label, selected = active, onClick = onClick)
}
