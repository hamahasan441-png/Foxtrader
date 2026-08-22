# FoxTrader Final Audit — Phase 15

## Delivered scope

This audited source tree contains the Phase 9–15 Deriv, execution, signal-intelligence, provider-routing and Binary3m work. The final Phase 15 addition is a deterministic M1 signal/research path for a three-minute Deriv Rise/Fall-style fixed expiry, shared by the live chart and Backtesting Lab.

## Provider boundaries

- Deriv: dedicated public market-data WebSocket for chart/history plus the existing authenticated trading flow.
- MetaTrader: MetaApi-backed market history/live routing with older-history paging and broker-native symbol preservation.
- Other providers: strict routing; a selected provider is never silently replaced with another venue for live data.
- Provider changes invalidate/purge stale chart state so historical/live candles from different venues are not intentionally merged.

## Deriv Binary 3m model

- M1 raw candles only.
- Signal is confirmed only after candle `i` closes.
- Modeled entry is the OPEN of candle `i + 1`.
- Three-minute expiry settles at the CLOSE of candle `i + 3`.
- EMA 9/21/50 trend, EMA21 slope, ADX/DI strength, EMA pullback/reclaim, candle rejection/directional close, RSI continuation, MACD momentum and ATR regime filtering.
- Configurable minimum confidence and payout ratio.
- Payout-aware break-even rate and edge-vs-break-even reporting.
- No Martingale. Overlapping contracts are disabled by default.
- Same signal engine is used by live chart markers and backtesting.
- Walk-forward and deterministic Monte Carlo validation are computed for binary results.
- Zero-PnL/TIE contracts remain neutral in validation analytics.

## Data-integrity protections

- Binary template locks provider to Deriv and timeframe to M1.
- Backtesting refreshes the selected live provider before measurement.
- Binary mode requests a deep 5,000-bar M1 history target.
- Synthetic fallback is rejected for live-provider backtests.
- Fixed-expiry markers are excluded from the ordinary SL/TP outcome evaluator.
- Binary confidence is not mutated by chart-only confluence boosts.

## Validation completed

- Phase 9 Deriv preflight: PASS
- Phase 10 Deriv switcher preflight: PASS
- Phase 11 audit preflight: PASS
- Phase 12 professional execution preflight: PASS
- Phase 13 signal intelligence preflight: PASS
- Phase 14 end-to-end provider preflight: PASS
- Phase 15 Deriv Binary3m preflight: PASS
- Release security preflight: PASS
- Room migration verifier v1→v10: PASS
- Backend pytest suite: PASS
- Binary + analytics pure-Kotlin compile/harness: PASS
- Modified-Kotlin parser-shape scan: no syntax/redeclaration/unclosed-token pattern found

## Android build boundary

A full Android Gradle compile could not be completed in this audit container. The wrapper requires Gradle 8.9 and attempts to download it from `services.gradle.org`; the container fails DNS with `java.net.UnknownHostException: services.gradle.org`. Therefore this package must not be represented as an APK-compiled build. Source-level/preflight/core validation passed, but the final Android build still needs a machine/CI runner with the Gradle distribution/dependencies available.

## Trading limitation

Historical/backtest performance is not a guarantee of future profitability. Deriv payout varies by contract/market/time; enter the actual net payout ratio shown by the current proposal when evaluating break-even and expectancy.
