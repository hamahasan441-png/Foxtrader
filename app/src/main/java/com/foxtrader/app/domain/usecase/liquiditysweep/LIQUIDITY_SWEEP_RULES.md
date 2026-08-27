# Liquidity Sweep — rule reference

A multi-timeframe scalping model: the market collects resting liquidity, traps the traders who chased the break,
and reverses. The engine trades the reversal, not the break.

## Provenance of these rules — read this first

The source document (`scribd.com/document/961716482`) is **view-restricted**; its full text was not retrievable.
What *was* publicly available is its own summary:

> The Liquidity Sweep strategy is a scalping model favored by prop and smart money traders, focusing on entering
> trades after price has collected liquidity and trapped breakout traders. The process involves identifying market
> bias, marking key liquidity levels, waiting for a liquidity sweep, entering on a retest, and managing stop loss
> and take profit effectively. This strategy emphasizes patience and reaction to market manipulation rather than
> chasing breakouts.

That gives the five steps and their order. **Everything below that summary — thresholds, windows, how a sweep is
confirmed, where the stop goes — is derived from the standard published liquidity-sweep methodology, not from the
source document.** Every one of those choices is a named parameter in `LiquiditySweepConfig`, so aligning the engine
to the document once its text is available is a settings change, not a rewrite. The table in §7 lists exactly which
values are assumptions.

---

## 1. The five steps

| Step | Where | What must be true |
|---|---|---|
| 1. Bias | HTF (+ MTF) | Higher-timeframe structure is directional; nothing trades against it |
| 2. Levels | MTF + HTF | Swing extremes, equal-level clusters, optionally the previous HTF range |
| 3. Sweep | Execution | Price trades beyond a marked level **and closes back** within the reclaim window |
| 4. Entry | Execution | Reclaim close, or a retest of the reclaimed level, or a CHOCH then the retest |
| 5. Risk | Execution | Stop beyond the swept extreme; target at opposing liquidity or a fixed multiple |

## 2. Multi-timeframe without a second data feed

The charted series is the **execution** timeframe. The two timeframes above it are **resampled from it** — the only
direction that adds no information the execution bars did not already carry.

This matters more than it looks. Fetching a second series introduces a feed that can disagree with the chart:
different provider, different session boundaries, different gap handling. Resampling cannot disagree, because there
is only one series. Chart, replay and backtest therefore see the same higher timeframe by construction.

Default ladder — two steps up, because one step is too close to carry an independent bias and three leaves levels a
scalp never reaches:

| Execution | Mid | Higher |
|---|---|---|
| 1m | 5m | 15m |
| 5m | 15m | 1H |
| 15m | 1H | 4H |
| 30m | 1H | 4H |
| 1H | 4H | 1D |
| 4H | 1D | 1W |
| 1D | 1W | 1M |

### The no-lookahead contract

`MultiTimeframeSeries` drops the unfinished trailing bucket and records, for every higher bar, the **execution index
at which it closed**. An unfinished higher-timeframe bar's high and low keep moving as the execution series
advances, so treating one as final — or reading a finished one before the execution series reached its close — is a
direct look-ahead. Detectors receive `closedPrefix(executionIndex)` rather than filtering for themselves, so no
detector can reach a bar the execution series had not yet arrived at.

## 3. Step 3 — what makes a sweep a sweep

Both halves are required:

- **Penetration** — the bar trades beyond the level by at least `minSweepPenetrationFraction`. This is the liquidity
  being collected.
- **Reclaim** — a bar closes back on the original side within `maxReclaimBars`. This is the proof it was *collected*
  rather than genuinely broken.

Without the reclaim, a sweep and an accepted break are indistinguishable, and the trap the model exists to trade
never happened. A break that closes beyond the level and *stays* there for a bar before returning is explicitly
rejected: that is a break with a pullback, which is the opposite signal.

## 4. Liquidity is consumed

**A level is swept once.** Once the stops behind it have been run they are gone, and the shelf is no longer a place
liquidity rests. Without this the same level re-arms on every pullback that touches it: in measurement, removing
consumption inflated sweeps from 297 to 4 943 on the same 12 000 bars — a model firing sixteen times as often as the
one described, on setups that no longer exist.

Levels that describe the same shelf are also collapsed before any of this: an MTF swing high, an HTF swing high and
an equal-level cluster routinely land within a tick of each other, and left separate they are three chances for one
sweep to fire without adding a single new place stops actually rest.

## 5. Steps 4 and 5 — entry and risk

Entry modes, strictly ordered — each admits a subset of the one before, which is asserted by test:

| Mode | Waits for | Trade-off |
|---|---|---|
| Reclaim | nothing further | Earliest; takes reclaims that were themselves noise |
| **Retest** (default) | price returns toward the level and holds | Tighter stop; misses entries price never returns for |
| CHOCH + Retest | a change of character first, then the retest | Strongest confirmation; rarest |

Retest depth is measured as a fraction of the sweep leg rather than a fixed distance, so the rule means the same
thing on a two-pip scalp and a fifty-pip swing.

**The stop goes beyond the swept extreme**, because that is the price the market has already proved it rejected.
Anything tighter sits inside the noise the sweep just created. The target is the nearest untouched opposing
liquidity, falling back to a fixed multiple when there is none; a setup that cannot reach `minRiskReward` is
rejected rather than taken at a worse price.

## 6. Non-repaint

`analyze()` is a pure function of the closed-bar prefix: run over candles truncated at bar `t`, it returns exactly
what the full-series run reports for bars at or before `t`. Every event carries the bar it became knowable on — the
level, the bias, the sweep, the entry — and each is published on that bar, never on the bar it started forming.

Signal identity is `symbol | timeframe | direction | level price | sweep bar | entry bar`: structural facts only, so
recalculation noise or a changed target cannot manufacture a second arrow for one confirmed sweep.

## 7. Which numbers are assumptions

These are the values to revisit against the source document. All are settings.

| Parameter | Default | Basis |
|---|---|---|
| Timeframe ladder | two steps up | Assumption |
| `minSweepPenetrationFraction` | 0.00002 | Assumption |
| `maxReclaimBars` | 3 | Assumption |
| `retestDepthFraction` | 0.5 | Assumption |
| `entryWindowBars` | 12 | Assumption |
| `riskReward` / `minRiskReward` | 2.0 / 1.5 | Assumption |
| `maxActiveLevelsPerSide` | 6 | Assumption |
| `levelClusterFraction` | 0.0004 | Assumption |
| `stopBufferFraction` | 0.0001 | Assumption |
| Step order, sweep-and-reclaim, stop behind the extreme | — | **From the source summary** |
