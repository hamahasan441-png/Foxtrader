# FOXTRADER — Engineering Log, Session 1

Scope of this session: repository map, non-repaint audit of the three production
analysis systems, one verified defect fixed with a regression test.

---

## 0. Environment constraints (read this before trusting any "verified" claim)

The master prompt specifies an autonomous IMPLEMENT → BUILD → TEST → PROFILE
loop. The build half of that loop is not available in this environment:

| Capability | Available | Consequence |
|---|---|---|
| Read/edit repository | yes | code changes are real |
| JDK 21 | yes | JVM logic can be exercised |
| Kotlin compiler | **no** | cannot compile the module |
| Gradle / Android SDK | **no** | no `assembleDebug`, no `testDebugUnitTest`, no lint |
| Network | **no** | no dependency resolution |
| Android device/emulator | **no** | no profiling, no frame timing, no memory capture |

So: **nothing below is claimed on the basis of a green build.** Where I say
"verified", it means the algorithm was ported line-for-line to a runnable
reference implementation and the property was measured. Where I could not
measure, I say so. Performance claims are absent from this log entirely,
because I cannot profile and a static reading of a render loop is a hypothesis,
not a measurement.

---

## 1. Runtime architecture map (as built, not as documented)

Verified by tracing call sites rather than filenames.

```
ChartViewModel
  └─ indicatorCoordinator.processCandles(displayCandles, toggles, ...)
  └─ ConfirmedBarPolicy.latestConfirmedIndex(displayCandles, timeframe, now)
        └─ signalCandles = displayCandles[0 .. latestConfirmedIndex]   ← closed-bar prefix
             ├─ litXEngine.analyze(...)          → LiT Adventure
             ├─ litEngine.analyze(...)           → LiT May Madness
             ├─ smsEngine.analyze(...)           → internal (SMS)
             ├─ smtDivergenceDetector.detect(...)→ SMT   (peers also trimmed to confirmed prefix)
             └─ rsiOrderFlowSignalEngine.analyze(...) → RSI Orderflow (TIME bars only)
        └─ signalFusionEngine.fuse(tradePro, litX, lit, sms, smt, latestConfirmedIndex)
             └─ ChartUiStateMapper → ChartUiState → CandleChart → ChartSignalLayer
                  ├─ drawSmtDivergences()  — historical rays at primaryIndex..confirmationIndex
                  └─ unified arrow renderer — anchored to each signal's confirmation bar
```

Canonical product-name binding, confirmed in
`feature/chart/presentation/ProductionAnalysisSystem.kt`:

| Product system | Backing engine |
|---|---|
| LiT Adventure | `domain/usecase/litx/LitXEngine` |
| LiT May Madness | `domain/usecase/signalintel/LitEngine` (+ `LitProStructureDetector`, `LitSequenceValidator`) |
| SMT | `domain/usecase/smt/SmtDivergenceDetector` |
| RSI Orderflow Candle | `domain/usecase/signalintel/RsiOrderFlowSignalEngine` |

`withProductionAnalysisSystem()` rebuilds `IndicatorToggles` from a clean
instance on every switch, so stale legacy toggles cannot leave a fifth system
running behind the selector. The three systems are genuinely independent
implementations — no shared strategy body, no merged generic SMC path. That
part of the prompt's scope requirement is already satisfied.

### Non-repaint posture of each engine

- **LiT Adventure** emits only when `isFreshRetest` — i.e. the first POI retest
  lands exactly on `candles.lastIndex`. One signal, at the newest confirmed bar.
- **LiT May Madness** emits only when `retestIndex == candles.lastIndex`, with an
  explicit early return on the "first retest already behind us" case.
- Both therefore publish **at most one event, always at the right edge**. Under
  prefix evaluation they are non-repainting by construction — their historical
  markers exist only because the chart accumulated them bar by bar.
- **SMT is different in kind.** `detect()` returns a *list of historical events*
  retained for `maxSignalAgeBars` (default 24) and re-derives that whole list
  from scratch on every tick. Every returned event is re-decided on every bar.
  That makes SMT the only one of the three where a published historical marker
  can change underneath the user — and that is where the defect was.

---

## 2. Defect found and fixed — SMT peer-pair look-ahead (repaint)

**File:** `domain/usecase/smt/SmtDivergenceDetector.kt`
**Function:** `synchronizedPairs`
**Class:** future-data leakage → visible repaint of a confirmed historical marker
**Severity:** high — SMT is a published-history engine, so this is user-visible

### Root cause

For each adjacent primary swing pair `(p0, p1)`, the matcher scanned every
adjacent peer pair and kept the one minimising `|p0-q0| + |p1-q1|`.

That selection is not stable under prefix extension. Peer pivots confirm
left-to-right, so a peer swing that becomes knowable *after* an event's own
confirmation bar can score closer than the pair that was chosen at confirmation
time, and take over the match. The event is then re-evaluated against peer
structure from its own future — which changes `peerIndex`, `peerPrice`,
`correlation` and `confidence`, and can flip the `swept`/`held` comparison so the
event ceases to exist at all.

### Concrete trace (seed 52 of the reference harness)

```
prefix=388   peer lows: [351, 360, 374, 380]   pair for primary (377,384) → (374, 380)   |377-374|+|384-380| = 7
prefix=389   peer lows: [351, 360, 374, 380]   pair for primary (377,384) → (374, 380)
prefix=390   peer lows: [360, 374, 380, 386]   pair for primary (377,384) → (380, 386)   |377-380|+|384-386| = 5  ← takeover
```

The divergence confirmed at bar **387**. The peer low at bar **386** is only
confirmed at bar **389** (`386 + swingLookback`). At prefix 390 the engine is
scoring a bar-387 event against a pivot that did not exist until bar 389.

### Measurement

Reference implementation: `smt_prefix_test.py` — a line-for-line port of
`findSwings`, `synchronizedPairs`, the sweep/held comparisons and
`confirmationAligned = max(p1,q1) + swingLookback`. Peer timestamps are made
identical to primary so `align()` is the identity and the alignment layer is
factored out; this isolates the matcher. 200 correlated series × 400 bars,
evaluated on every prefix, `maxSwingSyncBars=4`, `maxSignalAgeBars=24`
(SmtConfig INTRADAY defaults).

| | before | after |
|---|---|---|
| confirmed events observed | 2519 | 2513 |
| **vanished while still inside `maxSignalAgeBars`** | **186 (7.4%)** | **0** |
| peer pair re-matched after confirmation | 0¹ | 0 |
| direction/type flipped after confirmation | 0 | 0 |

¹ zero only because a re-match usually breaks the sweep/held test outright, so
the event lands in the "vanished" column instead. The re-match is the mechanism;
disappearance is the symptom.

Event population moves by −0.24%, so this removes look-ahead rather than
filtering signals away. That distinction matters: a "fix" that suppressed 7% of
signals would be trading correctness for silence.

### The fix

Select the **earliest confirmable** qualifying peer pair (smallest `q1`) instead
of the nearest by total distance. The synchronization tolerance is unchanged, so
matches are still contemporaneous.

Stability argument, which is why this is correct rather than merely better:
detected peer swings only ever *grow* under prefix extension, and a new swing is
always appended after every swing already detected — one can never be inserted
between two existing ones, because a pivot at index `j` is decided the moment bar
`j + lookback` exists. So the minimum-`q1` candidate can never be displaced by a
later arrival. The choice is fixed at the moment the event becomes knowable.

### Regression test added

`app/src/test/java/com/foxtrader/app/domain/usecase/smt/SmtPrefixNonRepaintPropertyTest.kt`

- `confirmed divergences never disappear or mutate as bars are appended` —
  full prefix sweep over 24 pseudo-random correlated series, asserting identity
  *and* payload stability for every event still inside the retention window.
- `peer swing backing an event is knowable by its confirmation bar` — asserts
  `peerIndex + swingLookback <= confirmationIndex`, isolating the look-ahead
  directly rather than via its symptom.

**Not executed** — no Kotlin toolchain here. The property it encodes was measured
on the ported reference implementation; the Kotlin translation is unverified and
is the first thing to run on a machine with Gradle.

### Why the existing tests did not catch this

`SmtPrefixStabilityTest`, `SmtDivergenceDetectorTest` and
`SmtDivergenceDetectorEdgeCaseTest` all build their fixture from the same shape:
exactly two engineered pivots, at indices 30 and 60, on both series, perfectly
synchronized. With a single candidate peer pair the matcher has nothing to choose
between, so both the old and new selection rules return the same answer and the
defective branch is never entered. Those three tests should be unaffected by this
change — verify, don't assume.

This is the more useful finding of the two: the suite had a *shape* gap, not a
coverage gap. Line coverage over `synchronizedPairs` was fine. Every fixture
just happened to be degenerate in the one dimension that mattered.

---

## 3. Audited and found sound (no change made)

Recorded so the next session does not re-litigate these.

- **Confirmed-bar boundary.** `ConfirmedBarPolicy` trims to closed bars before
  any engine runs, including SMT peers and HTF series. Bar timestamps are open
  times and the policy accounts for that. No in-progress candle reaches a signal
  engine.
- **Pivot plateau handling.** Both `SmtDivergenceDetector.findSwings` and
  `LitProStructureDetector.detectSwings` use strict-left / tolerant-right
  comparison, so an equal-high plateau resolves to its first extreme and stays
  put when a later equal print arrives. Consistent across both engines.
- **SMT correlation freezing.** `eventCorrelation` windows on
  `[confirmationIndex - period + 1, confirmationIndex]`, so appending bars cannot
  re-score an old event. One residual edge: when the retained window is shorter
  than `period`, the start is clamped to 0 and the window then *grows* with
  incoming data, which can drift confidence at the very start of a series. Not
  fixed — low impact, and it needs a decision about whether to suppress events
  until a full correlation window exists.
- **LiT May Madness chronology.** `LitSequenceValidator` is a hard gate ahead of
  POI/displacement/R:R, so confluence cannot rescue an impossible ordering.
  Displacement is explicitly frozen to `candles[0..choch.confirmationIndex]`.
- **LiT Adventure R:R.** Deliberately does not use `RiskRewardOptimizer` — that
  builds the target as exactly `minRR × stop`, making the ratio a constant that
  could never gate anything. The in-engine `buildRiskReward` measures real reward
  to the opposite side of the dealing range. The existing comment explaining this
  is correct; leave it alone.
- **Hygiene sweep.** No `GlobalScope`, no `TODO`/`FIXME`/`STUB`/`PLACEHOLDER` in
  `app/src/main`, date formatters cached in `ChartViewport` rather than allocated
  per axis label. Prior audit rounds cleaned this up; there is no low-hanging
  fruit left in these categories.

---

## 4. Open items, ranked

1. **Run the build.** Compile, run `SmtPrefixNonRepaintPropertyTest`, confirm the
   three existing SMT tests still pass. Everything else is downstream of this.
2. **Extend the property harness to LiT Adventure and LiT May Madness.** Both are
   right-edge-only emitters, so the correct test is: drive prefixes, record each
   emitted signal, then assert the accumulated sequence is identical to a single
   full-history replay. That is the prefix test the prompt specifies, and it does
   not exist for either engine.
3. **`LitProStructureDetector.classifyBreaks` sliding window.** The break search
   is anchored to `last - setupLookback + 1`, so as bars arrive an old break can
   fall out of the window and `latest` can change. Published signals are safe
   (right-edge-only emission), but the displayed *stage* and the entry geometry
   anchored to `choch.confirmationIndex` do move. Needs a decision: is stage
   allowed to repaint, or should it be frozen too?
4. **SMT recomputation cost.** `detect()` re-derives the entire retained event
   list on every tick — full `align()` over both series, then `findSwings` over
   the retained window, then an O(primary swings × peer swings) match. Incremental
   state is the obvious answer, but this needs profiling on a device first. I have
   no measurement and will not guess at a number.
5. **Per-system settings surfaces.** The prompt requires independent settings
   models, validation, presets and persistence for each of the three systems.
   `LitXConfig`, `LitConfig` and `SmtConfig` exist with `sanitized()` and presets,
   which covers the model layer. The UI and persistence layers were not audited
   this session.
