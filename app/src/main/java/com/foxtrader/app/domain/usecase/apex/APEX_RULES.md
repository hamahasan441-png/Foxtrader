# Apex — rule reference

A consensus engine behind a measured precision gate. Apex adds no new way of reading price. It runs the
methodologies FoxTrader already implements, takes a trade only where several of them independently arrive at the
same one, and then holds itself to a required hit rate that it **measures on its own resolved trades** rather than
asserting.

## What this engine promises — read this first

This engine was built in response to a request for a signal success rate above 80%. That number is worth being
precise about, because there are two very different claims one could attach to it.

**What Apex does not promise:** that its published signals will win 80% of the time. No method can promise that
about the future. Any indicator that displays a fixed win-rate figure on its panel is showing you a number it made
up or measured on data it already knew the answer to.

**What Apex does guarantee, and what is checkable:** *while the engine's own recent measured record is below the
threshold you configured, it publishes nothing.* The threshold is enforced backwards, against outcomes that have
already happened, never forwards as a prediction.

The practical consequence is worth stating bluntly: **a quiet chart is this engine working, not failing.** On
30 000 bars of structureless synthetic data it publishes zero signals at every threshold from 50% to 95% — because
its measured record there is 21.9% and no threshold can be earned by it. That silence is the entire product.

A second point the engine refuses to let you forget: **win rate alone decides nothing.** Nine wins in ten at a
tenth of the risk is a 90% strategy that loses money. Expectancy in R travels with the hit rate everywhere it is
reported, and a threshold on the rate alone would happily produce exactly that outcome.

---

## 1. The sequence

| Step | What must be true |
|---|---|
| 1. Votes | Member methodologies analyse the series under their own unmodified rules |
| 2. Agreement | `minAgreeingMembers` distinct members reach the same direction within `agreementWindowBars` |
| 3. Stamp | The candidate is dated on the bar the agreement **completed** — never moved by later votes |
| 4. Geometry | Stop = the widest member's; target = the nearest member's (or a fixed R multiple) |
| 5. Resolution | The trade is walked forward to its stop, its target, or `maxHoldBars` |
| 6. The gate | It is published only if the record of trades resolved **before its own bar** meets the threshold |

## 2. The members

| Member | Reads |
|---|---|
| Liquidity Sweep | HTF bias, a marked level swept and reclaimed |
| Virgin Wick | An untested HTF wick, revisited and confirmed by an iFVG |
| RSI Orderflow | Momentum with an orderflow proxy |
| Pivot Sweep Divergence | Prior-day pivot sweep plus dual divergence |
| Value Area Rejection | Prior-session value-area sweep and rejection |
| AMD | Accumulation → manipulation → distribution |

Members keep their own configs and defaults. Apex deliberately does not retune them: **a member adjusted to agree
more often stops being independent evidence**, and independence is the only reason agreement between them is worth
more than any one of them alone. A member that throws is dropped and costs only its own vote.

For the same reason, an Apex signal never reinforces its members' confidence anywhere in the app, and is never
reinforced by them — it is built out of them, so counting both would count one piece of evidence twice.

## 3. The agreement rule, and why it is what it is

As soon as the required number of distinct members have voted, the cluster closes and is stamped on that bar.
Votes arriving afterwards — even from new members, even still inside the window — begin the next cluster instead of
joining this one.

The natural-sounding alternative, taking every vote inside the window, **repaints**. A third member confirming two
bars later would move a marker that had already been drawn, because the cluster would then be stamped on the newer
vote. A signal that moves after the fact cannot be traded and cannot be honestly backtested. Later agreement is
treated as what it is: confirmation that arrived too late to have been part of the decision.

One vote per member per cluster — a methodology firing twice inside the window is one opinion repeated, not two
that agree.

## 4. The precision gate

The record consulted at bar *t* contains **only trades that had already resolved before *t***. A trade resolving on
bar *t* itself is not yet evidence when bar *t* is being decided. Measured over the whole series instead, the
number would include the trade being judged and every trade after it — that is not a filter, it is hindsight, and
it would look spectacular and be worthless live.

The gate reads the **Wilson score lower bound at 95% confidence**, not the raw rate. Four wins from five is "80%"
and means nothing; its lower bound is about 38%, which is the honest reading. Without the bound, a short lucky run
unlocks a threshold the record cannot support. `minResolvedSample` (default 30) refuses that case sooner and more
clearly. The Wilson interval is used rather than the textbook normal approximation because the latter misbehaves
exactly where this gate lives — small samples and proportions near 1, where it can even produce bounds above 1.

Before enough trades have resolved to measure anything, the default is to **withhold**: publishing under a
hit-rate threshold that has not yet been measured is precisely the claim this engine exists to avoid making.

## 5. Resolution is pessimistic on purpose

When a single bar contains both the stop and the target, the outcome is recorded as a **loss**. Bar data cannot say
which came first, and assuming the good one is how a backtest quietly flatters itself. Every candidate is tracked
whether or not it was published: withholding a signal does not make the trade it would have taken disappear from
the evidence.

## 6. Non-repainting

`analyze()` is a pure function of the closed-bar prefix. Analysing any prefix yields exactly the signals the
completed history reports inside that prefix — same bars, same members, **no slack** — because a cluster's stamp
cannot be moved by later votes and the gate reads only already-resolved trades. This is asserted directly in
`ApexEngineTest`, and it is what makes the single-pass `backtestFunction` legitimate: it is equal to bar-by-bar
replay, not an approximation of it.

## 7. Presets

| | Members | Agree within | Max hold | Reward |
|---|---|---|---|---|
| Scalping | 4 fast | 18 bars | 60 bars | 1.0R |
| Intraday | all 6 | 36 bars | 240 bars | 1.5R |
| Swing | all 6, k=3 | 72 bars | 720 bars | 2.0R |

Scalping's reward multiple is deliberately the lowest. **A high hit rate and a large target are not independent
choices** — asking for both is asking the market for something it does not offer, and the honest way to reach for a
high hit rate is to accept a smaller target in exchange for it.

## 8. Measured behaviour

On synthetic series, 30 000 M5 bars, default presets. These are the engine's own numbers, reproduced by the tests:

| Series | Candidates | Resolved | Hit | 95% lower bound | Expectancy | Published |
|---|---|---|---|---|---|---|
| Random walk (no edge), intraday | 75 | 73 | 21.9% | 14.0% | −0.47R | **0** |
| Reverting channel (real edge), intraday | 24 | 21 | 85.7% | 65.4% | +0.91R | **0** |
| Reverting, threshold 50% / sample 8 | 24 | 21 | 85.7% | 65.4% | +0.91R | 14 |

Read the second row carefully, because it is the most informative one. The method measured 85.7% with positive
expectancy — and the engine still published **nothing**, because 21 resolved trades cannot support an 80% claim at
95% confidence. That is the gate refusing to let a good-looking short record speak for itself.

Two consequences a trader should expect:

- **Consensus is rare.** Roughly one candidate per 1 250 bars on intraday. Scalping is rarer still (its tighter
  window and smaller member set produced a single candidate in 30 000 bars on this fixture), and swing rarer again.
- **The default gate needs a great deal of history before it can open at all** — 30 resolved trades at that rate
  means tens of thousands of bars. On a short chart, Apex being silent is the expected state, not a fault.

Synthetic series are a test of the mechanism, not evidence about markets. What they establish is that the gate
refuses noise and that the measurement is walk-forward; what a real instrument measures is for the instrument to
say.

## 9. Assumptions

Every number below is a default this engine chose, not a rule from a published methodology. All are exposed in the
study settings.

| Parameter | Default | Why |
|---|---|---|
| `minAgreeingMembers` | 2 | Enough for independence to mean something without making signals impossible |
| `agreementWindowBars` | 36 | Members confirm at different points of one move; the window must span that dispersion |
| `minHitRate` | 0.80 | The requested threshold, enforced by measurement |
| `minResolvedSample` | 30 | Below this an 80% figure is a coin flip wearing a percentage sign |
| `precisionWindow` | 60 | Recent enough to track regime, long enough to mean something |
| `useConfidenceBound` | true | The difference between a measured claim and a flattering one |
| `minRewardMultiple` | 0.8 | A floor, so the hit rate is not bought with an arbitrarily small target |
