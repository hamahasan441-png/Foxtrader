# FOXTRADER v5 — Per-mode accuracy measurement

Step 2 of the v4 ordering: get per-mode accuracy on your own data.

I cannot produce the numbers — that requires running the app against live or
recorded market data, and I have no compiler, no device, and no market feed. What
I can do, and did, is build the pipeline so the numbers come out when you run it.
Everything below is plumbing for a measurement you take, not a measurement I took.

---

## The gap

v2 stamped `MODE_SNIPER` / `MODE_PRECISION` etc. onto every LiT Adventure signal.
v3 added the sample-size gate. Neither was actually wired to a per-mode figure:

- `SignalComputer` dropped the `MODE_*` tag when converting `LitXSignal` to
  `ChartSignal` — the mode survived only inside a prose label.
- `SignalOutcomeEvaluator.summarize` grouped by `SignalSource`. Every LiT
  Adventure mode reports under `SignalSource.LITX`, so all four collapsed into
  one row.

That combined row is worse than no row. It averages a mode designed to fire
constantly with one designed to fire rarely, and reports a number that describes
neither. If SNIPER is excellent and MOMENTUM is poor, the blend looks mediocre
and you would draw the wrong conclusion about both.

---

## What changed

**`ChartSignal.variant`** (new, defaults to null) — machine-readable rule-set
identity within a source.

Deliberately separate from `label`. Label is prose for the history panel and is
free to be reworded; accuracy groups on `variant`. Grouping statistics on a
display string would silently re-partition every stored result the first time
someone changed the wording.

**`SignalComputer`** now carries the mode across, read from the `MODE_*`
confirmation the engine already stamps.

**`SignalOutcomeEvaluator.summarizeByVariant()`** (new) — returns `VariantStats`
per (source, rule set). `Record` gained `variant`, threaded through all four
outcome paths including UNRESOLVED, so an expired signal stays attributable.

Sources with a single rule set are **omitted**, not bucketed under a placeholder:
a source with nothing to compare against has no partition, and an
"(unspecified)" row would imply one that does not exist.

**Tests:** `SignalOutcomeEvaluatorVariantTest` — partitioning, the sample-size
gate after partitioning, the no-placeholder rule, variant survival through the
UNRESOLVED path, deterministic ordering. Every fixture's expected outcome was
traced against a port of the evaluator's SL/TP walk and matches.

---

## What to expect when you run it

**Most modes will show no win rate for a long time.** Partitioning divides an
already small sample four ways, and the gate needs 20 resolved signals per mode.
SNIPER will be last to qualify — it is built to fire rarely.

That is the honest state of the evidence, not a bug to route around. If you find
yourself wanting to lower `MIN_RESOLVED_FOR_RATE` to make the panel look
populated, that is the moment the gate is doing its job.

**Reading the result once it populates:**

- Win rate alone is not the comparison. A mode with 45% wins at 3R beats one with
  70% wins at 1.2R. `averageR` and `profitFactor` are on the same object.
- The evaluator counts a candle touching both SL and TP as a LOSS, because
  intrabar ordering is unknowable from OHLC. Real results should come in at or
  above what it reports, never below.
- These are in-sample figures on whatever history you fed it. Walk-forward
  splitting is Track B's remaining item and is not built.

**Then the v4 question becomes answerable.** If SNIPER underperforms PRECISION on
your data with enough resolved signals behind it, a new sniper engine is
justified and you will know which gate to change. If it holds up, a second engine
is duplicated maintenance.

---

## Run this

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Five sessions of uncompiled Kotlin now. New here:
`SignalOutcomeEvaluatorVariantTest`. Likely trouble spot: `ChartSignal` gained a
field — any exhaustive positional construction in tests or mappers surfaces at
compile time, and all of it is mechanical to fix.
