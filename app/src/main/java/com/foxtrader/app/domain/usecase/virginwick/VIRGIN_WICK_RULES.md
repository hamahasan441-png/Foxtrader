# Virgin Wick — rule reference

An untested-wick reversion model. When price spikes into an area and is rejected, the wick it leaves behind is a
level nobody has since traded against. While it stays untouched it acts as a magnet, and the return to it is the
trade.

## Provenance of these rules — read this first

The source document (`scribd.com/document/998260402`, *Virgin Wick Theory — Trading Bible V3.1*) is
**view-restricted**; its full text was not retrievable. Two things were public and both are used here.

Its own summary:

> The document outlines the Virgin Wick Theory trading strategy, focusing on specific assets like Nasdaq 100 and
> S&P 500 futures. It details the trading sessions, entry methods, and risk management techniques, emphasizing the
> importance of following strict rules and processes.

And the model's published core sequence:

> A bullish A+ trade waits for an H1 candle to close above virgin wick(s), which then become H1 bullish POIs, then
> drops to M1 to look for price to trade under the POI, and waits for a confirmed iFVG before placing a limit order
> on confirmation of the iFVG. The stop loss is placed just on the other side of the iFVG or the entry candle
> (whichever is further/safer), and the take profit is set at an obvious DOL or 2R, with adjustments if the DOL is
> significantly more than 2R away.

**That sequence, the stop rule and the target rule are implemented as stated.** Numeric thresholds the sources do
not give — what size of wick counts, how long a zone stays live, how wide the confirmation window is — are named
parameters and are listed as assumptions in §7. Aligning the engine to the document once its text is available is a
settings change, not a rewrite.

---

## 1. The sequence

| Step | Where | What must be true |
|---|---|---|
| 1. Virgin wicks | Context (1H) | A wick region no later bar has traded back into |
| 2. Activation | Context | The context closes beyond it, confirming the market left it behind |
| 3. Return | Execution (1M) | Price trades back into the zone |
| 4. Confirmation | Execution | An inverted fair value gap in the trade's direction |
| 5. Risk | Execution | Stop behind the safer side; target the next untested wick or a fixed multiple |

## 2. What a virgin wick is

The wick is the region between a bar's body edge and its extreme — **proximal** (body edge, the side price reaches
first) to **distal** (the extreme).

It is virgin while no later bar has traded back into that region. How far in counts is configurable, because the
sources do not say:

| Mode | A wick is spent when price reaches | Effect |
|---|---|---|
| **Touch** (default) | the body edge — any re-entry at all | Strictest; fewest zones survive |
| Half | the wick's midpoint | Middle |
| Full | the wick's extreme | Loosest; most zones survive |

Wicks smaller than a fraction of their own bar's range, or of price, are dropped: a one-tick tail is noise, not
somewhere anyone was rejected.

### Virginity is evaluated *as of* the bar being asked about

This is the load-bearing detail, and it is what makes the model non-repainting at all. Every untested wick will
eventually be traded through — that is the whole point of it being a magnet. If virginity were judged from the end
of the series, every historical arrow would erase itself the moment its own zone was finally worked.

So the question asked is always "was this wick untested **at that bar**", never "is it untested now". A wick tested
next week is still virgin today, and a confirmed arrow stays confirmed. A dedicated test asserts exactly this.

## 3. Activation

A virgin wick becomes a point of interest once the context timeframe **closes beyond it** — above a lower wick,
below an upper one. That close is what confirms the market genuinely left the wick behind rather than still working
around it. A lower wick left behind is demand; an upper wick is supply.

One close is the methodology's own rule. `closesBeyondToActivate` allows more, trading responsiveness for
confidence; requiring more can only ever activate fewer zones, which is asserted by test.

## 4. Confirmation

Price returning to the zone is the **setup**, not the trade. On its own it says only that the zone was reached, not
that it is holding. The inverted fair value gap is what separates the two: a gap that formed in the old direction
and was then traded through and rejected is the market's own admission that the move into the zone has failed.

| Mode | Requires |
|---|---|
| Touch | price entering the zone |
| **iFVG** (default) | an inversion in the trade's direction, inside the window |
| In-zone | the inversion must also overlap the zone itself |

Each admits a subset of the one before, asserted by test. The inversion must also be *fresh*: an old inversion that
happens to sit at this price was some earlier move's rejection, not this return's.

## 5. Risk

**Stop** — the far side of whichever is safer among the inversion, the entry bar, and the wick's own extreme. Taking
the furthest is deliberate: the tighter candidates sit inside the structure that just rejected price, and a stop
placed there is paying for precision the setup has not earned.

**Target** — the nearest untested wick on the far side, which is exactly the kind of unfinished business this model
says price travels toward. When that draw is further than `maxDolRewardMultiple`, aiming at it turns a scalp into a
swing, so the fixed multiple is used instead. A setup that cannot reach `minRewardMultiple` is rejected rather than
taken at a worse price.

## 6. Sessions, and one zone one trade

The source names index futures and specific sessions. `killZonesOnly` restricts entries to the London and New York
opens; off by default, because the ladder is instrument-agnostic and a trader on other markets should not inherit
an index-futures session window silently.

**A zone is traded once.** The wick's entire value is that it was untested; once price has worked it, that is no
longer true and a second trade there is a different setup wearing the same name.

## 7. Which numbers are assumptions

| Parameter | Default | Basis |
|---|---|---|
| Context ladder (1M→1H and its generalisation) | two-step | **1M/1H from the source**; the rest generalised |
| `testMode` | Touch | Assumption |
| `minWickFractionOfRange` / `OfPrice` | 0.20 / 0.00015 | Assumption |
| `maxWickAgeBars` / `maxPoiAgeBars` | 120 / 2000 | Assumption |
| `confirmationWindowBars` | 60 | Assumption |
| `maxIfvgAgeBars` | 20 | Assumption |
| `defaultRewardMultiple` | 2.0 | **From the source (2R)** |
| `maxDolRewardMultiple` | 4.0 | Assumption (the source says "significantly more than 2R") |
| `minRewardMultiple` | 1.5 | Assumption |
| Sequence, stop rule, DOL-or-2R target | — | **From the source** |

## 8. Non-repaint

`analyze()` is a pure function of the closed-bar prefix. The context timeframe is resampled from the execution
series through the shared `MultiTimeframeSeries`, which drops the unfinished trailing bucket and dates every context
bar in execution time — so no zone, and no activation, can be acted on before the bar that created it had closed.

Signal identity is `symbol | timeframe | direction | context bar | wick extreme | entry bar`: structural facts only.
