# FOX Trader — Phase 3: On-Chart Backtesting

## Goal

Make the chart itself a strategy-validation surface: select a production or saved visual-builder strategy, run the same non-repainting strategy function used by live signals, display completed entries/exits directly on the price chart, and show winning/losing signal counts without leaving the Chart tab.

## Implemented

### 1. Backtest controls on the Chart toolbar
- Added a dedicated **Backtest on Chart** toolbar action and expandable panel.
- Built-in strategy selector includes Confluence, Trend, Mean Reversion, Breakout, Smart Money, LIT, LIT X, Ichimoku, and Pattern.
- Saved **visual-builder strategies** are also selectable and are compiled through the existing `ScriptEngine`.
- Backtest results can be cleared without affecting live strategy/indicator state.
- Backtest markers can be toggled ON/OFF independently of live signal history.

### 2. Same strategy logic for live + backtest
- Built-in tests resolve the strategy from `StrategyLibrary`.
- Saved visual strategies compile through `ScriptEngine` and are evaluated with the same function contract used by the chart.
- Execution is delegated to the existing `BacktestEngine`, preserving its no-look-ahead prefix evaluation, spread/slippage, commission, risk sizing, and metrics.

### 3. Closed-bar / data-integrity rules
- **Simulated/synthetic data is rejected** for on-chart backtests.
- Non-time bars (Heikin-Ashi / Renko) are rejected for executable-price backtesting; switch to `Time` bars first.
- If the newest time candle is still forming, it is excluded from the research run. The backtest therefore evaluates closed bars only.
- A minimum data/warm-up requirement is enforced before running a strategy.

### 4. Win / loss / breakeven counts
The chart backtest state now reports:
- total executed backtest signals/trades;
- winning signals;
- losing signals;
- breakeven signals;
- win rate;
- net P/L;
- profit factor;
- number of closed bars tested.

A compact summary is rendered over the chart, e.g.:

`BT LIT X  •  18  W 11  L 7  B 0  •  WR 61.1%`

### 5. On-chart backtest markers
Each completed trade is projected back onto the candle chart:
- directional entry arrow;
- low-alpha dashed entry→exit connector;
- green `W` exit badge for wins;
- red `L` exit badge for losses;
- amber `B` exit badge for breakeven.

Markers include both candle indices and timestamps. If older history is prepended after a run, the renderer verifies the hinted index and falls back to timestamp lookup, preventing marker drift.

### 6. Replay future-leak protection
Completed backtest outcomes and the summary card are hidden while Replay is active. This prevents a completed historical backtest from revealing future W/L outcomes during candle-by-candle replay.

### 7. Stale-result detection
The state records the source bar count and latest source timestamp at run time. If new bars arrive or older history is loaded afterward, the summary is flagged:

`NEW BARS — RERUN`

The old result remains visible for comparison, but is explicitly identified as a snapshot rather than silently presented as current.

### 8. Custom strategy edit/delete safety
If a saved visual-builder strategy used by the chart backtest is edited or deleted, its previous backtest projection is invalidated. This prevents stale markers from being attributed to a changed strategy definition.

## Main files changed

- `app/src/main/java/com/foxtrader/app/domain/model/Backtest.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartUiState.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartBacktestMapper.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartScreen.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/components/CandleChart.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/components/layers/ChartSignalLayer.kt`
- `app/src/test/java/com/foxtrader/app/feature/chart/presentation/ChartBacktestMapperTest.kt`

## Validation performed in this environment

- Kotlin domain model + new mapper were compiled locally with minimal dependency stubs to validate their real Kotlin types and syntax.
- Modified Kotlin sources were passed through `kotlinc` parser/type analysis; no syntax/unclosed-token errors were found. Android/Compose symbols cannot resolve without Gradle dependencies.
- Full Gradle unit-test execution was attempted, but the wrapper requires `gradle-8.9-bin.zip` and this environment cannot resolve `services.gradle.org` (`UnknownHostException`). Therefore a full Android compile is **not claimed**.
- ZIP integrity is checked after packaging.

## Phase 3 next extension candidates

The foundation is now ready for the next Phase 3 additions without changing the execution contract:
- explicit date/range selector and history prefetch before run;
- per-signal detail sheet (entry, SL, TP, exit reason, R multiple, confidence);
- equity curve overlay/pane linked to chart time;
- comparison mode for two strategies on the same symbol/timeframe;
- parameter sweeps / optimizer and out-of-sample validation.

---

## Phase 3 completion extension (full-audit pass)

The extension candidates above have now been partially promoted into the shipped Phase 3 chart workflow:

- `Loaded / 1M / 3M / 6M / 1Y` history ranges;
- bounded real-history prefetch (20,000 visible-bar cap);
- explicit `PARTIAL RANGE` coverage state;
- equity curve/sparkline and expanded metrics (return, max DD, expectancy, average R);
- source-series stale-result warning;
- in-flight symbol/timeframe race guard during history paging;
- LIT X strategy context uses the actual symbol/timeframe rather than a hard-coded H1 context;
- app-wide audit fixes for market-data provenance, auth refresh concurrency, release host/signing/cleartext policy, lifecycle cleanup and telemetry privacy.

See `PHASE3_FULL_AUDIT.md` and `VALIDATION_RESULTS.txt` for the complete audit and remaining production limitations.
