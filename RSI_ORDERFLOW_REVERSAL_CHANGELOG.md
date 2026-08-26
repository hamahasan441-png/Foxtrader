# RSI Orderflow Reversal — changelog and architecture note

Delivers the strategy specified in [`RSI_ORDERFLOW_REVERSAL_SPEC.md`](RSI_ORDERFLOW_REVERSAL_SPEC.md), planned in
[`RSI_ORDERFLOW_REVERSAL_PLAN.md`](RSI_ORDERFLOW_REVERSAL_PLAN.md), with every rule resolution recorded in
[`app/src/main/java/com/foxtrader/app/domain/usecase/rsireversal/RSI_REVERSAL_RULES.md`](app/src/main/java/com/foxtrader/app/domain/usecase/rsireversal/RSI_REVERSAL_RULES.md).

---

## 1. Architecture

A pure domain package, `domain/usecase/rsireversal`, with no Android dependencies:

| File | Responsibility | Spec |
|---|---|---|
| `RsiReversalConfig.kt` | every threshold, preset and rule flag | §44, §48.4 |
| `model/RsiReversalModels.kt` | RSI candle, pivot, pattern point, state, setup, signal | §13, §21 |
| `RsiCandleEngine.kt` | RSI OHLC candles from four Wilder series | §3 |
| `RsiReversalPivotEngine.kt` | non-repaint pivots, shared by price and RSI | §5, §6 |
| `RsiReversalHtfEngine.kt` | P1→P2→P3→final extreme + unbounded recursion, BUY and SELL | §7–§14 |
| `RsiReversalLtfEngine.kt` | sweep / CHOCH / displacement / BOS / retest | §16–§18 |
| `RsiReversalRiskEngine.kt` | stop behind the swept extreme, fixed-R target | §19, §20 |
| `RsiReversalEngine.kt` | orchestrator, MTF composition, backtest entry point | §28, §33 |

**The one property everything else rests on:** `analyze()` is a pure function of the closed-bar prefix. Running it
over candles truncated at bar `t` returns exactly what the full-series run reports for bars at or before `t`. Chart,
replay and backtest therefore call one core, and parity between them is structural rather than something tested for
and hoped. Pivots are consumed on the bar they became knowable, never the bar they formed on.

**BUY and SELL are one code path.** Every directional comparison goes through a single `Ops` abstraction. The mirror
test feeds a price-inverted, RSI-inverted series and asserts identical indices, recursion depth and armed bars, so
the two cannot drift apart.

## 2. What was decided, and why

Three specification points needed a decision rather than a guess. Each is documented in the rules file; two are
config flags with tests covering **both** readings, as §48.4 requires.

- **P4 is the confirmed swing low that undercuts P2**, not the first bar to trade through it. A pivot low's value is
  its wick low, so "a wick is enough" still holds and a liquidity sweep still qualifies — but RSI is measured at the
  extreme the §10 comparison is actually about, and the §11 recursion gets a defined reference.
- **`equalRsiCountsAsFailure`** (default `true`) resolves whether `RSI(P4) == RSI(P2)` within epsilon is a failure to
  confirm. §7.2 writes `>=` and only calls `>` the "preferred strong form".
- **`protectedRsiMode`** (default `HIGHEST`) resolves which RSI swing P3 must break.

Two smaller judgements, also documented: a sweep must **reclaim** its level (without it every continuation bar
qualifies and the stop-hunt premise is lost), and displacement is measured **strictly after** the sweep bar against
the bars leading into it (letting the sweep bar be its own displacement collapses Balanced into Aggressive).

## 3. Two findings from the audit that changed the work

**Name collision.** `RsiOrderFlow` already existed and was labelled "RSI Orderflow Candle" in the UI — but it is an
RSI + volume-delta pressure oscillator, not RSI OHLC candles, and `SignalEvidenceReducer` keys signal families off
that display string. Shipping a second study under the same name would have given the app two different things with
one identity. The existing study is relabelled **"RSI Orderflow Divergence"**, which is what it actually is. That is
a display string only: `SignalSource.RSI_ORDERFLOW`, the `rsiOrderFlow` toggle, the `RSI_ORDERFLOW_CANDLE` enum
constant and every persisted preference are untouched, so nothing migrates and no stored history is re-partitioned.

**Wilder-seed repaint hazard.** Wilder RSI is recursive and seeded from the first bar of whatever series it is given.
This app **prepends older history at runtime** (`ChartDataController.loadOlderHistory`), so a scroll-back re-seeds
the recursion and shifts every historical RSI value slightly — enough, at an exact `RSI(P4) ≈ RSI(P2)` boundary, to
flip a decision and repaint an already-published arrow. Answered with warmup exclusion
(`max(rsiLength × 10, 200)` bars), epsilon comparisons throughout, and a prepend-invariance test that runs the engine
with and without 400 bars of prepended history and requires the overlapping setups to be identical.

## 4. Integration

- **Signals** — `SignalSource.RSI_REVERSAL` with a `SignalIdentity.rsiReversal` event key, drawn by the existing
  shared signal layer. The default chart stays clean: only final entry arrows (§22).
- **RSI pane** — `RsiCandleSubChart` draws RSI as candlesticks with bodies, wicks and the 30/50/70 levels,
  horizontally synchronised with the price chart, matching the supplied reference screenshots (§4). It reads the same
  engine the signal path reads, so what a trader sees and what armed a setup cannot disagree.
- **Debug layer** — `RsiReversalDebugLayer` marks P1, P2, the final extreme and any recursive extremes. Off by
  default (§23).
- **Lower-timeframe data** — `RsiReversalLtfProvider` is the one place a second series is fetched. It is bounded (one
  cached series per symbol/timeframe, refreshed at most once a minute) and **provenance-gated**: FoxTrader seeds
  synthetic bars when a provider is unreachable, and a synthetic series is discarded rather than used, so an entry can
  never be confirmed against generated prices. The study reports no signal instead, which is the honest answer.
- **Settings** — `RsiReversalStudySettings` exposes only the choices a trader should be making. The engine keeps its
  full threshold surface for research; the mapper never overrides the warmup and never enables the §26 filters that
  are meant to stay off until researched.
- **Backtest Lab** — a template driven by the same engine. The selected timeframe is treated as the **entry**
  timeframe and the context timeframe is reconstructed by resampling upward, which is the only direction that adds no
  information the bars did not already carry. The trailing partial bucket is dropped, because an unfinished
  higher-timeframe bar's high and low keep changing as the entry series advances.

## 5. Verification

**53 new tests**; full suite **1 523 tests, 0 failures**.

| Area | Covers |
|---|---|
| Component | RSI candle range/well-formedness/prefix purity, close-only vs series RSI equality, degenerate input, pivot non-repaint, plateau resolution, price/RSI independence |
| HTF pattern | the §39 recursion sequence (no arm at LL3, arm at LL4), §38 divergence positive and negative, §40 wick vs close break, both readings of the equality flag, §27 expiry, the BUY/SELL mirror |
| LTF | the §41 cases (sweep without CHOCH, expired window, valid entry), all three entry presets, no-lookahead, the SELL mirror, 4R geometry both directions, degenerate sweep, stop buffer |
| Engine | replay-equals-history at three cutoffs, setups never disappearing, prepend invariance, warmup exclusion, duplicate identity, HTF-only degradation, signal geometry, malformed/duplicate/out-of-order/gapped/flat input, determinism |
| Integration | settings mapper fidelity and sanitization, §15 mapping both directions, backtest bounds safety, backtest prefix purity, no unfinished context bar, 4R geometry |
| Performance | 100 000 bars analysed in **0.55 s**, well inside a 15 s budget |

## 6. Known limitation

`Timeframe` has no `M3`, so the specification's `3m → 1m` mapping row (§15) is absent. Adding `M3` reaches data
providers, Room entities and stored preferences; it is left to its own change rather than approximated with a nearby
timeframe. Confirmed with the project owner before implementation began.
