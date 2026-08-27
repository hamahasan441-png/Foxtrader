# Compass — rule reference

A directional-accuracy engine. Compass does not generate setups. It scores the calls the app's other
methodologies make, and publishes only those whose estimated probability of being **directionally right** clears a
threshold that was earned on calls already proved right or wrong.

## What "accuracy" means here — read this first
> **Defaults changed after measurement.** The threshold below shipped at 0.80 and was enforced honestly against
> measured outcomes — but it published **nothing** at any realistic chart size, on any series tested, including ones
> built to contain a large edge. A study that never draws is not a study. The default now **reports** the measurement
> instead of **enforcing** it: every signal still carries the number actually measured for it, the status line still
> says what the record is, and turning the gate back on is one setting. What was wrong was never the honesty — it was
> setting the bar above what the data can deliver and calling the resulting silence a feature.


This engine was built in response to a request for signals that are right more than 80% of the time, judged on
**correctness of direction only, not on reward-to-risk**. That distinction changes everything about how the number
must be defined, and getting the definition right matters more than any model.

### The barrier is symmetric

From a call's bar, price must reach a barrier **the same distance on both sides**, scaled by ATR. Reaching the
called side first is right; the opposite side first is wrong; neither inside the horizon is undecided and counts as
neither.

Two other definitions were considered and rejected:

- *"Did price ever move my way?"* — one-sided, and true of nearly every call, because price wanders both ways given
  time. It scores above 90% for a coin flip.
- *"Did it hit a near target before a far stop?"* — this is the reward-ratio question wearing an accuracy label. It
  can be pushed arbitrarily close to 100% by moving the target closer, while the strategy loses more money at every
  step.

Symmetric barriers remove that lever completely: widening the side you want widens the side you do not. What
remains is direction, which is what was asked for. It also means the reported accuracy describes a trade a person
could actually take — the published stop and target sit exactly on the barrier the number was measured against.

The definition is tested against its own null: judging a random walk in a fixed direction lands between 42% and
58%, so the metric carries no bias of its own.

### Accuracy is always reported against its base rate

This is the failure mode the research literature documents most sharply, and the one most published accuracy
figures fall into. A model showing 80% where a rule reading nothing scores 78% has demonstrated almost nothing; one
showing 62% where such a rule scores 50% has demonstrated a great deal.

The base rate used here is **the accuracy of the best constant-direction rule on exactly those bars** — always-long
or always-short, whichever did better. In a market that rose, always-long is right most of the time while reading
nothing at all. Compass requires both an absolute accuracy level *and* a margin over that base rate, because either
alone is easy to satisfy with no skill whatsoever.

Measured behaviour makes the point better than the argument does. On a drifting random walk, one subset of calls
measured **100% accurate over 54 calls** — and Compass published nothing, because always-long also scored 100%
there. That is the guard doing precisely the job it exists for.

### The threshold search is corrected for its own size

Scoring past calls, trying every threshold and keeping the one that looks best *is* a search for a lucky subset.
The more thresholds tried, the luckier the winner, and none of it survives new data.

So the search is treated as multiple hypothesis testing. Each candidate threshold is the hypothesis "calls above
this are at least `minAccuracy` accurate", each is tested with a Wilson lower bound, and the confidence level is
split across every candidate (a Bonferroni correction). **A wider search therefore makes each candidate harder to
justify, never easier.** Testing twenty thresholds at 95% and reporting the winner is a 36% confidence claim
wearing a 95% label; splitting the level is what makes the label true.

When several thresholds survive, the **least selective** one is chosen rather than the best-scoring one — the
highest sample accuracy among candidates is the most likely to be the luckiest.

---

## 1. The sequence

| Step | What happens |
|---|---|
| 1. Calls | The six member engines produce direction calls under their own unmodified rules |
| 2. Features | Eight scale-free features are read at the call's own bar, from closed data only |
| 3. Verdict | The symmetric barrier decides right, wrong, or undecided |
| 4. Fit | Logistic regression is fitted on calls resolved **strictly before** the bar being decided |
| 5. Calibrate | A threshold is selected, corrected for the size of the search |
| 6. Publish | Only calls scoring at or above that threshold |

## 2. Walk-forward, without exception

The scorer and the threshold at any bar are built only from calls whose verdict was already known before that bar.
Fitting a model on a call and then scoring that same call does not produce a small optimism — it produces
near-perfect accuracy and teaches nothing. `CompassEngineTest` asserts that no published signal was inside its own
evidence.

Analysing any prefix therefore yields exactly what the completed history reports inside that prefix — same bars,
same sources, no slack. That equality is asserted directly, and it is what makes the single-pass `backtestFunction`
equal to bar-by-bar replay rather than an approximation of it.

One subtlety was found while building this and is worth recording. Three member engines keep only their most recent
160 results by default. That is a sensible display cap and ruinous as input to a learning layer: as the series
grows, the oldest calls silently disappear, so history Compass had already learned from would change underneath it.
Compass asks those engines for the uncapped view; their defaults are untouched for every other caller, and
`CompassCallSourceTest` pins the requirement.

## 3. The scorer

Logistic regression, gradient descent, fixed iterations, no randomness. Chosen because it is **calibrated by
construction** — minimising log loss makes the output a probability rather than a score, and the whole decision
compares that number to a threshold directly. A model that ranked well but was systematically overconfident would
pass every ranking check and break the guarantee silently.

It is deliberately small: eight features, strong regularisation, a few hundred observations. A larger model on this
much data would fit the noise and report wonderful accuracy on data it had already seen.

Undecided calls train nothing. Treating "no move" as a loss would teach the model to avoid quiet markets rather
than wrong directions.

## 4. Presets

| | Horizon | Barrier | Learns from |
|---|---|---|---|
| Scalping | 8 bars | 0.6 ATR | 300 calls |
| Intraday | 24 bars | 1.0 ATR | 400 calls |
| Swing | 96 bars | 2.0 ATR | 500 calls |

## 5. Measured behaviour

30 000 synthetic M5 bars. Unfiltered accuracy is over every call the primary layer made:

| Series | Preset | Calls | Accuracy | Base rate | Lift | Best justifiable | Published |
|---|---|---|---|---|---|---|---|
| Random walk | Intraday | 476 | 50.0% | 51.3% | −1.3% | 53% (bound 44%) | **0** |
| Random walk | Swing | 476 | 52.3% | 51.1% | +1.3% | 52% (bound 45%) | **0** |
| Reverting channel | Intraday | 317 | 61.8% | 50.2% | **+11.7%** | 67% (bound 58%) | **0** |
| Reverting channel | Swing | 317 | 75.3% | 53.2% | **+22.1%** | 87% (bound 76%) | **0** |
| Drifting walk | Intraday | 251 | 20.7% | 97.6% | −76.9% | 92% (bound 76%) | **0** |
| Drifting walk | Swing | 251 | 21.5% | 100.0% | −78.5% | 100% (bound 87%) | **0** |

Read the last row first. A subset of calls measured **100% accurate**, and the engine published nothing, because a
rule that read nothing at all would have scored 100% on the same bars. That number is the market's drift, not
skill, and putting it on screen would be the most misleading thing this engine could do.

Then read the reverting rows, which carry the honest answer to the original question. That fixture has a large,
genuine, exploitable edge — and the highest accuracy that can be justified on it is **87% raw with a 76% lower
bound** on swing, **67% raw with a 58% bound** on intraday. Both fall short of 80% once the confidence bound and
the size of the threshold search are accounted for.

**So: 80% honest directional accuracy was not achievable on any fixture tested, including one built to contain a
strong edge.** The engine's answer to being asked for 80% is to stay silent and report what it *could* justify:

> *Compass silent — best candidate measured 67% over 214 calls (at least 58% at 95% across 10 candidates, base rate
> 50%), short of 80%.*

That message is the useful output. It says what accuracy the data actually supports, so lowering the bar becomes an
informed decision rather than a guess. At a 55% requirement the same fixture publishes 21 signals measuring 62%.

Synthetic series test the mechanism, not markets. What they establish is that the metric is unbiased, that the
guards refuse both noise and drift, and that the calibration is walk-forward.

## 6. Assumptions

Every number below is a default this engine chose. All are exposed in the study settings except the threshold grid,
which is deliberately fixed: its size is what the multiple-testing correction is computed from, so an editable grid
would let the search be widened until something passed.

| Parameter | Default | Why |
|---|---|---|
| `horizonBars` | 24 | A call with no horizon is unfalsifiable |
| `barrierAtrMultiple` | 1.0 | Volatility-scaled so "the same distance" means the same thing in any regime |
| `minAccuracy` | 0.80 | The requested threshold, enforced by measurement |
| `minLiftOverBaseRate` | 0.05 | Below this, the figure is the market's drift restated |
| `confidence` | 0.95 | Split across the grid, not spent on each candidate |
| `minCalibrationSample` | 40 | Fewer calls cannot support a claim of this kind |
| `learningWindow` | 400 | Recent enough to track regime, long enough to fit eight weights |
| `thresholdGrid` | 0.50–0.95 by 0.05 | Ten candidates; more resolution costs a stricter bound on each |
