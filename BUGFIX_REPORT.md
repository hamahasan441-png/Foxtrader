# Critical Bug Fix Report

Scope: crashes, non-logic (silent numeric corruption) and concurrency defects in
the FoxTrader domain and presentation layers.

Method: the pure-domain layer was compiled standalone and **executed** against a
fuzz harness of degenerate inputs (empty/1/2-bar series, flat prices, zero
volume, zero and negative periods, zero-priced bars, 1e-9 and 1e12 prices,
duplicate timestamps, zero/negative balances, 8-thread concurrent writes).
Every issue below was reproduced as a real failure before being fixed, and the
harness re-run clean afterwards.

## Why these inputs are reachable

Indicator periods are **not** internal constants. They arrive from:

- the plugin SDK — `params["period"]?.toInt() ?: 14` in every builtin indicator,
  read straight from a user-authored script with no validation,
- indicator settings and strategy scripts.

Bucket/row counts, account balance, leverage and stop price are likewise
user-editable. "Impossible" values are routine.

---

## 1. Crashes (reproduced `ArrayIndexOutOfBounds` / `NegativeArraySize` / `IllegalArgument`)

| # | Site | Trigger | Failure |
|---|------|---------|---------|
| 1 | `TechnicalIndicators.calculateATRIncremental` | `period <= 0` | `atr[period - 1]` → index −1 |
| 2 | `TechnicalIndicators.calculateADXIncremental` | `period <= 0` | slips past the `len < period * 2` guard, then seeds from `tr[-1]` |
| 3 | `TechnicalIndicators.calculateRSI` | `period <= 0` | writes `rsi[period]` at a negative index |
| 4 | `TechnicalIndicators.calculateSMA` | `period <= 0` | `candles[i - period]` reads past the end |
| 5 | `TechnicalIndicators.calculateMomentum` | `period < 0` | loop starts negative, reads past the end |
| 6 | `SuperTrend.calculate` | `atrPeriod <= 0` | inherits the ATR crash |
| 7 | `SmcDetector.computeVolumeProfile` | `buckets <= 0` | `NegativeArraySizeException`, or empty `coerceIn(0, -1)` range |
| 8 | `MarketProfile.compute` | `rowSize <= 0` | same two failures |
| 9 | `RiskEngine.calculatePositionSize` | account balance `0.0` | `riskPercent` = Infinity trips `require(riskPercent >= 0.0)` inside `PositionSizeResult` and throws **out of the sizing call** |
| 10 | `ChartAiCoordinator.computeFingerprint` | empty series | `first()`/`last()` on an empty list |

Fix: periods are clamped once at the source via a documented `sanitizePeriod`
helper (and locally where an indicator has its own window math), so every
indicator is **total** — bad configuration degrades to a degenerate-but-valid
series instead of taking the chart down.

## 2. Silent numeric corruption (the dangerous class — no crash, wrong numbers)

| # | Site | Trigger | Consequence |
|---|------|---------|-------------|
| 11 | `calculateVolatility` | any close `== 0.0` | `0/0` = NaN; **one** NaN poisons mean+variance for the whole series. Feeds stop distances and volatility-based sizing |
| 12 | `calculateEMA` / `calculateMACD` | `period == -1` | `k = 2/0` = Infinity → every value NaN |
| 13 | `BollingerBands` / `Stochastic` / `Ichimoku` | `period == 0` | empty window → division by zero → NaN bands rendered as a garbage overlay |
| 14 | `RiskEngine.calculatePositionSize` | non-finite intermediate | `(Infinity * 100).roundToInt()` pins to `Int.MAX_VALUE` → a **~21 million lot** order that looks like a real number all the way downstream |
| 15 | `PositionCalculator` | `leverage == 0` | margin rendered as `"Infinity"` — the trade looks free |
| 16 | `BacktestEngine.computeReturns` | balance wiped to `0.0` mid-run | NaN Sharpe/Sortino |
| 17 | `BacktestEngine.calculateMetrics` | `initialBalance == 0` | `returnPercent` = Infinity, which also poisons the Calmar ratio |

Items 14–17 matter most: a backtest report is a decision-making artifact, and a
NaN Sharpe silently invalidates every ranking built on it (strategy comparison,
optimizer objective, analytics report).

## 3. Concurrency

**`RiskEngine.config` was not `@Volatile`** while `peakBalance`, `currentBalance`
and `haltReason` around it already were. It is written from the settings/UI
thread and read by background sizing/gating threads, so a worker could keep
serving a **stale risk configuration indefinitely** — still sizing against the
old risk-per-trade after the user lowered it. `RiskConfig` is an immutable data
class, so a volatile reference swap publishes the whole config atomically.

A dedicated 8-thread × 1 000-trade test asserts `recordTrade` loses no balance
updates (the balance is what every risk gate is evaluated against).

## 4. Structured concurrency — swallowed `CancellationException`

Eight ViewModels wrapped suspending work in `catch (e: Exception)`, which also
catches `CancellationException`. Two consequences: cancellation stops
propagating (breaking structured concurrency), and a routine screen close or
re-run surfaces as a **spurious on-screen error**.

Fixed in `BacktestLab`, `Strategies`, `AlertRules`, `Correlation`, `DailyPlan`,
`OpportunityBoard`, `TradeProRiskDashboard`, `TradeProSimulator` — each now
rethrows `CancellationException` first, matching the pattern `ScannerViewModel`
already used correctly.

---

## Verification

| Check | Result |
|---|---|
| Fuzz suite 1 — 15 datasets × ~45 domain entry points | 70 issues → **0** |
| Fuzz suite 2 — risk/sizing/backtest money paths | 10 issues → **0** |
| Fuzz suite 3 — incremental-vs-full parity, concurrency, indicator bounds | **pass** (no regressions introduced) |
| Fuzz suite 4 — negative/zero periods | 25 issues → **0** |
| **Existing domain unit tests** | **768 passed, 0 failed** |
| New regression tests | **35 passed** |
| Backend `pytest` | **23 passed** |

Incremental-recomputation parity (the live-tick hot path) was verified to still
match full recomputation to 1e-6 for EMA, ATR, VWAP, MACD and ADX, on both the
append and last-bar-update paths.

### Environment note

No Android SDK or JDK was present and Maven/Gradle mirrors were unreachable, so
a full `./gradlew` build could not be run here. Verification instead used a JVM
(`jdk4py`) plus the Kotlin compiler (`npm kotlin-compiler`) to compile and
execute the framework-free domain layer directly. All changed files were also
parse/type-checked. The UI-layer changes (`CancellationException` rethrows,
the `computeFingerprint` guard) are mechanical and were compile-verified against
a coroutines stub; they still warrant a CI run of `assembleDebug`.

## New test files

- `domain/usecase/indicators/IndicatorDegenerateInputTest.kt`
- `domain/usecase/risk/RiskEngineDegenerateStateTest.kt`
- `domain/usecase/calculator/PositionCalculatorDegenerateInputTest.kt`
- `domain/usecase/backtest/BacktestEngineDegenerateConfigTest.kt`
