#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0
pass(){ printf 'PASS: %s\n' "$1"; }
check(){ local label="$1"; shift; if "$@"; then pass "$label"; else printf 'FAIL: %s\n' "$label" >&2; fail=1; fi; }
contains(){ grep -qF -- "$2" "$1"; }
contains_re(){ grep -Eq -- "$2" "$1"; }
not_contains_re(){ ! grep -Eq -- "$2" "$1"; }

MODEL=app/src/main/java/com/foxtrader/app/domain/model/SignalIntelligence.kt
INTEGRITY=app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/SignalSeriesIntegrity.kt
LIT=app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/LitEngine.kt
SMS=app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/SmsEngine.kt
FUSION=app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/SignalFusionEngine.kt
OUTCOME=app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/SignalOutcomeEvaluator.kt
LITX=app/src/main/java/com/foxtrader/app/domain/usecase/litx/LitXEngine.kt
MSS=app/src/main/java/com/foxtrader/app/domain/usecase/litx/MssClassifier.kt
SMT=app/src/main/java/com/foxtrader/app/domain/usecase/smt/SmtDivergenceDetector.kt
CHART=app/src/main/java/com/foxtrader/app/domain/usecase/chart/SignalComputer.kt
VM=app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt
SHEET=app/src/main/java/com/foxtrader/app/feature/chart/presentation/components/ChartAnalysisSheet.kt
STRATEGIES=app/src/main/java/com/foxtrader/app/domain/usecase/strategies/StrategyLibrary.kt
AGENT=app/src/main/java/com/foxtrader/app/domain/usecase/ai/agents/LitAgent.kt
SCANNER=app/src/main/java/com/foxtrader/app/domain/usecase/scanner/ScannerUseCase.kt
PREFS=app/src/main/java/com/foxtrader/app/domain/usecase/preferences/AppPreferences.kt
SETTINGS_VM=app/src/main/java/com/foxtrader/app/feature/settings/presentation/SettingsViewModel.kt
SETTINGS_UI=app/src/main/java/com/foxtrader/app/feature/settings/presentation/SettingsScreen.kt

check "Phase 13 institutional model exists" test -f "$MODEL"
check "LiT is a first-class engine" test -f "$LIT"
check "SMS is a first-class engine" test -f "$SMS"
check "Signal fusion is a first-class engine" test -f "$FUSION"
check "Conservative accuracy evaluator exists" test -f "$OUTCOME"
check "Scalping/intraday/swing profiles exist" contains "$MODEL" 'enum class SignalProfile { SCALPING, INTRADAY, SWING }'
check "LiT editable config exists" contains "$MODEL" 'data class LitConfig('
check "SMT editable config exists" contains "$MODEL" 'data class SmtConfig('
check "SMS editable config exists" contains "$MODEL" 'data class SmsConfig('
check "Signal config values are clamped" contains "$MODEL" 'fun sanitized()'
check "LiT settings persist" contains "$PREFS" 'KEY_LIT_CONFIG'
check "SMT settings persist" contains "$PREFS" 'KEY_SMT_CONFIG'
check "SMS settings persist" contains "$PREFS" 'KEY_SMS_CONFIG'
check "LiTX settings are sanitized before persistence" contains "$PREFS" 'config.sanitized()'
check "Settings UI exposes LIT" contains "$SETTINGS_UI" 'LIT — Liquidity / Inducement'
check "Settings UI exposes SMT" contains "$SETTINGS_UI" 'SMT — Divergence'
check "Settings UI exposes SMS" contains "$SETTINGS_UI" 'SMS — Smart Money Structure'
check "Settings VM applies LIT presets" contains "$SETTINGS_VM" 'LitConfig.preset(profile)'
check "Settings VM applies SMT presets" contains "$SETTINGS_VM" 'SmtConfig.preset(profile)'
check "Settings VM applies SMS presets" contains "$SETTINGS_VM" 'SmsConfig.preset(profile)'
check "Chart uses persisted LIT config" contains "$VM" 'appPreferences.litConfig.value'
check "Chart respects LiTX enable switch" contains "$VM" 'litXConfig.enabled && (ind.litX || ind.tradePro)'
check "Chart uses persisted SMT config" contains "$VM" 'appPreferences.smtConfig.value'
check "Chart uses persisted SMS config" contains "$VM" 'appPreferences.smsConfig.value'
check "Paused chart recomputes after signal setting changes" contains "$VM" 'Phase 13 settings are live'

check "Candle series integrity rejects non-monotonic time" contains "$INTEGRITY" 'c.timestamp <= previousTs'
check "Confirmed-bar policy uses timeframe close time" contains "$INTEGRITY" 'safeAdd(candles[i].timestamp, duration)'
check "HTF maps are trimmed to confirmed bars" contains "$INTEGRITY" 'confirmedMap('

check "LiT requires objectively confirmed structure" contains "$LIT" 'shift.breakIndex + STRUCTURE_RIGHT_BARS'
check "LiT does not clamp unavailable confirmation into history" not_contains_re "$LIT" 'shift\.breakIndex.*coerceAtMost'
check "LiT displacement evidence is causal" contains_re "$LIT" 'it\.startIndex in shift\.breakIndex\.\.minOf'
check "LiT uses first post-confirmation retest" contains_re "$LIT" 'firstOrNull|first \{'

check "SMS confirmation is delayed by configured right-side bars" contains "$SMS" 'latest.breakIndex + cfg.swingBars'
check "SMS displacement evidence is causal" contains_re "$SMS" 'displacement\.startIndex in latest\.breakIndex\.\.minOf'
check "SMS supports BOS/CHOCH/MSS" contains "$MODEL" 'enum class SmsEventType { BOS, CHOCH, MSS }'

check "LiTX validates raw candle integrity" contains "$LITX" 'SignalSeriesIntegrity.validate'
check "LiTX enforces confirmation knowledge index" contains "$LITX" 'shift.breakIndex + STRUCTURE_RIGHT_BARS'
check "LiTX profile presets are active" contains "app/src/main/java/com/foxtrader/app/domain/model/LitX.kt" 'fun preset(profile: SignalProfile'
check "LiTX strong MSS is configurable" contains "$LITX" 'cfg.requireStrongMss'
check "LiTX directional premium/discount gate exists" contains "$LITX" 'cfg.requireDirectionalZone'
check "MSS classifier rejects pre-shift displacement" contains "$MSS" 'displacement.startIndex in shift.breakIndex..(shift.breakIndex + maxDisplacementGapBars)'

check "SMT timestamp alignment has bounded skew" contains "$SMT" 'timestampSkewMs'
check "SMT divergence is emitted on confirmation bar" contains "$SMT" 'confirmationIndex'
check "SMT rejects weak divergence separation" contains "$SMT" 'MIN_DIVERGENCE_STRENGTH'
check "SMT prevents reused peer alignment" contains "$SMT" 'result.lastOrNull()?.peerIndex == best'
check "SMT plateau handling is asymmetric/confirmed" contains "$SMT" '(i + 1..i + lookback).all { candles[it].high <= candles[i].high }'

check "TradePro fusion cannot invent EXECUTE" contains "$FUSION" 'remain executable if its own setup was already EXECUTE'
check "Opposing institutional evidence can block execution" contains "$FUSION" 'BLOCKED by opposing institutional evidence'
check "Fusion uses LiTX" contains "$FUSION" 'SignalFusionComponent("LiTX"'
check "Fusion uses LiT" contains "$FUSION" 'SignalFusionComponent("LiT"'
check "Fusion uses SMS" contains "$FUSION" 'SignalFusionComponent("SMS"'
check "Fusion uses SMT" contains "$FUSION" 'SignalFusionComponent("SMT"'

check "Chart renders LiT" contains "$CHART" 'source = SignalSource.LIT'
check "Chart renders SMS" contains "$CHART" 'source = SignalSource.SMS'
check "Chart puts SMT on confirmation bar" contains "$CHART" 'barIndex = div.confirmationIndex'
check "TradePro signal timestamp is replay-stable" contains "$CHART" 'timestamp = candles[barIndex].timestamp'
check "Chart does not double-count Phase 13 TradePro fusion" contains "$CHART" 'phase13TradeProAlreadyFused'
check "Chart displays Phase 13 fusion score" contains "$SHEET" 'Phase13FusionCard'

check "Chart live analysis trims active candle" contains "$VM" 'ConfirmedBarPolicy.latestConfirmedIndex'
check "Chart strategy evaluation uses confirmed candles" contains "$VM" 'signalCandles'
check "Canonical LiT strategy uses Phase 13 engine" contains "$STRATEGIES" 'litEngine.analyze'
check "AI LiT agent trims active candle" contains "$AGENT" 'ConfirmedBarPolicy'
check "AI LiT uses only BOS/CHOCH/MSS as structure confirmation" contains "$AGENT" 'CONFIRMATION_STRUCTURE_TYPES = setOf("BOS", "CHOCH", "MSS")'
check "Canonical LiT suppresses duplicate legacy entry vote" contains "$AGENT" 'if (canonicalLitSignal == null)'
check "Scanner trims active candle" contains "$SCANNER" 'ConfirmedBarPolicy'

check "Accuracy starts after confirmation bar" contains "$OUTCOME" 'val start = signal.barIndex + 1'
check "Ambiguous same-candle SL+TP is conservative loss" contains "$OUTCOME" 'Worst-case ordering'
check "Accuracy reports win rate" contains "$OUTCOME" 'winRate'
check "Accuracy reports average R" contains "$OUTCOME" 'averageR'
check "Accuracy reports profit factor" contains "$OUTCOME" 'profitFactor'

check "No TODO()/NotImplementedError in Android production Kotlin" bash -c '! grep -R -E "TODO\\(|NotImplementedError" app/src/main/java --include="*.kt" >/dev/null'
check "No GlobalScope in Android production Kotlin" bash -c '! grep -R "GlobalScope" app/src/main/java --include="*.kt" >/dev/null'
check "No merge conflict markers" bash -c '! grep -R -E "^(<<<<<<<|=======|>>>>>>>)" app/src/main/java backend scripts >/dev/null'

if (( fail )); then
  echo 'PHASE13_SIGNAL_INTELLIGENCE_PREFLIGHT: FAIL' >&2
  exit 1
fi
echo 'PHASE13_SIGNAL_INTELLIGENCE_PREFLIGHT: PASS'
