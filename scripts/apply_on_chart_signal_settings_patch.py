from pathlib import Path
import textwrap

D = textwrap.dedent


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch anchor {label!r} expected once, found {count}")
    return text.replace(old, new, 1)


# 1) Remove indicator/signal engine tuning from global app Settings.
p = "app/src/main/java/com/foxtrader/app/feature/settings/presentation/SettingsScreen.kt"
s = read(p)
for imp in (
    "import com.foxtrader.app.domain.model.LitBreakMode\n",
    "import com.foxtrader.app.domain.model.LitXGrade\n",
    "import com.foxtrader.app.domain.model.SignalProfile\n",
):
    s = s.replace(imp, "")
s = s.replace(D('''\
private val LIT_ADVENTURE_MODES = linkedMapOf(
    "Fast Scalp" to SignalProfile.SCALPING,
    "Balanced Trade" to SignalProfile.INTRADAY,
    "Power Trade" to SignalProfile.SWING,
)

'''), "")
start = '            SectionHeader(stringResource(R.string.settings_litx_title))\n'
end = '            SectionHeader("General")\n'
a = s.find(start)
b = s.find(end)
if a < 0 or b < 0 or b <= a:
    raise SystemExit("could not locate global LiT/SMT/SMS settings block")
s = s[:a] + s[b:]
s = replace_once(
    s,
    D('''\
            SectionHeader("Chart Indicators")
            SettingsCard {
                Text(
                    "Every chart indicator is available from the chart's Indicators button in every LiT Adventure mode. " +
                        "Enable it there; active studies expose their normal parameters from the ⚙ chip. " +
                        "RSI Orderflow advanced divergence thresholds are also visible in the indicator panel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }
'''),
    D('''\
            SectionHeader("Chart Indicators")
            SettingsCard {
                Text(
                    "Add indicators and signal engines from the chart. Every active study exposes a small ⚙ chip directly on the chart for presets and parameters; × removes it. Confirmed arrows are closed-bar signals and remain locked once observed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }
'''),
    "global chart-indicator explanation",
)
write(p, s)


# 2) Indicator picker becomes selection-only. Editing belongs to the active chart gear.
p = "app/src/main/java/com/foxtrader/app/feature/chart/presentation/components/IndicatorPanel.kt"
s = read(p)
s = s.replace(
    D('''\
 * Technical study parameters are edited from the TradingView-style active-study
 * gear chips on the chart. The RSI Orderflow advanced divergence thresholds are
 * additionally exposed here because the compact gear card previously omitted
 * them. Signal-engine parameters remain in the app Settings screen.
'''),
    D('''\
 * Parameters are edited only from the TradingView-style active-study gear chips
 * on the chart. The picker only adds/removes studies; it is not a second settings
 * surface. Signal-engine presets and thresholds follow the same rule.
'''),
)
s = replace_once(
    s,
    D('''\
                Text(
                    text = "All studies stay available in Fast Scalp, Balanced Trade and Power Trade. " +
                        "Enable technical studies here and use each active ⚙ chip for its normal parameters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
'''),
    D('''\
                Text(
                    text = "Add a study here, then use its small on-chart ⚙ chip to edit presets and parameters. The picker stays selection-only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
'''),
    "indicator picker guidance",
)
s = replace_once(
    s,
    D('''\
                if (toggles.rsiOrderFlow) {
                    RsiOrderFlowAdvancedSettings(
                        toggles = toggles,
                        onToggle = guardedToggle,
                    )
                }

'''),
    "",
    "remove RSI settings from picker",
)
s = replace_once(
    s,
    D('''\
                Text(
                    text = "Readiness is informational: incompatible studies remain visible instead of disappearing. " +
                        "Time-axis studies require standard time candles; Deriv 3m requires Deriv + M1. " +
                        "Signal-engine thresholds and LiT modes are persisted in app Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
'''),
    D('''\
                Text(
                    text = "Readiness is informational: incompatible studies remain visible instead of disappearing. " +
                        "Time-axis studies require standard time candles; Deriv 3m requires Deriv + M1. " +
                        "All editable study and signal-engine controls live on the active chart ⚙ card.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
'''),
    "indicator picker footer",
)
write(p, s)


# 3) ChartViewModel exposes persisted signal-engine configs to the chart gear.
p = "app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt"
s = read(p)
s = replace_once(
    s,
    "import com.foxtrader.app.domain.model.Direction\n",
    D('''\
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.SmtConfig
'''),
    "ChartViewModel signal config imports",
)
s = replace_once(
    s,
    "    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {\n",
    D('''\
    fun currentLitXConfig(): LitXConfig = appPreferences.litXConfig.value
    fun currentLitConfig(): LitConfig = appPreferences.litConfig.value
    fun currentSmtConfig(): SmtConfig = appPreferences.smtConfig.value
    fun currentSmsConfig(): SmsConfig = appPreferences.smsConfig.value

    fun updateLitXConfig(config: LitXConfig) {
        appPreferences.setLitXConfig(config.sanitized())
    }

    fun updateLitConfig(config: LitConfig) {
        appPreferences.setLitConfig(config.sanitized())
    }

    fun updateSmtConfig(config: SmtConfig) {
        appPreferences.setSmtConfig(config.sanitized())
    }

    fun updateSmsConfig(config: SmsConfig) {
        appPreferences.setSmsConfig(config.sanitized())
    }

    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {
'''),
    "ChartViewModel chart-config API",
)
s = replace_once(
    s,
    D('''\
    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {
        val current = _uiState.value.indicators
        val updated = transform(current)
'''),
    D('''\
    fun updateIndicators(transform: (IndicatorToggles) -> IndicatorToggles) {
        val current = _uiState.value.indicators
        val updated = transform(current)
        // The chart toggle is authoritative now that LiT Adventure no longer has
        // a global Settings enable switch. Never let a stale persisted disabled
        // flag make an enabled on-chart study silently produce no arrows.
        if (!current.litX && updated.litX && !appPreferences.litXConfig.value.enabled) {
            appPreferences.setLitXConfig(appPreferences.litXConfig.value.copy(enabled = true).sanitized())
        }
'''),
    "LiTX chart-toggle authority",
)
write(p, s)


# 4) Give active signal studies real on-chart gear editors.
p = "app/src/main/java/com/foxtrader/app/feature/chart/presentation/components/ChartStudyCornerControls.kt"
s = read(p)
s = replace_once(
    s,
    "import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettings\n",
    D('''\
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettings
'''),
    "corner signal config imports",
)
s = replace_once(
    s,
    D('''\
fun ChartStudyCornerControls(
    toggles: IndicatorToggles,
    onChange: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    modifier: Modifier = Modifier,
) {
'''),
    D('''\
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
'''),
    "corner public signature",
)
s = replace_once(
    s,
    D('''\
            StudySettingsCard(
                id = id,
                toggles = toggles,
                onChange = onChange,
                onClose = { editing = null },
            )
'''),
    D('''\
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
'''),
    "corner settings call",
)
s = replace_once(
    s,
    D('''\
private fun StudySettingsCard(
    id: StudyControlId,
    toggles: IndicatorToggles,
    onChange: ((IndicatorToggles) -> IndicatorToggles) -> Unit,
    onClose: () -> Unit,
) {
'''),
    D('''\
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
'''),
    "corner private signature",
)
s = replace_once(
    s,
    D('''\
            when (id) {
                StudyControlId.EMA -> {
'''),
    D('''\
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
                    Text(
                        text = "Hard sequence: pullback → IDM sweep/reclaim → BOS → CHOCH + displacement → POI/SCOB → first retest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
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
'''),
    "signal-engine gear cases",
)
s = replace_once(
    s,
    '                    ResetButton { updateSettings(onChange) { it.copy(rsiOrderFlow = RsiOrderFlowStudySettings()) } }\n',
    D('''\
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
                    ResetButton { updateSettings(onChange) { it.copy(rsiOrderFlow = RsiOrderFlowStudySettings()) } }
'''),
    "RSI Orderflow advanced gear controls",
)
s = replace_once(
    s,
    D('''\
@Composable
private fun IntStepper(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
'''),
    D('''\
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
private fun IntStepper(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
'''),
    "profile/mode gear helpers",
)
write(p, s)


# 5) Wire the gear into the actual chart and keep a bounded recent arrow trail.
p = "app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartScreen.kt"
s = read(p)
s = replace_once(
    s,
    "import com.foxtrader.app.feature.chart.presentation.components.ChartOhlcLegend\n",
    "import com.foxtrader.app.feature.chart.presentation.components.ChartOhlcLegend\nimport com.foxtrader.app.feature.chart.presentation.components.ChartStudyCornerControls\n",
    "ChartScreen corner-control import",
)
s = replace_once(
    s,
    D('''\
    val visibleSignals = remember(state.signals, state.showSignalHistory) {
        if (state.showSignalHistory) state.signals else state.signals.filter { it.isLive }
    }
'''),
    D('''\
    val visibleSignals = remember(state.signals, state.showSignalHistory, state.candles.size) {
        if (state.showSignalHistory) {
            state.signals
        } else {
            // Keep the canvas clean but do not hide every confirmed arrow the
            // instant the next candle opens. The bounded trail makes signal
            // engines visibly auditable without enabling full Signal History.
            val recentCutoff = (state.candles.size - 120).coerceAtLeast(0)
            state.signals
                .filter { it.isLive || it.barIndex >= recentCutoff }
                .takeLast(24)
        }
    }
'''),
    "default bounded confirmed arrows",
)
legend = D('''\
                        ChartOhlcLegend(
                            candle = displayCandles.lastOrNull(),
                            previousCandle = displayCandles.getOrNull(displayCandles.lastIndex - 1),
                            freshness = state.dataFreshness,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        )
''')
controls = legend + D('''\
                        ChartStudyCornerControls(
                            toggles = state.indicators,
                            onChange = viewModel::updateIndicators,
                            litXConfig = viewModel.currentLitXConfig(),
                            litConfig = viewModel.currentLitConfig(),
                            smtConfig = viewModel.currentSmtConfig(),
                            smsConfig = viewModel.currentSmsConfig(),
                            onLitXConfigChange = viewModel::updateLitXConfig,
                            onLitConfigChange = viewModel::updateLitConfig,
                            onSmtConfigChange = viewModel::updateSmtConfig,
                            onSmsConfigChange = viewModel::updateSmsConfig,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 8.dp, top = 40.dp, end = 72.dp),
                        )
''')
s = replace_once(s, legend, controls, "chart corner gear wiring")
write(p, s)

print("on-chart signal-settings patch applied")
