# Phase 15 — Deriv Binary 3m Precision

## Purpose

Phase 15 adds a deterministic, non-repainting research strategy for Deriv Rise/Fall-style directional contracts with a three-minute fixed expiry. It is designed for raw one-minute candles and is shared by the live chart and Backtesting Lab so signal logic cannot silently diverge between display and measurement.

This module is a research/backtesting tool. No strategy can guarantee profitability and the app does not auto-submit a real-money binary contract from a chart signal.

## Causal execution model

- Signal timeframe: M1 only.
- Signal knowledge: closed bar `i` only; no future candle reads.
- Backtest entry: OPEN of bar `i + 1` (bar-open approximation of the earliest post-confirmation entry).
- Fixed expiry: CLOSE of bar `i + 3`, which is three elapsed one-minute bars after the modeled entry.
- Direction: BULLISH = CALL, BEARISH = PUT.
- Ties: unchanged expiry price is recorded as TIE.
- Position sizing: fixed percentage of current balance; no Martingale.
- Overlap: disabled by default. A new setup may be taken only after the previous contract has expired.

## Signal model

The signal score combines causal, volume-independent evidence because Deriv history may not expose exchange volume:

1. EMA 9 / 21 / 50 trend alignment.
2. EMA21 slope in the same direction.
3. ADX + DI directional strength.
4. Pullback into the EMA 9/21 zone and reclaim.
5. Directional close / rejection evidence.
6. RSI continuation regime rather than overextended extremes.
7. MACD histogram momentum confirmation (bonus).
8. ATR range regime to reject dead candles and abnormal one-bar spikes.

Default minimum confidence is 72/100. The Backtesting Lab exposes the threshold so sensitivity can be measured without changing source code.

## Backtest economics

Binary payout is not assumed to be constant. The Lab accepts a configurable net payout ratio. A value of `0.85` means a 100 stake returns 85 net profit on a win and loses 100 on a loss.

Break-even win rate is computed as:

`1 / (1 + payoutRatio)`

At an 85% payout the break-even win rate is about 54.05%.

Reported metrics include wins/losses/ties, win rate, payout-aware break-even, edge versus break-even, net P/L, return, expectancy, profit factor, maximum drawdown, streaks, final balance, and the equity curve.

The same result is also passed through the Backtesting Lab validation layer: a chronological 70/30 walk-forward split plus deterministic Monte Carlo trade-order randomization. The Lab reports out-of-sample profit factor/stability, 95% drawdown, risk-of-ruin and validation recommendations. Refunded/zero-PnL TIE outcomes are treated as neutral rather than losses in these analytics.

## Data integrity

- Selecting the Binary 3m template forces DataProvider.DERIV and Timeframe.M1.
- The Backtesting Lab refreshes the selected provider before every measurement.
- Binary 3m requests up to 5,000 current M1 bars from Deriv.
- Synthetic fallback is rejected for all live-provider backtests.
- Provider switching is strict; no hidden cross-provider live fallback is allowed.
- The chart signal is enabled only on Deriv + M1 + raw TIME candles. Heikin-Ashi and Renko are intentionally rejected for fixed-expiry timing.
- Binary3m confidence is excluded from chart-only confluence boosts so the displayed score matches the backtest engine's score.

## Live chart

Enable `Deriv 3m (M1)` in Strategy signals. B3 markers are plotted on the closed confirmation bar. The label explicitly says CALL/PUT and `enter next M1`; it does not fake a future entry price that was unknown at confirmation time.

## Deriv API compatibility

The native Deriv proposal builder uses the current `underlying_symbol` field and supports `duration` plus `duration_unit`. Actual order submission remains behind the app's existing explicit proposal/review/confirmation flow.

## Validation

- Pure Kotlin Binary3m engine compile: PASS.
- Deterministic prefix-stability / non-repaint harness: PASS.
- Next-M1 entry and +3-bar expiry harness: PASS.
- Payout-aware break-even calculation: PASS.
- Binary walk-forward + Monte Carlo analytics compile/integration: PASS.
- Neutral TIE handling in validation analytics: PASS.
- No-overlap sizing harness: PASS.
- Phase 9–15 preflights: PASS after package cleanup.
- Release security preflight: PASS.
- Room migration v1→v10 verifier: PASS.
- Backend pytest suite: PASS.

Full Android Gradle compilation could not be executed in the audit environment because the Gradle wrapper cannot resolve `services.gradle.org` to download Gradle 8.9 (`UnknownHostException`). This is an environment/network limitation; it is not recorded as a successful Android compile.
