# RSI Orderflow Reversal — Implementation Plan

Status: **PLAN ONLY — no production code written yet.**
Spec of record: [`RSI_ORDERFLOW_REVERSAL_SPEC.md`](RSI_ORDERFLOW_REVERSAL_SPEC.md) (verbatim copy of the delivered
master plan, §1–§52). Visual references: the two supplied chart screenshots (EURUSD 15m HTF with points 1–4 and an
"Rsi Orderflow Candle" pane; EURUSD 5m LTF confirmation zone). Clean-room implementation — no third-party code copied.

This document is the result of spec §48.1 ("first audit the existing project architecture") and defines *what will be
built, where it plugs in, and how it is proven*. It is written to be executed phase by phase.

---

## 1. Audit findings

The strategy does **not** land on an empty codebase. FoxTrader already has most of the substrate:

| Spec requirement | Existing asset | Verdict |
|---|---|---|
| §3 RSI engine | `TechnicalIndicators.calculateRSI(candles, period)` | Wilder/RMA, **close-only** — needs a price-selector variant |
| §5 Price structure | `AnalyzeMarketStructureUseCase`, `SwingPoint`/`StructureBreak`/`MarketStructure` | Reusable, correct non-repaint pivots |
| §16–17 LTF sweep/CHOCH/BOS | `SmcDetector` (liquidity, order blocks, FVG, AMD), `LitProStructureDetector` | Partially reusable; needs a dedicated LTF entry engine |
| §21 Signal object | `ChartSignal` + `SignalIdentity` + `SignalSource` | Extend, don't replace |
| §22 Chart arrows | `ChartSignalLayer` (shared, dedupes on `eventKey`) | Reuse as-is |
| §4 RSI sub-pane | `ChartPaneStack`, `RsiSubChart`, `RsiOrderFlowSubChart` | Pattern exists; new candle pane needed |
| §33–35 Backtest | `BacktestEngine`, `HistoricalBacktestRunner`, Backtest Lab | Reuse; register a new template |
| §28 MTF | `MarketRepository.getSourcedCandles(symbol, timeframe)` | LTF fetch is possible; nothing wires it into chart signals yet |
| §44 Settings UI | `ChartStudySettings`, `IndicatorPanel`, `ChartStudyCornerControls` | Extend |

The most recent comparable engine, **Nascent FX** (commit `59dcc67`, 39 files, 4 480 lines, 39 tests), is the
integration template this plan mirrors file-for-file.

### 1.1 Blocking finding — name collision

`RsiOrderFlow.kt` already exists and `ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE` is labelled
**"RSI Orderflow Candle"** in the UI. It is **not** what the spec means. The existing study is an
*RSI + volume-delta-pressure oscillator with divergence detection* — two line series, no RSI OHLC candles anywhere.
The spec's §3 engine (RSI Open/High/Low/Close rendered as candlesticks) does not exist in this codebase.

Shipping a second thing called "RSI Orderflow Candle" would give the app two differently-behaving studies under one
name, and `SignalEvidenceReducer` already keys signal families off that display string.

**Resolution (proposed):**
- New system's user-facing label: **"RSI Orderflow Reversal"**; `SignalSource.RSI_REVERSAL`; package
  `domain/usecase/rsireversal/`; class prefix `RsiReversal*`.
- Existing oscillator relabelled **"RSI Orderflow Divergence"** (display string only — enum constants, toggle field
  `rsiOrderFlow`, `SignalSource.RSI_ORDERFLOW` and persisted preferences all stay untouched, so no migration).
- `SignalEvidenceReducer` gains the two new strings alongside the existing ones.

### 1.2 Blocking finding — Wilder RSI seed vs. history prepend

Spec §32.4 demands "reload must reproduce the same signals". This app **prepends older history at runtime**
(`ChartDataController.loadOlderHistory` / `preloadHistoryBackTo`). Wilder RSI is recursive: changing the first bar
changes every downstream RSI value slightly, which can move an RSI pivot by a fraction of a point and — at an exact
`RSI_Low(P4) ≈ RSI_Low(P2)` boundary — flip a decision. That would silently repaint historical arrows after a scroll-back.

**Mitigation (three layers, all testable):**
1. **Warmup exclusion.** No signal may be emitted before `warmupBars = max(rsiLength * 10, 200)`; by then the Wilder
   seed's influence is far below the RSI epsilon.
2. **Epsilon comparisons** everywhere (spec §25), never `==` or bare `<`/`>` on RSI values.
3. **A prepend-invariance test**: compute signals on `candles[k..n]`, then on `candles[0..n]`, and assert every signal
   in the overlapping region is byte-identical.

### 1.3 Non-blocking findings

- **No `M3` timeframe.** `Timeframe` is `M1, M5, M15, M30, H1, H4, D1, W1, MN`. Spec §15's `3m -> 1m` row cannot be
  expressed. Adding `M3` touches providers, Room entities and persisted prefs. **Recommendation: ship the mapping table
  without the 3m row**; add `M3` later as its own change if wanted.
- **Provenance is enforced.** `getSourcedCandles` returns `SourcedCandles`; the repo deliberately removed the
  unsourced overload. The LTF fetch must respect it — **synthetic LTF bars must never confirm an entry.**
- `ChartDataController` is single-timeframe. LTF confirmation needs a second, bounded fetch (§4.5 below).

---

## 2. Target architecture

New package `com.foxtrader.app.domain.usecase.rsireversal` — pure domain, no Android imports, deterministic:

```
rsireversal/
├── RsiReversalConfig.kt          §44 settings, all thresholds, presets, validated in init{}
├── model/RsiReversalModels.kt    §13 states, §21 signal, P-points, events, LTF patterns
├── RsiCandleEngine.kt            §3  RSI OHLC candles (4 Wilder series + canonical high/low)
├── RsiReversalPivotEngine.kt     §5/§6 one non-repaint pivot engine, used for BOTH price and RSI
├── RsiReversalHtfEngine.kt       §7–§12 P1→P2→P3→P4→recursive state machine (BUY + mirrored SELL)
├── RsiReversalLtfEngine.kt       §16–§18 sweep / CHOCH / displacement / BOS / retest
├── RsiReversalRiskEngine.kt      §19–§20 SL behind final sweep, TP = 4R
├── RsiReversalEngine.kt          orchestrator: HTF ⊕ MTF map ⊕ LTF ⊕ risk → Signal list
└── RSI_REVERSAL_RULES.md         §49.19 rule documentation + every ambiguity resolved
```

**Core contract (mirrors Nascent, and is what makes §31/§32/§36 achievable):**

> `RsiReversalEngine.analyze(htf, ltf, config)` is a **pure function of the closed-bar prefix**. Running it over
> `candles[0..t]` returns exactly what the full-series run reports at bars `≤ t`.

Chart, replay and backtest therefore share one core — parity is structural, not a thing we test for and hope.

### 2.1 Two decisions the spec leaves ambiguous

Per spec §48.4, each is isolated behind a named config flag with tests for **both** interpretations:

| Ambiguity | Flag | Default |
|---|---|---|
| §7.2 "RSI must not confirm": is `P2.rsiLow == P1.rsiLow` (within epsilon) a valid divergence? | `equalRsiCountsAsFailure` | `true` (spec writes `>=`, prefers `>`) |
| §8 "the relevant protected RSI high" — the highest RSI swing high between P1 and P2, or the most recent one before P2? | `protectedRsiHighMode = HIGHEST \| MOST_RECENT` | `HIGHEST` |

---

## 3. Rule model (what actually gets coded)

Restated compactly so implementation can't drift. BUY shown; SELL is the exact mirror and is generated from a shared
direction-parameterised code path, never copy-pasted (§12, §47.8).

```
P1   = confirmed price swing low                        → store index/time/priceLow/rsiLow
P2   = priceLow < P1.priceLow  AND  rsiLow ≳ P1.rsiLow   → DIVERGENCE_FOUND
P3   = RSI_Close breaks protectedRsiHigh upward          → RSI_BREAK_CONFIRMED   [close break, configurable]
P4   = price low < P2.priceLow (WICK is enough)          → CHECK_FINAL_RSI
        ├ rsiLow(P4) > rsiLow(P2)  → BUY_ARMED
        └ rsiLow(P4) < rsiLow(P2)  → reference = P4, WAIT_RECURSIVE_EXTREME → P5, P6, … (unbounded, §11)
BUY_ARMED → LTF window (3–12 bars, configurable) → sweep + CHOCH [+ displacement] [+ BOS + retest]
          → entry, SL behind final LTF swept low, TP = entry + 4R
```

Event classes are tracked separately per §24 (`TOUCH` / `WICK_BREAK` / `CLOSE_BREAK`), with the spec's defaults:
final price extreme = wick, RSI P3 = close, LTF CHOCH/BOS = close.

Duplicate protection key (§30): `symbol + htf + p1Index + p2Index + finalReferenceIndex`.

---

## 4. Delivery phases

Ordered per spec §46. **A phase does not start until the previous one's tests are green.**

### Phase 1 — RSI Orderflow Candle engine (§3, §37)
- Add `TechnicalIndicators.calculateRSI(values: DoubleArray, period)` (price-selector variant); keep the existing
  close-only overload delegating to it so no caller changes.
- `RsiCandleEngine` produces `RsiCandle(open, high, low, close, timestamp, index)` with
  `high = max(rO,rH,rL,rC)`, `low = min(...)` exactly per §3.2.
- Incremental append path + bounded cache (Nascent's pattern) so a tick doesn't rebuild 100k bars.
- Tests: range 0–100, OHLC validity, `high ≥ max(open,close)`, `low ≤ min(open,close)`, no NaN after warmup,
  **incremental == full recalculation**, prepend invariance (§1.2).

### Phase 2 — Pivot engine (§5, §6)
- One `RsiReversalPivotEngine` over an abstract `(highOf, lowOf)` accessor, run twice: once on price, once on RSI candles.
- Default `left=2 / right=2`; strict inequality on the right resolves equal-level plateaus deterministically.
- Confirmed pivots are immutable; each carries `confirmedIndex = pivotIndex + right`.
- Tests: no pivot without full right side; a confirmed pivot never moves when bars are appended; equal-level plateaus.

### Phase 3 — HTF state machine (§7–§14, §38–§40, §27)
- Formal `RsiReversalState` enum (§13) — a real state machine, not scattered `if`s (§13 is explicit about this).
- P1/P2 divergence, P3 RSI structure break (wick + close variants), P4 + **unbounded recursion** (§11).
- Setup expiry: max bars P1→P2, P2→P3, P3→P4, max recursive depth (§27).
- Tests: the §39 synthetic sequence (`LL1→LL2→LL3→LL4` / `HL2→LL3→HL4`, arm only at LL4), §40 P3 cases
  (wick-only, close, equal level, false breakout, repeat), §38 divergence positive/negative, and a **BUY/SELL mirror
  test** that feeds a price-inverted series and asserts perfectly mirrored output.

### Phase 4 — MTF synchronisation (§15, §28, §29)
- Configurable HTF→LTF map (`1D→4H, 4H→1H, 1H→15m, 30m→5m, 15m→5m, 5m→1m`; no 3m row — see §1.3).
- **Strict no-lookahead**: an armed HTF setup may only consume LTF bars whose `timestamp ≥ HTF armed bar close`.
- LTF entry window: 3–12 LTF bars, then `EXPIRED`.
- Tests: alignment across DST/timezone changes, no duplicated LTF processing, incomplete-candle handling.

### Phase 5 — LTF confirmation + risk (§16–§20, §41)
- `E1` sweep→CHOCH, `E2` sweep→displacement→BOS, `E3` CHOCH→HL→BOS→retest; presets
  Aggressive / **Balanced (default)** / Strict (§18).
- Displacement measured against recent average body **or** ATR multiple (configurable).
- SL behind the final LTF swept low (+ optional spread/ATR buffer); TP = **4R** (§20).
- Tests: sweep without CHOCH → no entry; CHOCH without HTF armed → no entry; armed + valid → entry; expired → no entry.

### Phase 6 — Integration (the Nascent 39-file pattern)
| File | Change |
|---|---|
| `domain/model/ChartSignal.kt` | `SignalSource.RSI_REVERSAL` |
| `domain/model/SignalIdentity.kt` | `rsiReversal(...)` event key |
| `domain/usecase/chart/SignalComputer.kt` | emit `ChartSignal` on the confirmation bar only |
| `signalintel/SignalEvidenceReducer.kt` | family mapping + relabel strings (§1.1) |
| `ChartUiState.kt` | `IndicatorToggles.rsiReversal` |
| `ChartStudySettings.kt` | `RsiReversalStudySettings` + `sanitized()` |
| `RsiReversalConfigMapper.kt` | settings → engine config |
| `ChartViewModel.kt` | bounded LTF fetch (provenance-checked), engine invocation, result cache |
| `ProductionAnalysisSystem.kt` | new canonical engine entry + relabel of the old one |
| `IndicatorPanel.kt`, `ChartStudyCornerControls.kt` | toggle + settings tabs (§44) |
| `components/RsiCandleSubChart.kt` | **new** RSI candle pane (§4) |
| `layers/RsiReversalDebugLayer.kt` | **new**, P1–P4/P5+/CHOCH/BOS/sweep labels, **off by default** (§23) |
| `layers/ChartSignalLayer.kt` | badge for the new source |
| `LiveSignalArchive.kt`, `IndicatorReadiness.kt` | register source |
| `BacktestLabUiState/ViewModel.kt` | Backtest Lab template (§33) |

The RSI candle pane (§4) renders bodies + wicks, 30/50/70 levels, bull/bear colouring, dark theme, horizontally
synchronised with the main chart — matching the supplied screenshots. Main chart stays clean: **only final entry
arrows** (§22), debug labels behind a switch.

### Phase 7 — Backtest & statistics (§33–§35)
Per-setup records; aggregate win rate, average R, total R, expectancy, profit factor, max drawdown, streaks, average
bars to confirmation, average recursive depth. **Pattern-split reporting** per §35: Direct vs. Recursive depth 1 vs.
depth 2+, wick vs. close P3, Aggressive/Balanced/Strict, BUY vs. SELL.

### Phase 8 — Verification (§36, §42, §43, §47)
- **Replay parity**: feed candles one at a time, log `state before → event → state after`, assert
  `replay signals == historical signals` exactly.
- **Reliability**: empty data, 1 candle, insufficient history, gaps, duplicate/out-of-order candles, symbol change,
  timeframe change, background/resume, reconnect, 100k+ bars.
- **Performance**: incremental update budget asserted by test (Nascent's `NascentPerformanceTest` pattern).

---

## 5. Acceptance criteria

Taken from spec §47 — the work is not done until all hold:

- [ ] RSI Orderflow candles render correctly (matching the reference screenshots)
- [ ] Price swings and RSI swings are non-repainting
- [ ] P1 / P2 / P3 / P4 detection correct; recursive reference movement correct
- [ ] BUY and SELL are exact mirrors (asserted by an inverted-series test)
- [ ] LTF confirmation works; historical signals persist; no duplicate arrows
- [ ] RR calculated correctly (4R default)
- [ ] Live == replay == historical, exactly
- [ ] Prepend-invariance holds (§1.2 — this repo's specific hazard)
- [ ] 100k+ bars stable, no crash
- [ ] Full existing suite (currently 1 464 tests) still green, plus ~45 new tests

Per spec §48.5: **no TODOs, placeholders, fake signals, hard-coded outputs or unfinished branches** in the production path.

---

## 6. Decisions (confirmed)

All three were put to the project owner and answered before Phase 1 began:

1. **Naming — confirmed.** The new engine is **"RSI Orderflow Reversal"**. The existing oscillator is relabelled
   **"RSI Orderflow Divergence"** as a display string only: `SignalSource.RSI_ORDERFLOW`, the `rsiOrderFlow` toggle
   field, the `RSI_ORDERFLOW_CANDLE` enum constant and every persisted preference stay exactly as they are, so there
   is no migration and no stored history is re-partitioned.
2. **LTF data — confirmed.** The engine may issue a second, bounded `getSourcedCandles` fetch per armed setup, so
   §16–§18 are honoured in full. Provenance is enforced: **synthetic LTF bars can never confirm an entry.**
3. **3m timeframe — deferred.** The mapping table ships without the `3m → 1m` row. Adding `M3` reaches providers,
   Room entities and stored preferences, and belongs in its own change rather than widening this one.

## 7. Estimated scope

~4 500–5 500 lines across ~40 files (13 new domain files, ~15 integration edits, ~10 test files), tracking the Nascent
precedent closely. Phases 1–3 are the load-bearing majority; phases 6–8 are mechanical once the core is proven.
