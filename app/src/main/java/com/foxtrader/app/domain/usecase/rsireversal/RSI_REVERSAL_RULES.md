# RSI Orderflow Reversal — rule reference

Implementation of the master specification kept at repository root as
`RSI_ORDERFLOW_REVERSAL_SPEC.md`. Section marks below (§) refer to it.

This file is the authority on **how each rule was resolved in code**, including every place the specification left
room for interpretation. Nothing here restates the specification for its own sake; it records decisions.

---

## 1. The governing rule

> Price makes a new extreme. RSI decides whether that extreme is confirmed continuation or momentum failure. If RSI
> confirms, the reference moves forward. If RSI fails to confirm, the setup arms. Only then do we drop to the lower
> timeframe.

Every decision below follows from that one sentence. Where the specification was silent, the resolution chosen was
always the one that applies this rule again rather than introducing a new mechanism.

## 2. RSI Orderflow candles (§3)

Four independent Wilder RSI series, over the bar opens, highs, lows and closes:

```
RSI_Open  = RSI(open)      RSI_High = max(all four)
RSI_Close = RSI(close)     RSI_Low  = min(all four)
```

**Why the extremes come from all four series rather than from RSI(high)/RSI(low).** RSI is not monotonic in its
input, so `RSI(high)` is not reliably the largest of the four values. Assigning it to the candle high directly
produces candles whose body escapes its own wicks on real data. Taking the extremes across all four keeps the candle
well-formed by construction, and the component test asserts it.

Smoothing and Heikin-Ashi are presentation modes only. Structure, divergence and entry logic always read the raw RSI
OHLC (§3.3).

## 3. Structure (§5, §6)

One pivot engine serves both price and RSI. Pivot strength defaults to 2/2 both sides.

- A pivot is emitted only once all right-hand bars exist, and carries `confirmedIndex = index + right`.
- No state transition may consume a pivot before its `confirmedIndex`.
- **Plateaus.** The left comparison is loose (`>` fails the candidate) and the right is strict (`>=` fails it). An
  equal-level plateau therefore resolves to exactly one pivot, its last bar. Strict on both sides would drop plateau
  pivots entirely; loose on both sides would emit one per plateau bar, and "is this a new extreme" would then depend
  on which of them happened to be picked.

## 4. The master pattern (§7–§11)

```
P1   running extreme reference (a confirmed swing low for BUY)
P2   price beyond P1, RSI failing to confirm          -> divergence
P3   RSI closes beyond the protected RSI swing        -> structure break
P4   price beyond P2 (a wick suffices)                -> decide
       RSI failed to confirm  -> ARMED
       RSI confirmed          -> reference = P4, wait for P5, P6, ... without limit
```

SELL is generated from the same code with the comparisons mirrored (§12). There is no second implementation, and the
mirror test feeds a price-inverted, RSI-inverted series and asserts identical indices and recursion depth.

### 4.1 Resolved: what P4 *is*

The specification says price must "break P2" and that a wick is enough, then compares `RSI_Low(P4)` against
`RSI_Low(P2)` — a comparison that needs P4 to be a *point*, not a moment.

**Resolution: P4 is the confirmed swing low that undercuts P2**, not the bar that first traded through it. A pivot
low's value *is* its wick low, so "a wick is enough" is honoured exactly: a liquidity sweep — a spike through the
level that reverses — is a textbook pivot and qualifies. Evaluating on the first bar to trade through instead would
measure RSI at an arbitrary bar mid-leg, which is not the extreme the comparison is about, and would leave the
recursion in §11 (`PriceLow(P5) < PriceLow(P4)`) without a defined P4 low.

Cost: the arming decision lags by `pricePivotRight` bars. That is the same latency the rest of the system already
accepts as the price of never repainting.

### 4.2 Resolved: price extends beyond P2 before P3 (unspecified case)

If price makes an even lower low while still waiting for the RSI structure break, the governing rule is applied
again: if RSI still fails to confirm, P2 moves to the deeper extreme; if RSI confirms it, the whole pattern restarts
with that extreme as the new P1.

### 4.3 Resolved: a higher low does not move the reference

While waiting in `FOUND_P1`, a confirmed swing low *above* the reference is ignored. P1 is the running extreme, and
letting it drift upward would make a later break of the *higher* low count as "price made a new low" when it did not.

### 4.4 After arming

The pattern restarts with the final extreme as the new P1, so a fresh sequence can build from where the last one
ended rather than waiting for structure to be rediscovered from nothing.

## 5. Configured ambiguities (§48.4)

Both are named flags with tests covering **both** interpretations.

| Flag | Question | Default | Rationale |
|---|---|---|---|
| `equalRsiCountsAsFailure` | Is `RSI(P4) == RSI(P2)` within epsilon a failure to confirm? | `true` | §7.2 writes `>=`; `>` is only called the "preferred strong form" |
| `protectedRsiMode` | Which RSI swing must P3 break — `HIGHEST` in the P1..P2 leg, or `MOST_RECENT` before P2? | `HIGHEST` | The extreme of the leg is the level the divergence actually left behind |

**Fallback**: when no RSI swing confirmed between P1 and P2, the most recent RSI swing before P2 is used. Without it
a setup whose price extremes sit close together could never define P3 and would always expire.

## 6. Break semantics (§24)

| Event | Default | Configurable |
|---|---|---|
| RSI structure break (P3) | `CLOSE_BREAK` | yes |
| Final price extreme (P4, P5, …) | `WICK_BREAK` | yes |
| LTF CHOCH / BOS | `CLOSE_BREAK` | yes |

## 7. Lower-timeframe confirmation (§16–§18)

Runs only while a setup is armed, and only over bars whose timestamp is at or after the armed bar (§28). Presets:

| Mode | Requires |
|---|---|
| Aggressive | sweep + CHOCH |
| **Balanced (default)** | sweep + CHOCH + displacement |
| Strict | sweep + CHOCH + BOS + held retest |

- **A sweep must reclaim.** The bar trades through a prior confirmed swing extreme *and closes back on the original
  side of it*. Without the reclaim every continuation bar would qualify and the stop-hunt premise of §9 is lost.
- **Displacement is measured strictly after the sweep bar**, against the average body of the
  `displacementLookback` bars leading into the sweep. Letting the sweep bar double as its own displacement would
  collapse Balanced back into Aggressive; measuring against the window start instead of the sweep makes
  "impulsive" mean nothing when the window opens in a quiet stretch.
- When there is not enough history before the sweep to establish an average body, Balanced does not confirm. That is
  reported as no signal, never as a signal on an unmeasured premise.

## 8. Risk (§19, §20)

Stop behind the final swept extreme, plus an optional buffer. Target is a fixed multiple of that risk, default 4R.
Nothing adjusts the multiple based on structure: §20 is explicit that opposing-liquidity awareness must not silently
alter the fixed mode, so a different target is a different configured multiple, never a hidden override.

## 9. Non-repaint (§32)

The load-bearing property: **`analyze()` is a pure function of the closed-bar prefix.** Running it over candles
truncated at bar `t` returns exactly what the full-series run reports for bars at or before `t`. Chart, replay and
backtest therefore share one core, and parity is structural rather than something tested for and hoped.

### 9.1 The Wilder-seed hazard, and why the warmup exists

Wilder RSI is recursive and seeded from the first bar of whatever series it is handed. **This app prepends older
history at runtime** (`ChartDataController.loadOlderHistory` / `preloadHistoryBackTo`), so a scroll-back re-seeds the
recursion and perturbs every historical RSI value slightly. At an exact `RSI(P4) ≈ RSI(P2)` boundary that could flip
a decision and repaint an arrow that had already been published.

Three layers answer it:

1. **Warmup exclusion** — no setup is published before `warmupBars = max(rsiLength × 10, 200)`. After 200 bars the
   seed's influence is on the order of 1e-5 RSI points, four orders of magnitude below the epsilon.
2. **Epsilon comparisons** — RSI is never compared with `==`, and price comparisons are relative to the price.
3. **A prepend-invariance test** — the engine runs over a series with and without 400 bars of prepended history, and
   every setup in the overlapping region must be identical.

`warmupBarsOverride` exists so component tests can work on short synthetic series. Production leaves it null.

## 10. Duplicate protection (§30)

A setup's identity is `symbol | timeframe | direction | P1 index | P2 index | final extreme index` — structural
indices only. Entry geometry, confidence and recalculation noise cannot manufacture a second arrow for one
objectively confirmed setup.

## 11. Known limitation

`Timeframe` has no `M3`, so the specification's `3m → 1m` mapping row (§15) is absent from the default map. Adding it
reaches data providers, Room entities and stored preferences and is deliberately left to its own change rather than
approximated with a nearby timeframe.
