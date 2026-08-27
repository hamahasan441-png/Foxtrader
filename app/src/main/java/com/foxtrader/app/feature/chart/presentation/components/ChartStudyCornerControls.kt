package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.SmtConfig
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
import com.foxtrader.app.feature.chart.presentation.AmdMode
import com.foxtrader.app.feature.chart.presentation.AmdStudySettings
import com.foxtrader.app.feature.chart.presentation.NascentDebugLevel
import com.foxtrader.app.feature.chart.presentation.NascentQuality
import com.foxtrader.app.feature.chart.presentation.NascentStudySettings
import com.foxtrader.app.feature.chart.presentation.PivotSweepDivergenceMode
import com.foxtrader.app.feature.chart.presentation.PivotSweepDivergenceStudySettings
import com.foxtrader.app.feature.chart.presentation.ValueAreaLiquidityRejectionMode
import com.foxtrader.app.feature.chart.presentation.ValueAreaLiquidityRejectionStudySettings
import com.foxtrader.app.feature.chart.presentation.ProductionAnalysisSystem
import com.foxtrader.app.feature.chart.presentation.RsiOrderFlowStudySettings
import com.foxtrader.app.feature.chart.presentation.RsiReversalEntryPreset
import com.foxtrader.app.feature.chart.presentation.RsiReversalStudySettings
import com.foxtrader.app.feature.chart.presentation.RsiStudySettings
import com.foxtrader.app.feature.chart.presentation.StochasticStudySettings
import com.foxtrader.app.feature.chart.presentation.SuperTrendStudySettings
import com.foxtrader.app.feature.chart.presentation.productionAnalysisSystem
import com.foxtrader.app.feature.chart.presentation.withProductionAnalysisSystem
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
    litXConfig: LitXConfig = LitXConfig(),
    litConfig: LitConfig = LitConfig(),
    smtConfig: SmtConfig = SmtConfig(),
    smsConfig: SmsConfig = SmsConfig(),
    onLitXConfigChange: (LitXConfig) -> Unit = {},
    onLitConfigChange: (LitConfig) -> Unit = {},
    onSmtConfigChange: (SmtConfig) -> Unit = {},
    onSmsConfigChange: (SmsConfig) -> Unit = {},
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
                litXConfig = litXConfig,
                litConfig = litConfig,
                smtConfig = smtConfig,
                smsConfig = smsConfig,
                onLitXConfigChange = onLitXConfigChange,
                onLitConfigChange = onLitConfigChange,
                onSmtConfigChange = onSmtConfigChange,
                onSmsConfigChange = onSmsConfigChange,
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
    litXConfig: LitXConfig,
    litConfig: LitConfig,
    smtConfig: SmtConfig,
    smsConfig: SmsConfig,
    onLitXConfigChange: (LitXConfig) -> Unit,
    onLitConfigChange: (LitConfig) -> Unit,
    onSmtConfigChange: (SmtConfig) -> Unit,
    onSmsConfigChange: (SmsConfig) -> Unit,
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
        modifier = Modifier.width(286.dp).heightIn(max = 460.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
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
                StudyControlId.LITX -> {
                    SignalProfileSelector("Preset", litXConfig.profile) { profile ->
                        onLitXConfigChange(LitXConfig.preset(profile, enabled = true))
                    }
                    LitXModeSelector(litXConfig.mode) { mode ->
                        onLitXConfigChange(
                            LitXConfig.preset(mode = mode, profile = litXConfig.profile, enabled = true)
                        )
                    }
                    IntStepper("Min confidence", litXConfig.minConfidenceScore, 50, 95) { value ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, minConfidenceScore = value).sanitized())
                    }
                    DoubleStepper("Min R:R", litXConfig.minRiskReward, 0.1, 1.0, 5.0) { value ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, minRiskReward = value).sanitized())
                    }
                    DoubleStepper("Displacement", litXConfig.displacementAtrMultiple, 0.05, 0.8, 3.0) { value ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, displacementAtrMultiple = value).sanitized())
                    }
                    ToggleRow("Require HTF alignment", litXConfig.requireHtfAlignment) { enabled ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, requireHtfAlignment = enabled).sanitized())
                    }
                    ToggleRow("Require displacement MSS", litXConfig.requireStrongMss) { enabled ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, requireStrongMss = enabled).sanitized())
                    }
                    ToggleRow("Require premium/discount", litXConfig.requireDirectionalZone) { enabled ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, requireDirectionalZone = enabled).sanitized())
                    }
                    IntStepper("Sweep → shift", litXConfig.maxSweepToShiftBars, 3, 30) { value ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, maxSweepToShiftBars = value).sanitized())
                    }
                    IntStepper("Shift → retest", litXConfig.maxShiftToRetestBars, 3, 40) { value ->
                        onLitXConfigChange(litXConfig.copy(enabled = true, maxShiftToRetestBars = value).sanitized())
                    }
                    Text(
                        text = "Logic modes: Sweep Reversal, Precision, Momentum and Sniper. Each emits only after its own closed-bar structural gates pass.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    SignalArrowNote()
                    ResetButton { onLitXConfigChange(LitXConfig.preset(litXConfig.profile, enabled = true)) }
                }

                StudyControlId.LIT -> {
                    SignalProfileSelector("Preset", litConfig.profile) { profile ->
                        onLitConfigChange(LitConfig.preset(profile))
                    }
                    IntStepper("Min confidence", litConfig.minConfidence, 50, 95) { value ->
                        onLitConfigChange(litConfig.copy(minConfidence = value).sanitized())
                    }
                    DoubleStepper("Min R:R", litConfig.minRiskReward, 0.1, 1.0, 5.0) { value ->
                        onLitConfigChange(litConfig.copy(minRiskReward = value).sanitized())
                    }
                    DoubleStepper("Displacement", litConfig.displacementAtrMultiple, 0.05, 0.8, 3.0) { value ->
                        onLitConfigChange(litConfig.copy(displacementAtrMultiple = value).sanitized())
                    }
                    IntStepper("Setup lookback", litConfig.setupLookback, 20, 180) { value ->
                        onLitConfigChange(litConfig.copy(setupLookback = value).sanitized())
                    }
                    IntStepper("Swing left", litConfig.swingLeftBars, 2, 8) { value ->
                        onLitConfigChange(litConfig.copy(swingLeftBars = value).sanitized())
                    }
                    IntStepper("Swing right", litConfig.swingRightBars, 2, 8) { value ->
                        onLitConfigChange(litConfig.copy(swingRightBars = value).sanitized())
                    }
                    ToggleRow("Require premium/discount", litConfig.requireDirectionalZone) { enabled ->
                        onLitConfigChange(litConfig.copy(requireDirectionalZone = enabled).sanitized())
                    }
                    ToggleRow("Require SCOB", litConfig.requireScob) { enabled ->
                        onLitConfigChange(litConfig.copy(requireScob = enabled).sanitized())
                    }
                    ToggleRow("POI divergence confirmation", litConfig.requirePoiDivergence) { enabled ->
                        onLitConfigChange(litConfig.copy(requirePoiDivergence = enabled).sanitized())
                    }
                    if (litConfig.requirePoiDivergence) {
                        IntStepper("Divergence RSI period", litConfig.poiDivergenceRsiPeriod, 2, 100) { value ->
                            onLitConfigChange(litConfig.copy(poiDivergenceRsiPeriod = value).sanitized())
                        }
                        IntStepper("Divergence lookback", litConfig.poiDivergenceLookbackBars, 10, 200) { value ->
                            onLitConfigChange(litConfig.copy(poiDivergenceLookbackBars = value).sanitized())
                        }
                        DoubleStepper("Min RSI gap", litConfig.poiDivergenceMinRsiGap, 0.5, 0.0, 30.0) { value ->
                            onLitConfigChange(litConfig.copy(poiDivergenceMinRsiGap = value).sanitized())
                        }
                    }
                    Text(
                        text = "Hard sequence: pullback → IDM sweep/reclaim → BOS → CHOCH + displacement → POI/SCOB → first retest" +
                            (if (litConfig.requirePoiDivergence) " → RSI divergence." else "."),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = "Signal-only: every update analyses the latest 400 closed candles; " +
                            "every confirmed BUY/SELL arrow stays on the chart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    SignalArrowNote()
                    ResetButton { onLitConfigChange(LitConfig.preset(litConfig.profile)) }
                }

                StudyControlId.SMT -> {
                    SignalProfileSelector("Preset", smtConfig.profile) { profile ->
                        onSmtConfigChange(SmtConfig.preset(profile))
                    }
                    IntStepper("Comparison period", smtConfig.period, 40, 600) { value ->
                        onSmtConfigChange(smtConfig.copy(period = value).sanitized())
                    }
                    IntStepper("Swing confirmation", smtConfig.swingLookback, 1, 10) { value ->
                        onSmtConfigChange(smtConfig.copy(swingLookback = value).sanitized())
                    }
                    DoubleStepper("Min correlation", smtConfig.minCorrelation, 0.05, 0.10, 0.95) { value ->
                        onSmtConfigChange(smtConfig.copy(minCorrelation = value).sanitized())
                    }
                    DoubleStepper("Min divergence", smtConfig.minDivergenceStrength, 0.01, 0.01, 1.0) { value ->
                        onSmtConfigChange(smtConfig.copy(minDivergenceStrength = value).sanitized())
                    }
                    IntStepper("Max signal age", smtConfig.maxSignalAgeBars, 1, 80) { value ->
                        onSmtConfigChange(smtConfig.copy(maxSignalAgeBars = value).sanitized())
                    }
                    IntStepper("Min confidence", smtConfig.minConfidence, 50, 95) { value ->
                        onSmtConfigChange(smtConfig.copy(minConfidence = value).sanitized())
                    }
                    SignalArrowNote()
                    ResetButton { onSmtConfigChange(SmtConfig.preset(smtConfig.profile)) }
                }

                StudyControlId.SMS -> {
                    SignalProfileSelector("Preset", smsConfig.profile) { profile ->
                        onSmsConfigChange(SmsConfig.preset(profile))
                    }
                    IntStepper("Swing confirmation", smsConfig.swingBars, 2, 12) { value ->
                        onSmsConfigChange(smsConfig.copy(swingBars = value).sanitized())
                    }
                    DoubleStepper("Displacement", smsConfig.displacementAtrMultiple, 0.05, 0.8, 3.0) { value ->
                        onSmsConfigChange(smsConfig.copy(displacementAtrMultiple = value).sanitized())
                    }
                    IntStepper("Sweep → shift", smsConfig.maxSweepToShiftBars, 2, 30) { value ->
                        onSmsConfigChange(smsConfig.copy(maxSweepToShiftBars = value).sanitized())
                    }
                    IntStepper("Min confidence", smsConfig.minConfidence, 50, 95) { value ->
                        onSmsConfigChange(smsConfig.copy(minConfidence = value).sanitized())
                    }
                    ToggleRow("Require liquidity sweep", smsConfig.requireLiquiditySweep) { enabled ->
                        onSmsConfigChange(smsConfig.copy(requireLiquiditySweep = enabled).sanitized())
                    }
                    ToggleRow("Require displacement", smsConfig.requireDisplacementForChoch) { enabled ->
                        onSmsConfigChange(smsConfig.copy(requireDisplacementForChoch = enabled).sanitized())
                    }
                    ResetButton { onSmsConfigChange(SmsConfig.preset(smsConfig.profile)) }
                }

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
IntStepper("Min pivot separation", settings.rsiOrderFlow.minPivotSeparation, 1, 250) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(minPivotSeparation = value).sanitized()) }
}
IntStepper("Max pivot separation", settings.rsiOrderFlow.maxPivotSeparation, settings.rsiOrderFlow.minPivotSeparation, 500) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(maxPivotSeparation = value).sanitized()) }
}
DoubleStepper("Min RSI difference", settings.rsiOrderFlow.minRsiDifference, 0.5, 0.0, 50.0) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(minRsiDifference = value).sanitized()) }
}
DoubleStepper("Min flow difference", settings.rsiOrderFlow.minFlowDifference, 0.5, 0.0, 50.0) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(minFlowDifference = value).sanitized()) }
}
IntStepper("Signal strength", settings.rsiOrderFlow.minStrength, 0, 100) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(minStrength = value).sanitized()) }
}
IntStepper("Risk lookback", settings.rsiOrderFlow.riskLookback, 1, 250) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(riskLookback = value).sanitized()) }
}
DoubleStepper("Stop range buffer", settings.rsiOrderFlow.stopBufferRangeMultiple, 0.05, 0.0, 5.0) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(stopBufferRangeMultiple = value).sanitized()) }
}
DoubleStepper("Reward : risk", settings.rsiOrderFlow.rewardRisk, 0.25, 0.25, 10.0) { value ->
    updateSettings(onChange) { it.copy(rsiOrderFlow = it.rsiOrderFlow.copy(rewardRisk = value).sanitized()) }
}
SignalArrowNote()
ResetButton { updateSettings(onChange) { it.copy(rsiOrderFlow = RsiOrderFlowStudySettings()) } }
                }

                StudyControlId.RSI_REVERSAL -> {
                    IntStepper("RSI length", settings.rsiReversal.rsiLength, 2, 100) { value ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(rsiLength = value).sanitized()) }
                    }
                    IntStepper("Price pivot strength", settings.rsiReversal.pricePivotStrength, 1, 20) { value ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(pricePivotStrength = value).sanitized()) }
                    }
                    IntStepper("RSI pivot strength", settings.rsiReversal.rsiPivotStrength, 1, 20) { value ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(rsiPivotStrength = value).sanitized()) }
                    }
                    ToggleRow("RSI break must close", settings.rsiReversal.requireRsiCloseBreak) { enabled ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(requireRsiCloseBreak = enabled)) }
                    }
                    RsiReversalEntryModeRow(settings.rsiReversal.entryMode) { mode ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(entryMode = mode)) }
                    }
                    DoubleStepper("Reward : risk", settings.rsiReversal.riskReward, 0.25, 0.5, 20.0) { value ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(riskReward = value).sanitized()) }
                    }
                    IntStepper("LTF entry window", settings.rsiReversal.ltfConfirmationWindowBars, 1, 200) { value ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(ltfConfirmationWindowBars = value).sanitized()) }
                    }
                    ToggleRow("Debug pattern labels", settings.rsiReversal.showDebugLabels) { enabled ->
                        updateSettings(onChange) { it.copy(rsiReversal = it.rsiReversal.copy(showDebugLabels = enabled)) }
                    }
                    SignalArrowNote()
                    ResetButton { updateSettings(onChange) { it.copy(rsiReversal = RsiReversalStudySettings()) } }
                }

                StudyControlId.PIVOT_SWEEP_DIVERGENCE -> {
                    PsdModeSelector(settings.pivotSweepDivergence.mode) { mode ->
                        updateSettings(onChange) {
                            it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(mode = mode))
                        }
                    }
                    IntStepper("Min quality score", settings.pivotSweepDivergence.minScore, 0, 100) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(minScore = value)) }
                    }
                    IntStepper("RSI period", settings.pivotSweepDivergence.rsiPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(rsiPeriod = value)) }
                    }
                    IntStepper("Flow period", settings.pivotSweepDivergence.flowPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(flowPeriod = value)) }
                    }
                    IntStepper("Flow smoothing", settings.pivotSweepDivergence.flowSmoothing, 1, 100) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(flowSmoothing = value)) }
                    }
                    IntStepper("Pivot left", settings.pivotSweepDivergence.pivotLeft, 1, 25) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(pivotLeft = value)) }
                    }
                    IntStepper("Pivot right", settings.pivotSweepDivergence.pivotRight, 1, 25) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(pivotRight = value)) }
                    }
                    DoubleStepper("Min RSI divergence", settings.pivotSweepDivergence.minRsiDifference, 0.5, 0.0, 50.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(minRsiDifference = value)) }
                    }
                    DoubleStepper("Min flow divergence", settings.pivotSweepDivergence.minFlowDifference, 0.5, 0.0, 50.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(minFlowDifference = value)) }
                    }
                    IntStepper("ATR period", settings.pivotSweepDivergence.atrPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(atrPeriod = value)) }
                    }
                    DoubleStepper("Min sweep ATR", settings.pivotSweepDivergence.minSweepAtr, 0.05, 0.0, 3.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(minSweepAtr = value)) }
                    }
                    DoubleStepper("Min rejection wick", settings.pivotSweepDivergence.minRejectionWickFraction, 0.05, 0.0, 1.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(minRejectionWickFraction = value)) }
                    }
                    DoubleStepper("Close location", settings.pivotSweepDivergence.minCloseLocation, 0.05, 0.5, 1.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(minCloseLocation = value)) }
                    }
                    IntStepper("Structure lookback", settings.pivotSweepDivergence.structureLookback, 1, 100) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(structureLookback = value)) }
                    }
                    IntStepper("Confirm window", settings.pivotSweepDivergence.maxConfirmBars, 0, 30) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(maxConfirmBars = value)) }
                    }
                    IntStepper("Sweep window", settings.pivotSweepDivergence.sweepWindowBars, 0, 25) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(sweepWindowBars = value)) }
                    }
                    IntStepper("Reclaim window", settings.pivotSweepDivergence.maxReclaimBars, 0, 25) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(maxReclaimBars = value)) }
                    }
                    DoubleStepper("Displacement ATR", settings.pivotSweepDivergence.displacementAtrMultiple, 0.05, 0.0, 5.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(displacementAtrMultiple = value)) }
                    }
                    DoubleStepper("Stop ATR buffer", settings.pivotSweepDivergence.stopBufferAtr, 0.05, 0.0, 5.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(stopBufferAtr = value)) }
                    }
                    DoubleStepper("Reward : risk", settings.pivotSweepDivergence.rewardRisk, 0.25, 0.25, 10.0) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(rewardRisk = value)) }
                    }
                    IntStepper("Cooldown bars", settings.pivotSweepDivergence.cooldownBars, 0, 250) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(cooldownBars = value)) }
                    }
                    IntStepper("Session UTC offset", settings.pivotSweepDivergence.sessionOffsetMinutes, -720, 840, 60) { value ->
                        updateSettings(onChange) { it.copy(pivotSweepDivergence = it.pivotSweepDivergence.copy(sessionOffsetMinutes = value)) }
                    }
                    Text(
                        text = "BUY: PDL/S1/S2 sweep. SELL: PDH/R1/R2 sweep. Previous completed day only; regular dual divergence only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    SignalArrowNote()
                    ResetButton { updateSettings(onChange) { it.copy(pivotSweepDivergence = PivotSweepDivergenceStudySettings()) } }
                }

                StudyControlId.VALUE_AREA_LIQUIDITY_REJECTION -> {
                    ValrModeSelector(settings.valueAreaLiquidityRejection.mode) { mode ->
                        updateSettings(onChange) {
                            it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(mode = mode))
                        }
                    }
                    IntStepper("Min quality score", settings.valueAreaLiquidityRejection.minScore, 0, 100) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(minScore = value)) }
                    }
                    IntStepper("Profile bins", settings.valueAreaLiquidityRejection.profileBins, 12, 200) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(profileBins = value)) }
                    }
                    DoubleStepper("Value area", settings.valueAreaLiquidityRejection.valueAreaPercent, 0.05, 0.50, 0.90) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(valueAreaPercent = value)) }
                    }
                    IntStepper("Prior-session bars", settings.valueAreaLiquidityRejection.minPreviousSessionBars, 4, 1500) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(minPreviousSessionBars = value)) }
                    }
                    IntStepper("ATR period", settings.valueAreaLiquidityRejection.atrPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(atrPeriod = value)) }
                    }
                    IntStepper("Liquidity lookback", settings.valueAreaLiquidityRejection.liquidityLookback, 3, 500) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(liquidityLookback = value)) }
                    }
                    DoubleStepper("Pool tolerance ATR", settings.valueAreaLiquidityRejection.poolToleranceAtr, 0.05, 0.0, 5.0) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(poolToleranceAtr = value)) }
                    }
                    DoubleStepper("Min sweep ATR", settings.valueAreaLiquidityRejection.minSweepAtr, 0.05, 0.0, 3.0) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(minSweepAtr = value)) }
                    }
                    DoubleStepper("Min rejection wick", settings.valueAreaLiquidityRejection.minWickFraction, 0.05, 0.0, 1.0) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(minWickFraction = value)) }
                    }
                    DoubleStepper("Volume spike", settings.valueAreaLiquidityRejection.volumeSpikeMultiple, 0.05, 0.0, 10.0) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(volumeSpikeMultiple = value)) }
                    }
                    IntStepper("Structure lookback", settings.valueAreaLiquidityRejection.structureLookback, 1, 100) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(structureLookback = value)) }
                    }
                    IntStepper("Confirm window", settings.valueAreaLiquidityRejection.maxConfirmBars, 0, 30) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(maxConfirmBars = value)) }
                    }
                    DoubleStepper("POC minimum R:R", settings.valueAreaLiquidityRejection.minPocRewardRisk, 0.25, 0.25, 10.0) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(minPocRewardRisk = value)) }
                    }
                    IntStepper("Cooldown bars", settings.valueAreaLiquidityRejection.cooldownBars, 0, 250) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(cooldownBars = value)) }
                    }
                    IntStepper("Session UTC offset", settings.valueAreaLiquidityRejection.sessionOffsetMinutes, -720, 840, 60) { value ->
                        updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = it.valueAreaLiquidityRejection.copy(sessionOffsetMinutes = value)) }
                    }
                    Text(
                        text = "BUY: prior-session VAL sweep/reclaim. SELL: VAH sweep/reclaim. POC is the causal target; provider volume or deterministic TPO fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    SignalArrowNote()
                    ResetButton { updateSettings(onChange) { it.copy(valueAreaLiquidityRejection = ValueAreaLiquidityRejectionStudySettings()) } }
                }

                StudyControlId.AMD -> {
                    AmdModeSelector(settings.amd.mode) { mode ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(mode = mode)) }
                    }
                    IntStepper("Min quality score", settings.amd.minScore, 0, 100) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(minScore = value)) }
                    }
                    IntStepper("ATR period", settings.amd.atrPeriod, 2, 500) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(atrPeriod = value)) }
                    }
                    IntStepper("Min accumulation bars", settings.amd.minAccumulationBars, 3, 250) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(minAccumulationBars = value)) }
                    }
                    IntStepper("Max accumulation bars", settings.amd.maxAccumulationBars, 3, 500) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(maxAccumulationBars = value)) }
                    }
                    DoubleStepper("Range compression ATR", settings.amd.accumulationRangeAtrMultiple, 0.1, 0.1, 10.0) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(accumulationRangeAtrMultiple = value)) }
                    }
                    DoubleStepper("Min sweep ATR", settings.amd.minSweepAtr, 0.05, 0.0, 3.0) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(minSweepAtr = value)) }
                    }
                    DoubleStepper("Min rejection wick", settings.amd.minRejectionWickFraction, 0.05, 0.0, 1.0) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(minRejectionWickFraction = value)) }
                    }
                    DoubleStepper("Close location", settings.amd.minCloseLocation, 0.05, 0.5, 1.0) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(minCloseLocation = value)) }
                    }
                    IntStepper("Reclaim window", settings.amd.maxReclaimBars, 0, 25) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(maxReclaimBars = value)) }
                    }
                    IntStepper("Confirm window", settings.amd.maxConfirmBars, 0, 30) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(maxConfirmBars = value)) }
                    }
                    DoubleStepper("Displacement ATR", settings.amd.displacementAtrMultiple, 0.05, 0.0, 5.0) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(displacementAtrMultiple = value)) }
                    }
                    DoubleStepper("Stop ATR buffer", settings.amd.stopBufferAtr, 0.05, 0.0, 5.0) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(stopBufferAtr = value)) }
                    }
                    DoubleStepper("Reward : risk", settings.amd.rewardRisk, 0.25, 0.25, 10.0) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(rewardRisk = value)) }
                    }
                    IntStepper("Cooldown bars", settings.amd.cooldownBars, 0, 250) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(cooldownBars = value)) }
                    }
                    IntStepper("Session UTC offset", settings.amd.sessionOffsetMinutes, -720, 840, 60) { value ->
                        updateSettings(onChange) { it.copy(amd = it.amd.copy(sessionOffsetMinutes = value)) }
                    }
                    Text(
                        text = "Accumulation range compresses, a sweep hunts the stops beyond it, distribution displaces back through the whole range. Structural, not session-clock — fires on every timeframe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    SignalArrowNote()
                    ResetButton { updateSettings(onChange) { it.copy(amd = AmdStudySettings()) } }
                }

                StudyControlId.NASCENT -> {
                    NascentModeSelector(settings.nascent.mode) { mode ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(mode = mode)) }
                    }
                    NascentQualitySelector(settings.nascent.quality) { quality ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(quality = quality)) }
                    }
                    ToggleRow("Historical calculation", settings.nascent.historicalCalculation) { value ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(historicalCalculation = value)) }
                    }
                    IntStepper("History depth (bars)", settings.nascent.historyDepthBars, 200, 20_000, 500) { value ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(historyDepthBars = value)) }
                    }
                    IntStepper("Live analysis window", settings.nascent.liveWindowBars, 20, 2_000, 20) { value ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(liveWindowBars = value)) }
                    }
                    ToggleRow("Show key levels", settings.nascent.showKeyLevels) { value ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(showKeyLevels = value)) }
                    }
                    ToggleRow("Show setup labels", settings.nascent.showSetupLabels) { value ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(showSetupLabels = value)) }
                    }
                    NascentDebugSelector(settings.nascent.debug) { level ->
                        updateSettings(onChange) { it.copy(nascent = it.nascent.copy(debug = level)) }
                    }
                    Text(
                        text = "External structure locates the setup, internal structure times it. " +
                            "No valid external location means no signal — silence here is a result, not a fault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    SignalArrowNote()
                    ResetButton { updateSettings(onChange) { it.copy(nascent = NascentStudySettings()) } }
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
private fun SignalProfileSelector(
    label: String,
    selected: SignalProfile,
    onSelect: (SignalProfile) -> Unit,
) {
    val colors = FoxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SignalProfile.entries.forEach { profile ->
                val active = profile == selected
                Text(
                    text = when (profile) {
                        SignalProfile.SCALPING -> "Scalp"
                        SignalProfile.INTRADAY -> "Intraday"
                        SignalProfile.SWING -> "Power"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) colors.accent else colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) colors.accentMuted else colors.surface)
                        .clickable { onSelect(profile) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun LitXModeSelector(selected: LitXMode, onSelect: (LitXMode) -> Unit) {
    val colors = FoxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Logic", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LitXMode.entries.forEach { mode ->
                val active = mode == selected
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) colors.accent else colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) colors.accentMuted else colors.surface)
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun IntStepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onValue: (Int) -> Unit) {
    SettingRow(
        label,
        value.toString(),
        { onValue((value - step.coerceAtLeast(1)).coerceAtLeast(min)) },
        { onValue((value + step.coerceAtLeast(1)).coerceAtMost(max)) },
    )
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
private fun RsiReversalEntryModeRow(
    selected: RsiReversalEntryPreset,
    onSelect: (RsiReversalEntryPreset) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Entry mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        RsiReversalEntryPreset.entries.forEach { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
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

@Composable
private fun SignalArrowNote() {
    Text(
        text = "BUY/SELL arrows: confirmed candle close only · locked · non-repaint",
        style = MaterialTheme.typography.bodySmall,
        color = FoxTheme.colors.accent,
    )
}

@Composable
private fun PsdModeSelector(
    selected: PivotSweepDivergenceMode,
    onSelected: (PivotSweepDivergenceMode) -> Unit,
) {
    val colors = FoxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Signal mode", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PivotSweepDivergenceMode.entries.forEach { mode ->
                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected == mode) colors.accent else colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected == mode) colors.accentMuted else colors.surfaceStrong)
                        .clickable { onSelected(mode) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ValrModeSelector(
    selected: ValueAreaLiquidityRejectionMode,
    onSelected: (ValueAreaLiquidityRejectionMode) -> Unit,
) {
    val colors = FoxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Signal mode", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ValueAreaLiquidityRejectionMode.entries.forEach { mode ->
                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected == mode) colors.accent else colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (selected == mode) colors.accentMuted else colors.surfaceStrong)
                        .clickable { onSelected(mode) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AmdModeSelector(
    selected: AmdMode,
    onSelected: (AmdMode) -> Unit,
) {
    val colors = FoxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Signal mode", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AmdMode.entries.forEach { mode ->
                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected == mode) colors.accent else colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (selected == mode) colors.accentMuted else colors.surfaceStrong)
                        .clickable { onSelected(mode) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NascentModeSelector(
    selected: com.foxtrader.app.domain.usecase.nascent.model.NascentMode,
    onSelected: (com.foxtrader.app.domain.usecase.nascent.model.NascentMode) -> Unit,
) = ChipSelector(
    title = "Mode",
    entries = com.foxtrader.app.domain.usecase.nascent.model.NascentMode.entries,
    selected = selected,
    label = { it.name.replace('_', ' ') },
    onSelected = onSelected,
)

@Composable
private fun NascentQualitySelector(
    selected: NascentQuality,
    onSelected: (NascentQuality) -> Unit,
) = ChipSelector(
    title = "Signal quality",
    entries = NascentQuality.entries,
    selected = selected,
    label = {
        when (it) {
            NascentQuality.A_PLUS_ONLY -> "A+ only"
            NascentQuality.A_AND_ABOVE -> "A and above"
            NascentQuality.ALL_VALID -> "All valid"
        }
    },
    onSelected = onSelected,
)

@Composable
private fun NascentDebugSelector(
    selected: NascentDebugLevel,
    onSelected: (NascentDebugLevel) -> Unit,
) = ChipSelector(
    title = "Debug",
    entries = NascentDebugLevel.entries,
    selected = selected,
    label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
    onSelected = onSelected,
)

/** Shared chip row, so each new selector is a call rather than a copy. */
@Composable
private fun <T> ChipSelector(
    title: String,
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    val colors = FoxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            entries.forEach { entry ->
                Text(
                    text = label(entry),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected == entry) colors.accent else colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (selected == entry) colors.accentMuted else colors.surfaceStrong)
                        .clickable { onSelected(entry) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private data class ActiveStudy(val id: StudyControlId, val label: String)

private fun activeStudies(t: IndicatorToggles): List<ActiveStudy> = buildList {
    // A production system enables several internal primitives. Present one
    // canonical chip so the chart remains compact and the gear edits the
    // actual system rather than an implementation detail.
    when (t.productionAnalysisSystem()) {
        ProductionAnalysisSystem.LIT_ADVENTURE -> {
            add(ActiveStudy(StudyControlId.LITX, "LiT Adventure"))
            return@buildList
        }
        ProductionAnalysisSystem.LIT_MAY_MADNESS -> {
            add(ActiveStudy(StudyControlId.LIT, "LiT May Madness"))
            return@buildList
        }
        ProductionAnalysisSystem.SMT -> {
            add(ActiveStudy(StudyControlId.SMT, "SMT"))
            return@buildList
        }
        ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE -> {
            add(ActiveStudy(StudyControlId.RSI_ORDER_FLOW, "RSI Orderflow Divergence"))
            return@buildList
        }
        ProductionAnalysisSystem.RSI_REVERSAL -> {
            add(ActiveStudy(StudyControlId.RSI_REVERSAL, "RSI Orderflow Reversal"))
            return@buildList
        }
        ProductionAnalysisSystem.PIVOT_SWEEP_DIVERGENCE -> {
            add(ActiveStudy(StudyControlId.PIVOT_SWEEP_DIVERGENCE, "Pivot Sweep Divergence"))
            return@buildList
        }
        ProductionAnalysisSystem.VALUE_AREA_LIQUIDITY_REJECTION -> {
            add(ActiveStudy(StudyControlId.VALUE_AREA_LIQUIDITY_REJECTION, "Value Area Liquidity Rejection"))
            return@buildList
        }
        null -> Unit
    }

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
    if (t.rsiReversal) add(ActiveStudy(StudyControlId.RSI_REVERSAL, "RSI Rev"))
    if (t.pivotSweepDivergence) add(ActiveStudy(StudyControlId.PIVOT_SWEEP_DIVERGENCE, "PSD"))
    if (t.valueAreaLiquidityRejection) add(ActiveStudy(StudyControlId.VALUE_AREA_LIQUIDITY_REJECTION, "VALR"))
    if (t.amd) add(ActiveStudy(StudyControlId.AMD, "AMD"))
    if (t.nascent) add(ActiveStudy(StudyControlId.NASCENT, "Nascent FX"))
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
        StudyControlId.LITX -> t.withProductionAnalysisSystem(null)
        StudyControlId.LIT -> t.withProductionAnalysisSystem(null)
        StudyControlId.SMS -> t.copy(sms = false)
        StudyControlId.SMT -> t.withProductionAnalysisSystem(null)
        StudyControlId.TRADE_PRO -> t.copy(tradePro = false)
        StudyControlId.BINARY_3M -> t.copy(binary3m = false)
        StudyControlId.RSI -> t.copy(rsi = false)
        StudyControlId.RSI_ORDER_FLOW -> t.withProductionAnalysisSystem(null)
        StudyControlId.RSI_REVERSAL -> t.withProductionAnalysisSystem(null)
        StudyControlId.PIVOT_SWEEP_DIVERGENCE -> t.withProductionAnalysisSystem(null)
        StudyControlId.VALUE_AREA_LIQUIDITY_REJECTION -> t.withProductionAnalysisSystem(null)
        StudyControlId.AMD -> t.copy(amd = false)
        StudyControlId.NASCENT -> t.copy(nascent = false)
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
    STRUCTURE("Market Structure"), LITX("LiT Adventure"), LIT("LiT May Madness"), SMS("SMS"), SMT("SMT"),
    TRADE_PRO("TradePro"), BINARY_3M("Deriv 3m"), RSI("RSI settings"),
    RSI_ORDER_FLOW("RSI OrderFlow settings"),
    RSI_REVERSAL("RSI Orderflow Reversal settings"),
    PIVOT_SWEEP_DIVERGENCE("Pivot Sweep Divergence settings"),
    VALUE_AREA_LIQUIDITY_REJECTION("Value Area Liquidity Rejection settings"),
    AMD("AMD settings"),
    NASCENT("Nascent FX settings"),
    MACD("MACD settings"), VOLUME("Volume"),
    STOCHASTIC("Stochastic settings"), OBV("OBV"), MFI("MFI settings"), STRATEGY("Strategy settings"),
    CUSTOM_STRATEGY("Custom strategy"), ALL_STRATEGIES("All strategies"),
}
