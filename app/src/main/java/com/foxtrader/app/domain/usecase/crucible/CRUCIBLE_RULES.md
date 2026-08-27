# Crucible — rule reference

A discovery engine. Where the other engines judge setups someone designed, Crucible **searches** for conditions
under which an outcome is unusually predictable — and then spends nearly all of its effort trying to prove that
whatever it found is an accident.

## Why a search needs this much defence

Searching always succeeds. Try enough conditions on any data, including data containing nothing at all, and one
will look extraordinary. The literature on this is blunt: the probability that a strategy selected as best
in-sample from a grid of N configurations ranks below median out of sample **approaches 1 as N grows, regardless of
whether any configuration has genuine predictive power**.

So the engine is built around four defences, each aimed at a different way this goes wrong.

**Out of sample, purged and embargoed.** Outcomes span a horizon, so an ordinary split lets a training
observation's label be written by the very bars it will be tested on. Every finding is scored only where that
overlap has been removed, plus a buffer after the test fold for observations driven by the same move.

**Effective sample, not raw count.** Neighbouring observations describe the same stretch of market. A hundred
overlapping outcomes are not a hundred facts. Each observation carries its *uniqueness* — the share of its horizon
not shared with any other — and every bound is computed on the independent-equivalent total. In practice 30 000
observations here reduce to roughly 6 800 independent ones, and a bound computed on the larger number would be
overstating the evidence by a factor of four.

**False discovery rate across the whole search.** Testing thousands of rules at 95% each guarantees false findings
in proportion to how many were tried. Benjamini-Hochberg bounds the expected share of published findings that are
spurious. A test in this suite runs 1 000 pure coin flips through it and admits at most two.

**The search itself is measured.** Combinatorially symmetric cross-validation asks how often the in-sample winner
ranks below median out of sample. When that probability exceeds the configured tolerance, **nothing is published at
all** — because the best rule found is then a description of the search rather than of the market.

Crucible does not promise to find anything. It promises that what it reports survived all four, and that when
nothing survives it says so.

---

## 1. The sequence

| Step | What happens |
|---|---|
| 1. Observations | Every eligible bar becomes an observation with quantile-bucketed features |
| 2. Uniqueness | Each observation's independence from its neighbours is computed |
| 3. Rule space | Contiguous bucket bands per feature, combined up to `maxConditions` |
| 4. Folds | Contiguous splits, purged of overlap and embargoed after |
| 5. Scoring | Every rule scored on held-out folds only, pooled |
| 6. Discovery | Benjamini-Hochberg across all rules tested |
| 7. Overfitting | CSCV across every half-split; the run is withheld if it fails |

## 2. Features are bucketed by quantile, never by value

A rule saying "ATR above 0.0012" describes one instrument in one year. A rule saying "in the lowest quarter of its
own recent volatility" describes a market state, and is the only kind that can be checked on data it was not built
from. Bucket edges come from each feature's own distribution, which says nothing about which way price went.

The cut-points are deliberately coarse — three of them, giving four bands. Finer buckets multiply the rule count,
and **every rule tested is paid for twice**: once in the false-discovery correction and once in the overfitting
probability. Resolution is never free here.

## 3. Two questions, searched identically

This is the engine's most useful output, and the reason both targets exist.

- **Direction** — which side of a symmetric barrier price reaches first. Base rate near 50% by construction.
- **Movement** — whether either side is reached at all inside the horizon.

The movement barrier is much wider (2.5 ATR against 1.0) for a reason worth stating: at one ATR over 24 bars price
reaches a barrier about **99%** of the time, so "will it move" is answered before it is asked and no rule can beat a
base rate that high. Widening it until the base rate is genuinely uncertain is what makes the answer worth
measuring.

## 4. Measured behaviour

30 000 synthetic M5 bars, intraday preset, 80% required accuracy:

| Series | Target | Observations (independent) | Base rate | Rules tested | Overfit probability | Survived | Best accuracy | Lift |
|---|---|---|---|---|---|---|---|---|
| Random walk | Direction | 29 917 (6 823) | 50% | 3 528 | **0.69 — withheld** | 0 | — | — |
| Reverting channel | Direction | 29 917 (6 577) | 49% | 3 528 | 0.23 | **0** | — | — |
| Volatility clusters | Direction | 29 915 (7 201) | 50% | 3 528 | 0.39 | **0** | — | — |
| Random walk | Movement | 29 917 (6 823) | 52% | 1 764 | 0.06 | **0** | — | — |
| Volatility clusters | Movement | 29 915 (7 201) | 61% | 1 764 | **0.20 — holds up** | **3** | **81.1%** | **+20.6%** |

Two rows carry the whole result.

**Direction survives nothing, anywhere.** Not on a random walk, not on a channel built to contain a large
exploitable edge, not on clustered volatility. Three engines have now reached this conclusion by three different
routes.

**Movement clears 80%.** On volatility-clustered data, three rules survived every defence at the same 80% bar, the
best measuring **81.1% out of sample against a 60.7% base rate** — a genuine 20-point lift, on 97 independent
observations, with the search's own overfitting probability at 0.20. The winning rule was:

> *position in range in band 3 of 4 **and** volatility regime in the lowest 1 of 4*

Which is compression followed by expansion — volatility clustering, discovered from data rather than assumed, and
made to survive purged validation, multiplicity correction and an overfitting check before being reported.

The control matters as much as the finding: **the identical search on a random walk at the same 80% bar found
nothing**, and on a random walk searching direction the engine detected its own overfitting (0.69) and withheld the
run entirely.

## 5. Movement findings are never drawn as arrows

A movement rule says a move is coming, not which way. Turning it into a directional signal would invent the half
the rule explicitly refused to predict. Movement runs therefore report findings and publish no chart signals; only
direction runs draw arrows, and those use the same symmetric barrier the accuracy was measured against.

## 6. Assumptions

Every number is a default this engine chose. The cut-points, fold count and embargo are **not** exposed in the UI:
all three determine either the size of the search or the strength of the leak protection, and both are quantities
the corrections are computed from. An editable version would let the search be widened until something passed.

| Parameter | Default | Why |
|---|---|---|
| `horizonBars` | 24 | A prediction with no horizon is unfalsifiable |
| `barrierAtrMultiple` | 1.0 | Symmetric, so direction accuracy cannot be bought with geometry |
| `movementBarrierAtrMultiple` | 2.5 | Narrower makes the movement question degenerate at a ~99% base rate |
| `minAccuracy` | 0.80 | The requested threshold, enforced out of sample |
| `minLiftOverBaseRate` | 0.05 | Below this, the figure restates the base rate |
| `minEffectiveSample` | 25 | Independent-equivalent, not raw rows |
| `falseDiscoveryRate` | 0.05 | The share of published findings allowed to be spurious |
| `maxOverfittingProbability` | 0.5 | Above this the search describes itself, not the market |
| `folds` / `embargoBars` | 8 / horizon | Enough splits to measure with; embargo never shorter than the horizon |
| `cutPoints` | 0.25, 0.5, 0.75 | Coarse on purpose — every extra rule is paid for twice |

## 7. Cost

A full search is the heaviest study in the app: thousands of rules across tens of thousands of observations, plus
70 half-splits for the overfitting measurement. Per-block tallies are precomputed so a split costs a sum of eight
numbers rather than a rescan; without that the overfitting check is slow enough that it would in practice be
skipped, and a check nobody runs is not a check.
