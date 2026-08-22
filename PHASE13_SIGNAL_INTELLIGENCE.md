# Phase 13 — Signal Intelligence & Indicator Accuracy

Phase 13 hardens the signal stack requested by the project owner, with priority **LiTX → LiT → SMT → SMS → TradePro**. The goal is not to manufacture a high win-rate claim; it is to make every surfaced signal causal, confirmation-bound, explainable, reproducible between live/replay/history, and measurable with conservative outcome rules.

Gradle/APK validation is intentionally outside this phase at the project owner's request. Acceptance is based on independent Kotlin compilation/smoke runs, source preflights, prior-phase regression checks, Room migration verification, backend tests, syntax/XML checks, and package hygiene.

## Non-repaint contract

All Phase 13 live analysis entry points use `ConfirmedBarPolicy`. Candle timestamps are treated as bar-open timestamps; a candle is usable only after a complete timeframe has elapsed. LiTX, LiT, SMS, SMT, TradePro chart analysis, live strategy evaluation, the standalone LiTX screen, scanner, TradePro Opportunity/Daily Plan/Alerts, and the LiT AI-agent path are all constrained to confirmed prefixes.

The signal series integrity gate rejects duplicate/out-of-order timestamps, non-finite/invalid OHLCV, impossible candle geometry, and insufficient history. Historical and replay signal timestamps are derived from the confirming candle rather than wall-clock render time.

## LiTX

`LitXEngine` is now accuracy-first and profile-aware:

- `SCALPING`, `INTRADAY`, and `SWING` profiles tighten displacement, minimum R:R, confidence, and setup timing.
- Liquidity sweep must precede the structure shift within a bounded window.
- Structure knowledge is delayed by the detector's right-side confirmation bars; unavailable confirmation is never clamped into the existing history.
- Strong MSS, when enabled, requires causal same-direction displacement starting on/after the structure break.
- POI selection is ordered after the sweep and becomes eligible only after it is objectively observable.
- Only the first post-confirmation retest is eligible; stale/repeated POI touches do not emit repeated fresh arrows.
- Directional premium/discount alignment can be required.
- HTF directional alignment is a hard validation gate when configured.
- A configurable confidence floor and grade/R:R filters are all enforced before a signal is emitted.

The settings screen exposes profile, strong-MSS, premium/discount, HTF, confidence, and R:R controls.

## LiT

LiT is now a first-class `LitEngine` shared by the chart, Strategy Library, scanner, and AI agent. Its canonical sequence is:

`Liquidity pool → sweep/reclaim → CHOCH/MSS → causal displacement → POI → first confirmed retest → bounded risk/reward`

The old strategy-specific LiT rule has been replaced by this same engine, eliminating live-vs-backtest logic drift. A structure shift is not accepted until all right-side confirmation bars exist. Displacement occurring before the shift cannot confirm MSS.

The LiT AI agent also treats only BOS/CHOCH/MSS as structure confirmation; IDM remains inducement context. When the canonical Phase 13 LiT setup exists, legacy direct-entry logic is suppressed so one setup cannot receive two votes inside the same agent.

## SMS — Smart Money Structure

Phase 13 introduces `SmsEngine` as the first-class Smart Money Structure layer while preserving the project's existing MSS/SMC terminology. SMS classifies:

- BOS — continuation structure break
- CHOCH — confirmed change of character
- MSS — CHOCH/MSS corroborated by causal displacement

SMS tracks protected high/low context, optionally incorporates a recent aligned liquidity sweep, stamps signals on the confirmation bar rather than the hindsight event bar, and only exposes recent events as actionable chart markers. Older events remain context, not live signals.

## SMT

`SmtDivergenceDetector` was rebuilt around synchronized confirmed evidence:

- malformed/stale peer series are rejected;
- primary and peer bars use bounded timestamp skew alignment;
- a single peer candle cannot be reused for multiple primary bars;
- only confirmed swing highs/lows are compared;
- swing pairs must be synchronized within a bounded aligned-bar distance;
- plateau highs/lows use deterministic left-strict/right-tolerant rules to avoid duplicate hindsight swings;
- weak divergence separation is rejected relative to local average range;
- output is stamped on the first confirmation bar where both swing facts are objectively knowable;
- stale divergences age out of the actionable set.

The chart still shows the underlying divergence context, but the unified trade marker belongs to the confirmation bar.

## TradePro fusion

`SignalFusionEngine` combines independent LiTX, LiT, SMS, SMT, and existing TradePro evidence. It deliberately cannot invent a TradePro trade. An existing TradePro setup may remain executable only if TradePro itself had already reached `EXECUTE`.

Strong opposing institutional evidence or insufficient fusion quality can demote an `EXECUTE` setup back to `CONFIRMATION`. Supportive evidence can adjust confidence, but the chart's generic confluence pass does not boost TradePro a second time after Phase 13 fusion, preventing confidence double-counting.

The chart analysis sheet now displays Phase 13 direction, score, conflict/strong state, and per-engine evidence (`LiTX`, `LiT`, `SMS`, `SMT`, `TradePro`). Indicator toggles allow these sources to be shown independently without hidden engines leaking their own arrows merely because TradePro uses them internally for fusion.

## Editable LiTX / LiT / SMT / SMS settings

Phase 13 now exposes persistent, user-editable controls for the four priority signal engines. Settings are stored in DataStore, clamped to safe ranges before use, and a settings change forces a full signal recompute even on a paused chart. Selecting SCALPING / INTRADAY / SWING applies a complete recommended preset; every advanced value can then be tuned independently.

- **LiTX:** enable, profile preset, minimum grade, HTF alignment, strong MSS, premium/discount alignment, minimum confidence, minimum R:R, displacement ATR, sweep→shift window, shift→retest window.
- **LiT:** profile preset, minimum confidence, premium/discount alignment, setup lookback, sweep→shift window, shift→retest window, minimum R:R, displacement ATR.
- **SMT:** profile preset, comparison period, confirmed swing lookback, minimum correlation, timestamp-skew tolerance, swing synchronization tolerance, maximum signal age, minimum normalized divergence strength, minimum confidence.
- **SMS:** profile preset, swing-confirmation bars, displacement ATR, displacement-gap window, sweep→shift window, maximum signal age, minimum confidence, optional liquidity-sweep requirement, optional displacement requirement for CHOCH/MSS.

These controls never disable the Phase 13 causal rules: future candles remain inaccessible, swing/structure confirmation still requires right-side bars, malformed data remains fail-closed, and unified markers stay on the objective confirmation bar.

## Accuracy measurement

`SignalOutcomeEvaluator` adds conservative, reproducible outcome measurement for signals that contain a real entry, SL, and TP:

- evaluation starts on the bar after the confirmation bar;
- SMS/SMT context-only markers are excluded from trade win-rate statistics;
- if a single OHLC candle touches both SL and TP, the result is recorded as a **loss** because intrabar ordering is unknowable;
- statistics include resolved count, wins/losses, unresolved count, win rate, average R, and profit factor.

This phase does **not** claim a fabricated fixed accuracy percentage. A trustworthy percentage requires symbol/timeframe-specific out-of-sample/walk-forward data. The implementation is designed so those metrics can be measured without same-bar hindsight or future-bar leakage.

## Chart behavior

Unified chart sources are now `LiTX`, `LiT`, `SMS`, `TradePro`, `SMT`, and Strategy. LiTX/LiT use risk-bounded arrows; SMS/SMT are context markers with no fabricated SL/TP. Signal history shows the full source name and explainable confirmations. TradePro signal timestamps are based on the confirmed bar, making replay/history deterministic.

## Validation boundary

Phase 13 does not claim Gradle/APK validation because Gradle was explicitly excluded by the project owner. No real-money execution was needed for this signal-domain phase. See `VALIDATION_RESULTS_PHASE13.txt` and `scripts/phase13_signal_intelligence_preflight.sh` for the reproducible checks.
