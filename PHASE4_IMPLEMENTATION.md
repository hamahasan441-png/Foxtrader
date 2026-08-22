# FOX Trader — Phase 4 Implementation

Date: 2026-08-21
Base: Phase 3 Complete + Full Audit

## Goal

Phase 4 turns the Phase 3 tester/live-signal foundation into a stricter decision layer:

1. robust parameter optimisation rather than single-split curve fitting;
2. multi-timeframe + SMT confirmation in the scanner;
3. a confirmed-only opportunity view;
4. background Phase 4 opportunity alerts with deduplication;
5. adaptive risk sizing that can reduce broker size but can never increase it.

## 1. Walk-forward optimiser robustness gate

`TradeProOptimizer` still performs the normal in-sample/out-of-sample sweep, then adds anchored chronological walk-forward validation.

- Four expanding training folds are followed by unseen validation folds.
- The robustness candidate pool is selected deterministically from the parameter grid, never from rankings that used later bars. This avoids selection leakage into earlier folds.
- The default 27-config grid is evaluated in full; custom grids are bounded at 32 deterministic candidates for predictable cost.
- Each fold records winner, training score, validation score, validation trades, validation expectancy and validation profit factor.
- A 0–100 robustness score and A/B/C/D grade are produced.
- One-click application is blocked unless:
  - data is real/non-synthetic;
  - the best normal candidate is qualified;
  - the walk-forward report recommends the configuration (A/B gate plus positive validation behaviour).

The optimizer UI now shows the robustness grade, score, pass rate, fold passes, winner stability and average unseen expectancy.

## 2. Phase 4 MTF + SMT scanner confirmation

New `Phase4ConfluenceEngine` enriches every normal scanner result with stricter context.

### Higher-timeframe confirmation

- H4 and D1 context is consumed when trustworthy cached data is available.
- EMA20/EMA50 plus latest-close placement determine directional HTF bias.
- Missing HTF data does not become a positive signal. The strict `actionable` gate fails closed when no HTF is available.

### SMT confirmation

- Uses the existing synchronized, confirmed-swing `SmtDivergenceDetector`.
- Only fresh confirmed divergences are considered.
- Peer selection is explicit rather than scanning arbitrary correlations, including EURUSD/GBPUSD, AUDUSD/NZDUSD, major US indices, BTC/ETH, and XAU/XAG relationships.
- Aligned SMT increases score; opposing SMT reduces score and blocks actionability.

### Scanner output

Each result now carries:

- MTF alignment percentage;
- SMT confirmation and peer;
- Phase 4 actionable state;
- adaptive risk multiplier (0.25x–1.00x).

The UI includes a `PHASE 4 CONFIRMED ONLY` filter plus MTF / SMT / Risk / P4 status metrics. The same filter also limits heatmap cells to confirmed symbols, so LIST and HEATMAP cannot disagree about the active filter. Dense confluence tags use horizontally scrollable rows to avoid card overflow on phones.

## 3. Background Phase 4 alerts

`ScanAlertWorker` now has a Phase 4 fallback after the existing AI and TRADEPRO paths.

An alert can only be produced when:

- source provenance is trustworthy;
- the scanner opportunity passes the Phase 4 actionable gate;
- MTF requirements pass;
- no fresh SMT conflict exists;
- the scanner risk regime is not HIGH.

Phase 4 scanner alerts use a 45-minute per-symbol/direction cooldown. `AlertEngine` now supports a per-call cooldown override and its mutable state is synchronized to make concurrent worker/UI access safer.

The previous AI path now returns after dispatch, preventing the worker from also emitting a lower-priority duplicate for the same evaluation.

## 4. Adaptive execution risk

`RiskGatedBrokerExecutor.placeMarketOrder()` accepts a Phase 4 `riskMultiplier`.

Safety contract:

- value must be finite and in `(0, 1]`;
- it can reduce computed/manual volume but can never increase it;
- adjusted monetary risk and risk percentage are recomputed before `RiskEngine.canOpenTrade()`;
- live execution authorization, symbol support, SL/TP validation and the existing risk gate remain mandatory;
- the broker never receives an order until all gates pass.

The scanner and background alert surface the suggested multiplier so the execution layer can consume the same conservative value when an order workflow is connected to that opportunity.

## 5. Data safety / non-repaint discipline

Phase 4 preserves the Phase 3 rules:

- synthetic data cannot produce an actionable Phase 4 opportunity;
- missing MTF data does not count as confirmation;
- SMT relies only on synchronized confirmed swings;
- walk-forward validation uses chronological unseen blocks;
- the robustness candidate pool is data-independent, preventing candidate-selection look-ahead;
- broker risk scaling is down-only.

## Important build limitation

The full Android Gradle test/build/lint pipeline could not run in this environment because the wrapper requires Gradle 8.9 and `services.gradle.org` cannot be resolved here (`UnknownHostException`). This is an environment/network limitation, not a claimed build pass. Standalone Kotlin domain smoke checks and backend validation were run instead; see `VALIDATION_RESULTS.txt`.
