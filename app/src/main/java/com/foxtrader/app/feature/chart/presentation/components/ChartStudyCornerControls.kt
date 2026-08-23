package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettings
import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettingsRegistry
import com.foxtrader.app.feature.chart.presentation.BollingerStudySettings
import com.foxtrader.app.feature.chart.presentation.ChartStudySettings
import com.foxtrader.app.feature.chart.presentation.DonchianStudySettings
import com.foxtrader.app.feature.chart.presentation.EmaStudySettings
import com.foxtrader.app.feature.chart.presentation.IchimokuStudySettings
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.feature.chart.presentation.KeltnerStudySettings
import com.foxtrader.app.feature.chart.presentation.MacdStudySettings
import com.foxtrader.app.feature.chart.presentation.MfiStudySettings
import com.foxtrader.app.feature.chart.presentation.ParabolicSarStudySettings
import com.foxtrader.app.feature.chart.presentation.RsiOrderFlowStudySettings
import com.foxtrader.app.feature.chart.presentation.RsiStudySettings
import com.foxtrader.app.feature.chart.presentation.StochasticStudySettings
import com.foxtrader.app.feature.chart.presentation.SuperTrendStudySettings
import com.foxtrader.app.ui.theme.FoxTheme
import java.util.Locale

/**
 * Compact TradingView-style active-study legend anchored inside the price chart.
 *
 * Every enabled chart study is represented by a chip. The gear opens a local
 * editor for parameters that actually feed the calculation engine; × removes
 * the study. Studies with no tunable numeric input still expose a small info /
 * remove card rather than pretending to have settings that do nothing.
 */
@Composable
fun ChartStudyCornerControls(
    toggles: IndicatorToggles,
    onChange: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = remember(toggles) { activeStudies(toggles) }
    if (active.isEmpty()) return

    var editing by remember { mutableStateOf<StudyControlId?>(null) }
    if (editing != null && active.none { it.id == editing }) editing = null

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            active.forEach { study ->
                ActiveStudyChip(
                    label = study.label,
                    selected = editing == study.id,
                    onEdit = { editing = if (editing == study.id) null else study.id },
                    onRemove = { removeStudy(study.id, onChange) },
                )
            }
        }

        editing?.let { id ->
            StudySettingsCard(
                id = id,
                toggles = toggles,
                onChange = onChange,
                onClose = { editing = null },
            )
        }
    }
}

@Composable
private fun ActiveStudyChip(
    label: String,
    selected: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = FoxTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accentMuted else colors.surface.copy(alpha = 0.90f))
            .padding(start = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.accent else colors.textPrimary,
        )
        Text(
            text = " ⚙ ",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 5.dp),
        )
        Text(
            text = "×",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary,
            modifier = Modifier.clickable(onClick = onRemove).padding(horizontal = 7.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun StudySettingsCard(
    id: StudyControlId,
    toggles: IndicatorToggles,
    onChange: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    onClose: () -> Unit,
) {
    val colors = FoxTheme.colors
    val settings = toggles.settings.sanitized()
    val runtimeStrategySettings by StrategyRuntimeSettingsRegistry.state.collectAsState()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceElevated.copy(alpha = 0.98f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.width(286.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = id.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
                )
            }

            when (id) {
                StudyControlId.EMA -> {
                    IntStepper("Fast", settings.ema.fastPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(ema = it.ema.copy(fastPeriod = value).sanitized()) }
                    }
                    IntStepper("Slow", settings.ema.slowPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(ema = it.ema.copy(slowPeriod = value).sanitized()) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(ema = EmaStudySettings()) } }
                }

                StudyControlId.RSI -> {
                    IntStepper("Period", settings.rsi.period, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(rsi = it.rsi.copy(period = value)) }
                    }
                    DoubleStepper("Overbought", settings.rsi.overbought, 1.0, 51.0, 99.0) { value ->
                        updateSettings(onChange) { it.copy(rsi = it.rsi.copy(overbought = value).sanitized()) }
                    }
                    DoubleStepper("Oversold", settings.rsi.oversold, 1.0, 1.0, 49.0) { value ->
                        updateSettings(onChange) { it.copy(rsi = it.rsi.copy(oversold = value).sanitized()) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(rsi = RsiStudySettings()) } }
                }

                StudyControlId.RSI_ORDER_FLOW -> {
                    IntStepper("RSI period", settings.rsiOrderFlow.rsiPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(rsiPeriod = value)) }
                    }
                    IntStepper("Flow period", settings.rsiOrderFlow.flowPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(flowPeriod = value)) }
                    }
                    IntStepper("Smoothing", settings.rsiOrderFlow.flowSmoothing, 1, 100) { value ->
                        updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(flowSmoothing = value)) }
                    }
                    IntStepper("Pivot L", settings.rsiOrderFlow.pivotLeft, 1, 25) { value ->
                        updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(pivotLeft = value)) }
                    }
                    IntStepper("Pivot R", settings.rsiOrderFlow.pivotRight, 1, 25) { value ->
                        updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(pivotRight = value)) }
                    }
                    ToggleRow("Hidden divergence", settings.rsiOrderFlow.includeHidden) { enabled ->
                        updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(includeHidden = enabled)) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(rsiOrderFlow = RsiOrderFlowStudySettings()) } }
                }

                StudyControlId.MACD -> {
                    IntStepper("Fast", settings.macd.fastPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(macd = it.macd.copy(fastPeriod = value).sanitized()) }
                    }
                    IntStepper("Slow", settings.macd.slowPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(macd = it.macd.copy(slowPeriod = value).sanitized()) }
                    }
                    IntStepper("Signal", settings.macd.signalPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(macd = it.macd.copy(signalPeriod = value)) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(macd = MacdStudySettings()) } }
                }

                StudyControlId.BOLLINGER -> {
                    IntStepper("Period", settings.bollinger.period, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(bollinger = it.bollinger.copy(period = value)) }
                    }
                    DoubleStepper("Deviation", settings.bollinger.multiplier, 0.1, 0.1, 10.0) { value ->
                        updateSettings(onChange) { it.copy(bollinger = it.bollinger.copy(multiplier = value)) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(bollinger = BollingerStudySettings()) } }
                }

                StudyControlId.SUPER_TREND -> {
                    IntStepper("ATR period", settings.superTrend.atrPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(superTrend = it.superTrend.copy(atrPeriod = value)) }
                    }
                    DoubleStepper("Factor", settings.superTrend.multiplier, 0.1, 0.1, 20.0) { value ->
                        updateSettings(onChange) { it.copy(superTrend = it.superTrend.copy(multiplier = value)) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(superTrend = SuperTrendStudySettings()) } }
                }

                StudyControlId.STOCHASTIC -> {
                    IntStepper("K period", settings.stochastic.kPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(stochastic = it.stochastic.copy(kPeriod = value)) }
                    }
                    IntStepper("D period", settings.stochastic.dPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(stochastic = it.stochastic.copy(dPeriod = value)) }
                    }
                    DoubleStepper("Overbought", settings.stochastic.overbought, 1.0, 51.0, 99.0) { value ->
                        updateSettings(onChange) { it.copy(stochastic = it.stochastic.copy(overbought = value).sanitized()) }
                    }
                    DoubleStepper("Oversold", settings.stochastic.oversold, 1.0, 1.0, 49.0) { value ->
                        updateSettings(onChange) { it.copy(stochastic = it.stochastic.copy(oversold = value).sanitized()) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(stochastic = StochasticStudySettings()) } }
                }

                StudyControlId.KELTNER -> {
                    IntStepper("EMA", settings.keltner.emaPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(keltner = it.keltner.copy(emaPeriod = value)) }
                    }
                    IntStepper("ATR", settings.keltner.atrPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(keltner = it.keltner.copy(atrPeriod = value)) }
                    }
                    DoubleStepper("Factor", settings.keltner.multiplier, 0.1, 0.1, 20.0) { value ->
                        updateSettings(onChange) { it.copy(keltner = it.keltner.copy(multiplier = value)) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(keltner = KeltnerStudySettings()) } }
                }

                StudyControlId.DONCHIAN -> {
                    IntStepper("Period", settings.donchian.period, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(donchian = it.donchian.copy(period = value)) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(donchian = DonchianStudySettings()) } }
                }

                StudyControlId.ICHIMOKU -> {
                    IntStepper("Tenkan", settings.ichimoku.tenkanPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(ichimoku = it.ichimoku.copy(tenkanPeriod = value)) }
                    }
                    IntStepper("Kijun", settings.ichimoku.kijunPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(ichimoku = it.ichimoku.copy(kijunPeriod = value)) }
                    }
                    IntStepper("Span B", settings.ichimoku.senkouBPeriod, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(ichimoku = it.ichimoku.copy(senkouBPeriod = value)) }
                    }
                    IntStepper("Displace", settings.ichimoku.displacement, 0, 500) { value ->
                        updateSettings(onChange) { it.copy(ichimoku = it.ichimoku.copy(displacement = value)) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(ichimoku = IchimokuStudySettings()) } }
                }

                StudyControlId.PARABOLIC_SAR -> {
                    DoubleStepper("Start", settings.parabolicSar.accelerationStart, 0.01, 0.001, 1.0) { value ->
                        updateSettings(onChange) { it.copy(parabolicSar = it.parabolicSar.copy(accelerationStart = value).sanitized()) }
                    }
                    DoubleStepper("Step", settings.parabolicSar.accelerationStep, 0.01, 0.001, 1.0) { value ->
                        updateSettings(onChange) { it.copy(parabolicSar = it.parabolicSar.copy(accelerationStep = value).sanitized()) }
                    }
                    DoubleStepper("Max", settings.parabolicSar.accelerationMax, 0.05, 0.001, 2.0) { value ->
                        updateSettings(onChange) { it.copy(parabolicSar = it.parabolicSar.copy(accelerationMax = value).sanitized()) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(parabolicSar = ParabolicSarStudySettings()) } }
                }

                StudyControlId.MFI -> {
                    IntStepper("Period", settings.mfi.period, 1, 500) { value ->
                        updateSettings(onChange) { it.copy(mfi = it.mfi.copy(period = value)) }
                    }
                    DoubleStepper("Overbought", settings.mfi.overbought, 1.0, 51.0, 99.0) { value ->
                        updateSettings(onChange) { it.copy(mfi = it.mfi.copy(overbought = value).sanitized()) }
                    }
                    DoubleStepper("Oversold", settings.mfi.oversold, 1.0, 1.0, 49.0) { value ->
                        updateSettings(onChange) { it.copy(mfi = it.mfi.copy(oversold = value).sanitized()) }
                    }
                    ResetButton { updateSettings(onChange) { it.copy(mfi = MfiStudySettings()) } }
                }

                StudyControlId.STRATEGY -> {
                    val type = toggles.activeStrategy
                    if (type == null) {
                        Text(
                            text = "No built-in strategy is active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    } else {
                        val strategySettings = (runtimeStrategySettings[type] ?: StrategyRuntimeSettings()).sanitized()
                        Text(
                            text = type.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                        )
                        ToggleRow("Bullish signals", strategySettings.allowBullish) { enabled ->
                            StrategyRuntimeSettingsRegistry.update(type) { it.copy(allowBullish = enabled) }
                        }
                        ToggleRow("Bearish signals", strategySettings.allowBearish) { enabled ->
                            StrategyRuntimeSettingsRegistry.update(type) { it.copy(allowBearish = enabled) }
                        }
                        IntStepper("Min confidence", strategySettings.minimumConfidence, 0, 100) { value ->
                            StrategyRuntimeSettingsRegistry.update(type) { it.copy(minimumConfidence = value) }
                        }
                        DoubleStepper("Min R:R", strategySettings.minimumRiskReward, 0.25, 0.0, 10.0) { value ->
                            StrategyRuntimeSettingsRegistry.update(type) { it.copy(minimumRiskReward = value) }
                        }
                        DoubleStepper("Target R:R", strategySettings.targetRiskReward, 0.5, 0.0, 10.0) { value ->
                            StrategyRuntimeSettingsRegistry.update(type) { it.copy(targetRiskReward = value) }
                        }
                        Text(
                            text = "Target R:R = 0 keeps the strategy's canonical target. These controls are shared by live signals and backtests.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        ResetButton { StrategyRuntimeSettingsRegistry.reset(type) }
                    }
                }

                StudyControlId.ALL_STRATEGIES -> {
                    Text(
                        text = "All-strategies mode uses each strategy's own saved runtime controls. Select one strategy to edit its direction, confidence and R:R filters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }

                StudyControlId.CUSTOM_STRATEGY -> {
                    Text(
                        text = "This saved Builder strategy uses its own canonical conditions and risk action. Edit those inputs in Strategy Builder so chart and backtest stay identical.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }

                else -> {
                    Text(
                        text = "This study has no numeric chart parameters. Visibility and removal are controlled from this chart-corner row.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntStepper(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
    SettingRow(label, value.toString(), { onValue((value - 1).coerceAtLeast(min)) }, { onValue((value + 1).coerceAtMost(max)) })
}

@Composable
private fun DoubleStepper(
    label: String,
    value: Double,
    step: Double,
    min: Double,
    max: Double,
    onValue: (Double) -> Unit,
) {
    SettingRow(
        label = label,
        value = String.format(Locale.US, if (step < 0.01) "%.3f" else if (step < 0.1) "%.2f" else "%.1f", value),
        onMinus = { onValue((value - step).coerceAtLeast(min)) },
        onPlus = { onValue((value + step).coerceAtMost(max)) },
    )
}

@Composable
private fun SettingRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val colors = FoxTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text("−", modifier = Modifier.clickable(onClick = onMinus).padding(horizontal = 10.dp, vertical = 4.dp), color = colors.accent)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text("+", modifier = Modifier.clickable(onClick = onPlus).padding(horizontal = 10.dp, vertical = 4.dp), color = colors.accent)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val colors = FoxTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ResetButton(onReset: () -> Unit) {
    Button(
        onClick = onReset,
        colors = ButtonDefaults.buttonColors(containerColor = FoxTheme.colors.surfaceStrong),
    ) {
        Text("Reset defaults", color = FoxTheme.colors.textPrimary, style = MaterialTheme.typography.labelSmall)
    }
}

private inline fun updateSettings(
    noinline onChange: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    crossinline transform: (ChartStudySettings) -> ChartStudySettings,
) {
    onChange { toggles -> toggles.copy(settings = transform(toggles.settings).sanitized()) }
}

private data class ActiveStudy(val id: StudyControlId, val label: String)

private fun activeStudies(t: IndicatorToggles): List<ActiveStudy> = buildList {
    if (t.ema) add(ActiveStudy(StudyControlId.EMA, "EMA ${t.settings.ema.sanitized().fastPeriod}/${t.settings.ema.sanitized().slowPeriod}"))
    if (t.bollinger) add(ActiveStudy(StudyControlId.BOLLINGER, "BB"))
    if (t.superTrend) add(ActiveStudy(StudyControlId.SUPER_TREND, "SuperTrend"))
    if (t.parabolicSar) add(ActiveStudy(StudyControlId.PARABOLIC_SAR, "PSAR"))
    if (t.vwap) add(ActiveStudy(StudyControlId.VWAP, "VWAP"))
    if (t.anchoredVwap) add(ActiveStudy(StudyControlId.ANCHORED_VWAP, "A-VWAP"))
    if (t.ichimoku) add(ActiveStudy(StudyControlId.ICHIMOKU, "Ichimoku"))
    if (t.keltner) add(ActiveStudy(StudyControlId.KELTNER, "Keltner"))
    if (t.donchian) add(ActiveStudy(StudyControlId.DONCHIAN, "Donchian"))
    if (t.pivotPoints) add(ActiveStudy(StudyControlId.PIVOTS, "Pivots"))
    if (t.volumeProfile) add(ActiveStudy(StudyControlId.VOLUME_PROFILE, "Vol Profile"))
    if (t.marketProfile) add(ActiveStudy(StudyControlId.MARKET_PROFILE, "Mkt Profile"))
    if (t.supportResistance) add(ActiveStudy(StudyControlId.SUPPORT_RESISTANCE, "S/R"))
    if (t.fibonacci) add(ActiveStudy(StudyControlId.FIBONACCI, "Auto Fib"))
    if (t.confluence) add(ActiveStudy(StudyControlId.CONFLUENCE, "Confluence"))
    if (t.orderBlocks) add(ActiveStudy(StudyControlId.ORDER_BLOCKS, "OB"))
    if (t.fairValueGaps) add(ActiveStudy(StudyControlId.FVG, "FVG"))
    if (t.liquidity) add(ActiveStudy(StudyControlId.LIQUIDITY, "Liquidity"))
    if (t.sessions) add(ActiveStudy(StudyControlId.SESSIONS, "Sessions"))
    if (t.structure) add(ActiveStudy(StudyControlId.STRUCTURE, "Structure"))
    if (t.litX) add(ActiveStudy(StudyControlId.LITX, "LiTX"))
    if (t.lit) add(ActiveStudy(StudyControlId.LIT, "LiT"))
    if (t.sms) add(ActiveStudy(StudyControlId.SMS, "SMS"))
    if (t.smt) add(ActiveStudy(StudyControlId.SMT, "SMT"))
    if (t.tradePro) add(ActiveStudy(StudyControlId.TRADE_PRO, "TradePro"))
    if (t.binary3m) add(ActiveStudy(StudyControlId.BINARY_3M, "Deriv 3m"))
    if (t.rsi) add(ActiveStudy(StudyControlId.RSI, "RSI ${t.settings.rsi.sanitized().period}"))
    if (t.rsiOrderFlow) add(ActiveStudy(StudyControlId.RSI_ORDER_FLOW, "RSI OF"))
    if (t.macd) add(ActiveStudy(StudyControlId.MACD, "MACD"))
    if (t.volume) add(ActiveStudy(StudyControlId.VOLUME, "Volume"))
    if (t.stochastic) add(ActiveStudy(StudyControlId.STOCHASTIC, "Stoch"))
    if (t.obv) add(ActiveStudy(StudyControlId.OBV, "OBV"))
    if (t.moneyFlowIndex) add(ActiveStudy(StudyControlId.MFI, "MFI"))
    if (t.activeStrategy != null) add(ActiveStudy(StudyControlId.STRATEGY, "Strategy: ${t.activeStrategy.name.replace('_', ' ')}"))
    if (t.activeBlueprintId != null) add(ActiveStudy(StudyControlId.CUSTOM_STRATEGY, "Custom strategy"))
    if (t.allStrategies) add(ActiveStudy(StudyControlId.ALL_STRATEGIES, "All strategies"))
}

private fun removeStudy(
    id: StudyControlId,
    onChange: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
) = onChange { t ->
    when (id) {
        StudyControlId.EMA -> t.copy(ema = false)
        StudyControlId.BOLLINGER -> t.copy(bollinger = false)
        StudyControlId.SUPER_TREND -> t.copy(superTrend = false)
        StudyControlId.PARABOLIC_SAR -> t.copy(parabolicSar = false)
        StudyControlId.VWAP -> t.copy(vwap = false)
        StudyControlId.ANCHORED_VWAP -> t.copy(anchoredVwap = false)
        StudyControlId.ICHIMOKU -> t.copy(ichimoku = false)
        StudyControlId.KELTNER -> t.copy(keltner = false)
        StudyControlId.DONCHIAN -> t.copy(donchian = false)
        StudyControlId.PIVOTS -> t.copy(pivotPoints = false)
        StudyControlId.VOLUME_PROFILE -> t.copy(volumeProfile = false)
        StudyControlId.MARKET_PROFILE -> t.copy(marketProfile = false)
        StudyControlId.SUPPORT_RESISTANCE -> t.copy(supportResistance = false)
        StudyControlId.FIBONACCI -> t.copy(fibonacci = false)
        StudyControlId.CONFLUENCE -> t.copy(confluence = false)
        StudyControlId.ORDER_BLOCKS -> t.copy(orderBlocks = false)
        StudyControlId.FVG -> t.copy(fairValueGaps = false)
        StudyControlId.LIQUIDITY -> t.copy(liquidity = false)
        StudyControlId.SESSIONS -> t.copy(sessions = false)
        StudyControlId.STRUCTURE -> t.copy(structure = false)
        StudyControlId.LITX -> t.copy(litX = false)
        StudyControlId.LIT -> t.copy(lit = false)
        StudyControlId.SMS -> t.copy(sms = false)
        StudyControlId.SMT -> t.copy(smt = false)
        StudyControlId.TRADE_PRO -> t.copy(tradePro = false)
        StudyControlId.BINARY_3M -> t.copy(binary3m = false)
        StudyControlId.RSI -> t.copy(rsi = false)
        StudyControlId.RSI_ORDER_FLOW -> t.copy(rsiOrderFlow = false)
        StudyControlId.MACD -> t.copy(macd = false)
        StudyControlId.VOLUME -> t.copy(volume = false)
        StudyControlId.STOCHASTIC -> t.copy(stochastic = false)
        StudyControlId.OBV -> t.copy(obv = false)
        StudyControlId.MFI -> t.copy(moneyFlowIndex = false)
        StudyControlId.STRATEGY -> t.copy(activeStrategy = null)
        StudyControlId.CUSTOM_STRATEGY -> t.copy(activeBlueprintId = null)
        StudyControlId.ALL_STRATEGIES -> t.copy(allStrategies = false)
    }
}

private enum class StudyControlId(val title: String) {
    EMA("EMA settings"), BOLLINGER("Bollinger settings"), SUPER_TREND("SuperTrend settings"),
    PARABOLIC_SAR("Parabolic SAR settings"), VWAP("VWAP"), ANCHORED_VWAP("Anchored VWAP"),
    ICHIMOKU("Ichimoku settings"), KELTNER("Keltner settings"), DONCHIAN("Donchian settings"),
    PIVOTS("Pivot Points"), VOLUME_PROFILE("Volume Profile"), MARKET_PROFILE("Market Profile"),
    SUPPORT_RESISTANCE("Support / Resistance"), FIBONACCI("Auto Fibonacci"), CONFLUENCE("Confluence"),
    ORDER_BLOCKS("Order Blocks"), FVG("Fair Value Gaps"), LIQUIDITY("Liquidity"), SESSIONS("Sessions"),
    STRUCTURE("Market Structure"), LITX("LiTX"), LIT("LiT"), SMS("SMS"), SMT("SMT"),
    TRADE_PRO("TradePro"), BINARY_3M("Deriv 3m"), RSI("RSI settings"),
    RSI_ORDER_FLOW("RSI OrderFlow settings"), MACD("MACD settings"), VOLUME("Volume"),
    STOCHASTIC("Stochastic settings"), OBV("OBV"), MFI("MFI settings"), STRATEGY("Strategy settings"),
    CUSTOM_STRATEGY("Custom strategy"), ALL_STRATEGIES("All strategies"),
}
