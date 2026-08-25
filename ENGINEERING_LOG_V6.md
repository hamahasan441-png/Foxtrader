# FOXTRADER v6 — Backtest Lab: mode comparison on real candle replay

You asked for v5; v5 was the per-mode accuracy pipeline in the previous session,
so this is v6. Same constraint throughout: **no compiler, no Gradle, no device.**

---

## What the Backtest Lab already had

I audited it before adding anything, because most of what "next level" usually
means was already there:

- Bar-by-bar execution with no look-ahead
- Next-bar-open fill mode alongside legacy signal-price
- Variable spread, commission, directional slippage
- Conservative SL-before-TP when both are touched in one candle
- Full metrics: Sharpe, Sortino, Calmar, profit factor, expectancy, drawdown
- **Walk-forward split and Monte Carlo with risk of ruin** — `BacktestAnalyticsEngine`
- Six strategy templates including LITX and TRADEPRO
- Visual tester and replay projection onto the chart

This is a good backtester. Rebuilding any of it would have been waste.

## The actual gap

**Mode was not a dimension of the backtest.** The `LITX` template ran through
`StrategyPackageEngine` with the *default* `LitXConfig` — so all four LiT
Adventure modes added in v2 were unreachable from the Lab, and there was no way
to compare them.

Which left the question I have deferred for three sessions unanswerable: **is
SNIPER actually better than PRECISION?** v5 built the live-signal measurement for
it, but that needs roughly 20 resolved signals *per mode* — weeks of waiting, and
SNIPER longest of all, because it is built to fire rarely.

Recorded candles answer it now instead.

---

## What was built

**`domain/usecase/backtest/LitXModeComparisonRunner.kt`**

Runs the same real candle history through every mode and reports them side by
side, with walk-forward and Monte Carlo applied per mode.

**Replay fidelity.** The strategy hands the engine `candles[start..index]` and
accepts a signal only when `confirmationIndex == index` — only what the engine
would have emitted live, on that bar, with no later bar visible. Same right-edge
contract `LitPrefixNonRepaintTest` pins, so the Lab cannot report a trade live
trading could not have taken.

**Thresholds are held constant across modes.** Only `LitXConfig.mode` varies. If
each mode ran its own preset the table would be comparing presets, not rules, and
the answer would be meaningless.

**Thin samples are excluded from ranking, not ranked low.** A mode needs 20
trades to be ranked — the same bar as the live gate, deliberately, so replay
evidence and live evidence are held to one standard. A mode with three lucky
trades must not top the table. When nothing clears the bar the report is
`inconclusive` and names no winner, rather than promoting the least-bad row.

### The trade-off you should know about

`LitXEngine.analyze` is O(window), so evaluating every bar from index 0 would be
quadratic and unusable on a phone. The runner uses a 240-bar trailing window.

This is not free and I am not going to present it as free: an engine that sees
240 bars will not always agree with one that sees 5,000. It is defensible because
it **matches live behaviour** — the chart pipeline also feeds the engine a
bounded display window, so a full-history backtest would be measuring a
configuration that never actually trades. If you change one window, change both.

### Tests

`LitXModeComparisonRunnerTest` — nine tests aimed at the ways this claim could be
dishonest: every mode reported even when it never traded; thin samples excluded
from ranking; inconclusive runs name no winner; determinism; per-mode progress;
mode subsets; window validation; backtest config shared identically; and
**SNIPER's trade count bounded by PRECISION's** on identical thresholds — if that
ever inverts, the mode table is not what it claims to be.

---

## Not wired to the UI

The runner is domain-layer only. Adding a "Compare Modes" tab to
`BacktestLabScreen` is maybe 150 lines of Compose that I cannot render, compile,
or check a single frame of. Same reasoning as the premium-chart work I declined
in v2 and v3, and I would rather hand you a correct engine you can wire up in an
hour than a screen that may not build.

Wiring it: inject `LitXModeComparisonRunner` into `BacktestLabViewModel`, call it
from a new action, render `ComparisonReport.comparison` as rows and
`inadequateSample` as a "not enough trades" note. `winner` is null when
inconclusive — show that state, do not hide it.

---

## Run this

```
./gradlew :app:testDebugUnitTest --tests "*LitXModeComparison*"
./gradlew :app:assembleDebug
```

Six sessions of uncompiled Kotlin. Likely trouble spots here:

1. **Runtime.** Four backtests × full history × a 240-bar engine call per bar is
   seconds, not milliseconds. If the test suite is slow, cut `bars` in the
   fixture — do not cut the analysis window, which would change what is measured.
2. **Vacuity.** If `ranked` is empty in every test, the synthetic walk is not
   producing trades. Widen the fixture rather than lowering
   `MIN_TRADES_FOR_COMPARISON`.
3. **`assertEquals(600, report.barsAnalyzed)`** boxes to the Object overload, as
   the rest of the suite already does. Harmless.

Then run it on real EURUSD H1 history and you will finally have the number that
decides whether the v4 sniper engine is worth building.
