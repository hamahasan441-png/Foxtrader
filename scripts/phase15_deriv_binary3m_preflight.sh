#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
require_file() { [[ -f "$1" ]] || fail "Missing $1"; }
require_grep() { local pattern="$1" file="$2" label="$3"; grep -Eq "$pattern" "$file" || fail "$label"; pass "$label"; }
reject_grep() { local pattern="$1" file="$2" label="$3"; if grep -Eq "$pattern" "$file"; then fail "$label"; else pass "$label"; fi; }

MODEL='app/src/main/java/com/foxtrader/app/domain/model/BinaryBacktest.kt'
SIGNAL='app/src/main/java/com/foxtrader/app/domain/usecase/binary/DerivBinary3mSignalEngine.kt'
ENGINE='app/src/main/java/com/foxtrader/app/domain/usecase/binary/BinaryBacktestEngine.kt'
ANALYTICS='app/src/main/java/com/foxtrader/app/domain/usecase/backtest/BacktestAnalyticsEngine.kt'
LAB_STATE='app/src/main/java/com/foxtrader/app/feature/backtest/presentation/BacktestLabUiState.kt'
LAB_VM='app/src/main/java/com/foxtrader/app/feature/backtest/presentation/BacktestLabViewModel.kt'
LAB_UI='app/src/main/java/com/foxtrader/app/feature/backtest/presentation/BacktestLabScreen.kt'
CHART_STATE='app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartUiState.kt'
CHART_VM='app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt'
CHART_PANEL='app/src/main/java/com/foxtrader/app/feature/chart/presentation/components/IndicatorPanel.kt'
CHART_SIGNAL='app/src/main/java/com/foxtrader/app/domain/model/ChartSignal.kt'
SIGNAL_COMPUTER='app/src/main/java/com/foxtrader/app/domain/usecase/chart/SignalComputer.kt'
OUTCOME='app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/SignalOutcomeEvaluator.kt'
REQUEST_BUILDER='app/src/main/java/com/foxtrader/app/domain/usecase/deriv/DerivRequestBuilder.kt'
SIGNAL_TEST='app/src/test/java/com/foxtrader/app/domain/usecase/binary/DerivBinary3mSignalEngineTest.kt'
ENGINE_TEST='app/src/test/java/com/foxtrader/app/domain/usecase/binary/BinaryBacktestEngineTest.kt'
ANALYTICS_TEST='app/src/test/java/com/foxtrader/app/domain/usecase/backtest/BacktestAnalyticsEngineTest.kt'

for f in "$MODEL" "$SIGNAL" "$ENGINE" "$ANALYTICS" "$LAB_STATE" "$LAB_VM" "$LAB_UI" "$CHART_STATE" "$CHART_VM" "$CHART_PANEL" "$CHART_SIGNAL" "$SIGNAL_COMPUTER" "$OUTCOME" "$REQUEST_BUILDER" "$SIGNAL_TEST" "$ENGINE_TEST" "$ANALYTICS_TEST"; do require_file "$f"; done

require_grep 'signalIndex: Int' "$MODEL" 'Binary signal records immutable confirmation index'
require_grep 'expiryBars: Int = 3' "$MODEL" 'Binary model defaults to three-bar expiry'
require_grep 'allowOverlappingContracts: Boolean = false' "$MODEL" 'Overlapping fixed-expiry contracts default off'
require_grep 'evaluateAll\(' "$SIGNAL" 'Bulk closed-bar signal engine exists'
require_grep 'EMA pullback \+ reclaim' "$SIGNAL" 'Trend pullback/reclaim confirmation is scored'
require_grep 'ADX/DI trend strength' "$SIGNAL" 'ADX/DI trend strength is scored'
require_grep 'RSI continuation zone' "$SIGNAL" 'RSI continuation filter is scored'
require_grep 'ATR regime accepted' "$SIGNAL" 'ATR regime gate is scored'
reject_grep 'candles\[index *\+ *[1-9]' "$SIGNAL" 'Signal engine never reads future candles by positive index offset'
require_grep 'val entryIndex = signalIndex \+ 1' "$ENGINE" 'Backtest enters at next M1 open'
require_grep 'val expiryIndex = signalIndex \+ config\.expiryBars' "$ENGINE" 'Backtest expiry is fixed from signal bar'
require_grep 'entryPrice = entry\.open' "$ENGINE" 'Backtest uses next-bar OPEN as entry price'
require_grep 'BinaryOutcome\.WIN -> stake \* config\.payoutRatio' "$ENGINE" 'Winning payout uses configurable net payout ratio'
require_grep '100\.0 / \(1\.0 \+ config\.payoutRatio\)' "$ENGINE" 'Break-even win rate uses payout-aware formula'
require_grep 'timeframe == Timeframe\.M1' "$ENGINE" 'Binary engine rejects non-M1 backtests'
reject_grep 'martingale|Martingale' "$ENGINE" 'Backtest engine contains no Martingale sizing logic'

require_grep 'DERIV_BINARY_3M' "$LAB_STATE" 'Backtest Lab exposes Deriv Binary 3m template'
require_grep 'DERIV_BINARY_SYMBOLS' "$LAB_STATE" 'Backtest Lab has explicit Deriv binary symbol list'
require_grep 'appPreferences\.setDataProvider\(DataProvider\.DERIV\)' "$LAB_VM" 'Selecting Binary 3m forces Deriv provider preference'
require_grep '_uiState\.value\.isBinary3m && provider != DataProvider\.DERIV' "$LAB_VM" 'Binary template refuses switching to a non-Deriv provider'
require_grep 'items = if \(state\.isBinary3m\) listOf\(DataProvider\.DERIV\)' "$LAB_UI" 'Binary template provider selector is locked to Deriv'
require_grep 'timeframe = if \(isBinary\) Timeframe\.M1' "$LAB_VM" 'Selecting Binary 3m forces M1 timeframe'
require_grep 'repository\.refreshCandles\(state\.symbol, state\.timeframe, requestedBars\)' "$LAB_VM" 'Backtest refreshes the selected provider before measurement'
require_grep 'sourced\.source == CandleSource\.LIVE' "$LAB_VM" 'Live-provider backtests reject synthetic fallback'
require_grep 'BINARY_BACKTEST_REFRESH_BARS = 5_000' "$LAB_VM" 'Deriv binary backtest requests deep 5000-bar M1 history'
require_grep 'expiryBars = 3' "$LAB_VM" 'Backtest Lab locks fixed expiry to three bars'
require_grep 'allowOverlappingContracts = false' "$LAB_VM" 'Backtest Lab disables overlapping contracts'
require_grep 'BinaryPayoutSlider' "$LAB_UI" 'Backtest Lab exposes payout control'
require_grep 'BinaryConfidenceSlider' "$LAB_UI" 'Backtest Lab exposes confidence threshold control'
require_grep 'Edge vs BE' "$LAB_UI" 'Backtest result reports edge versus payout break-even'
require_grep 'analyzeBinary\(' "$ANALYTICS" 'Binary results use walk-forward and Monte Carlo analytics'
require_grep 'result to analyticsEngine\.analyzeBinary\(result\)' "$LAB_VM" 'Binary Lab computes validation analytics after every run'
require_grep 'AnalyticsCard\(analytics\)' "$LAB_UI" 'Binary result renders validation analytics'
require_grep 'Zero-PnL trades' "$ANALYTICS" 'Analytics treats refunded ties as neutral rather than losses'

require_grep 'val binary3m: Boolean = false' "$CHART_STATE" 'Chart has dedicated Binary3m toggle'
require_grep 'Deriv 3m \(M1\)' "$CHART_PANEL" 'Chart indicator panel exposes Binary3m strategy'
require_grep 'BINARY3M' "$CHART_SIGNAL" 'Unified chart signal source includes Binary3m'
require_grep 'derivBinary3mSignalEngine' "$CHART_VM" 'Chart injects the same Binary3m engine used by backtests'
require_grep '_uiState\.value\.dataProvider == DataProvider\.DERIV' "$CHART_VM" 'Live Binary3m signals are gated to Deriv data'
require_grep 'timeframe == Timeframe\.M1' "$CHART_VM" 'Live Binary3m signals are gated to M1'
require_grep 'barMode == ChartBarMode\.TIME' "$CHART_VM" 'Live Binary3m signals reject transformed/non-time bars'
require_grep 'source = SignalSource\.BINARY3M' "$CHART_VM" 'Live chart maps Binary3m results into unified markers'
require_grep 'source != SignalSource\.BINARY3M' "$SIGNAL_COMPUTER" 'Binary3m is excluded from chart-only confidence confluence'
require_grep 'source == SignalSource\.BINARY3M' "$SIGNAL_COMPUTER" 'Signal boundary accepts fixed-expiry marker geometry'
require_grep 'it\.source != SignalSource\.BINARY3M' "$OUTCOME" 'SL/TP outcome evaluator excludes fixed-expiry Binary3m markers'

require_grep 'put\("underlying_symbol", request\.underlyingSymbol\)' "$REQUEST_BUILDER" 'Deriv proposal uses current underlying_symbol field'
require_grep 'put\("duration", it\)' "$REQUEST_BUILDER" 'Deriv proposal supports fixed duration'
require_grep 'put\("duration_unit", it\)' "$REQUEST_BUILDER" 'Deriv proposal supports minute duration unit'

require_grep 'prefix stable' "$SIGNAL_TEST" 'Unit test covers non-repaint prefix stability'
require_grep 'signalIndex \+ 1' "$ENGINE_TEST" 'Unit test verifies next-minute entry'
require_grep 'signalIndex \+ 3' "$ENGINE_TEST" 'Unit test verifies three-minute expiry'
require_grep 'binary analysis creates deterministic walk forward and monte carlo validation' "$ANALYTICS_TEST" 'Unit test covers Binary3m validation analytics'

if rg -n --glob '*.kt' 'TODO\(\)|NotImplementedError|GlobalScope' app/src/main/java >/dev/null; then
  fail 'Production Kotlin contains TODO()/NotImplementedError/GlobalScope'
else
  pass 'No TODO()/NotImplementedError/GlobalScope in production Kotlin'
fi
if rg -n '^(<<<<<<<|=======|>>>>>>>)' . --glob '!*.md' --glob '!*.txt' >/dev/null; then
  fail 'Merge conflict markers found'
else
  pass 'No merge conflict markers'
fi

printf 'PHASE15_DERIV_BINARY3M_PREFLIGHT: PASS\n'
