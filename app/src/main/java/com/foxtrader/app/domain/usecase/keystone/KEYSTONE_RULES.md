# Keystone — Liquidity Sweep · SMT Divergence · Displacement Retracement

Keystone trades one sequence and refuses everything else.

The name is the stone at the top of an arch. Remove any one of the three parts
and the structure does not weaken, it falls. A sweep without a divergence is an
ordinary reversal attempt. A divergence without a sweep is two markets
disagreeing about nothing in particular. Displacement without either is a large
candle. Together they describe one specific event: liquidity taken by one market
that the market beside it did not need to take, followed by an impulse that
breaks the structure the trap was built from.

## What it optimises

**Expectancy and drawdown. Not the win rate.**

This runs through every decision in the engine and it is worth being blunt about
why. A rule that risks one to make two is *wrong more than half the time when it
is working correctly*. Tuning such a rule toward being right more often means
shrinking the target, and shrinking the target is how a profitable model becomes
a flattering one.

`KeystoneValidation.accept` therefore computes the win rate, prints it, and gives
it no vote. The acceptance summary says so out loud: every verdict ends with
`(win rate NN%, not a criterion)`.

## The sequence

| Step | Rule | Where |
|---|---|---|
| 1 | Bias — both timeframes above the execution series agree, and the session is travelling the same way | `KeystoneBias` |
| 2 | Location — the move took a known pool: previous day, Asian range, or a confirmed major swing | `KeystoneLiquidity` |
| 3 | SMT — a correlated market failed to confirm the sweep | `KeystoneSmt` |
| 4 | Confirmation — a **closed** candle displaces and breaks internal structure | `KeystoneTrigger.displacementAt` |
| 5 | Entry — first retracement into the displacement's fair value gap, else the 50–62% band | `KeystoneTrigger.arm` |
| 6 | Stop — beyond the swept extreme plus an ATR buffer | `KeystoneTrigger.arm` |
| 7 | Exit — opposing liquidity when far enough away, else a fixed multiple; breakeven only after a **close** confirms continuation | `KeystoneTrigger.target`, `KeystoneValidation` |
| 8 | Filters — session, spread, news, volatility, one signal per liquidity event | `KeystoneFilters` |
| 9 | Risk — a fixed fraction per trade, hard stop after two losing trades in a day | `KeystoneEngine.refuse` |
| 10 | Validation — costs, purged out-of-sample, walk-forward, bootstrap, PBO, deflated Sharpe | `KeystoneValidation` |
| 11 | Acceptance — expectancy, profit factor, drawdown, sample, stability | `KeystoneValidation.accept` |

## Non-repainting, as a property rather than a claim

`KeystoneEngine.analyze` is a pure function of the closed-bar prefix: run it over
candles truncated at bar `t` and it returns exactly what the full-series run
reports for bars at or before `t`. `KeystoneNonRepaintTest` is that sentence
written as an assertion rather than a proxy for it.

Everything that could break the property is handled explicitly:

- higher-timeframe bars are read through `MultiTimeframeSeries`, which drops the
  unfinished trailing bucket and records the execution bar each higher bar closed
  on;
- liquidity pools carry the bar they became knowable — the previous day's high is
  not a level until the day has ended;
- the fair value gap is attached **one bar after** the impulse, because its third
  candle has not closed on the impulse bar itself;
- a divergence is stamped at the later of its two legs, and is scored against a
  **rolling** average range rather than the whole series, so a confirmed event's
  number cannot drift as new bars arrive;
- a trade whose hold window runs past the end of the data is `OPEN`, never
  `EXPIRED`, so the daily-loss ledger cannot be changed by the series merely
  growing.

The one thing that is *not* a pure function of the prefix is the validation
report, which is a summary of the whole run by construction. It is returned
separately from the signals for exactly that reason, and `enforceAcceptance` —
which would let that whole-run summary gate publication — is **off** by default.

## Two honest limitations

**No spread feed.** The spread test runs against an assumed spread the trader
configures, measured as a share of the trade's own risk rather than as an
absolute number: a fixed spread is negligible on a 40-pip stop and ruinous on a
3-pip one.

**No economic calendar.** The news blackout covers recurring release windows
(12:30, 14:00, 18:00, 19:00 UTC ± 30 minutes) rather than actual events. It will
stand a trade down on a quiet day at 12:30, and it will not know about an
unscheduled announcement at 09:15. Both are coarser than the real thing, and
neither silently passes everything.

## What the numbers mean

- **Expectancy (R)** — mean result per trade after costs. The number that decides.
- **Profit factor** — gross profit over gross loss. Must clear 1.3.
- **Drawdown (R)** — judged on the **bootstrap 95th percentile**, not on the one
  ordering this particular series happened to produce.
- **PBO** — probability of backtest overfitting, by combinatorially symmetric
  cross-validation over a grid of exit rules (four reward floors × breakeven on
  or off). It is a property of the *selection procedure*, not of any single
  configuration: a high value means picking an exit by past performance would not
  have worked, so the exit in use should be justified by its reasoning rather than
  by its backtest rank.
- **Deflated Sharpe** — the Sharpe ratio corrected for the skew and fat tails a
  capped-reward rule produces by construction, and for the fact that the best of
  several configurations was reported. Beating zero proves nothing; this is the
  bar that has to be cleared instead.

## Measured against the market, not against a fixture

Everything below is EURUSD from Dukascopy — the same feed the app charts —
against GBPUSD as the correlated peer: 49 322 fifteen-minute bars from August
2024 to July 2026, and 24 397 hourly bars from September 2022.

Running the engine on it found five defects that no synthetic series had
exposed, every one of them a rule that read correctly and measured something
else:

| Defect | What it actually did |
|---|---|
| Displacement size compared a **body** to ATR | ATR is an average *range*. The median body is 0.35 ATR against a median range of 0.86, so `body ≥ 1.2 ATR` demanded a 93rd-percentile candle while reading as though it asked for a large one. Now measured on the range. |
| Consumed shelves **never expired** | Each taken shelf blocks a price band forever. After five hundred sweeps those bands covered close to half the pair's whole range, and the engine spent the back half of the series blind because of trades it had considered in the first half. Fixing it quadrupled the sweeps found, 522 → 2 235. |
| Duplicate orders on one bar | One move through a shelf takes the previous day's low, the Asian low and a swing low within pips of each other. Each armed its own setup and all filled together: three orders, near-identical entries and stops, one idea sized three times. |
| SMT paired **swing pivots** rather than reading the sweep | It required both markets to form a detectable pivot within a few bars of each other and found divergences wherever two pivots happened to line up. Of 769 divergences it reported, only about one in five fell near a sweep. It now asks the question directly at the sweep bar: *the primary just made a new extreme for this window — did the peer?* |
| Internal structure reached back **before** the sweep | That required the impulse to erase the entire approach *into* the trap rather than the structure built during it. It is now measured from the sweep forward. |

## The frequency, and why it is not negotiable

On 20 000 fifteen-minute EURUSD bars the intraday preset publishes **three**
signals. That is roughly one trade per symbol per eight months, and no filter is
responsible for it — removing any single one yields nought to two more. The
scarcity is the conjunction the specification asks for.

The divergence requirement is worth stating separately, because it is the one
that looks most like an obstacle and is in fact the edge:

| | signals | expectancy | profit factor |
|---|---|---|---|
| SMT required (default) | 3 | **+0.294R** | **1.82** |
| SMT dropped | 20 | −0.332R | 0.50 |

Seven times the signals, and the model stops making money. Anyone tempted to
turn `requireSmt` off to get more arrows on the chart is turning a
positive-expectancy rule into a negative one, and this table is here so that
choice is made with the number in front of it.

The practical consequence is that Keystone is a **scanner**, not a chart
indicator: its natural use is many symbols at once, through the Backtesting Lab
or across a watchlist, rather than waiting for one pair to produce its next
sequence.

## What was measured on synthetic data, including the unflattering part

On 5 000 bars of a synthetic trending series with a correlated peer, the
intraday preset found 235 sweeps and published **nothing**:

| Stage | Lost |
|---|---|
| Sweep opposed the higher-timeframe bias | 109 |
| No confirmed bias to read | 41 |
| Session had already travelled hard against it | 18 |
| No closed displacement broke structure | 45 |
| Price never returned to the entry | 11 |
| Reached entry, no divergence to support it | 9 |
| Reached entry, outside the permitted sessions | 2 |

The swing preset published four. On a series that actually contains the
sequence, the same defaults publish at roughly 26 signals per 5 000 bars.

Two things are worth being clear about. **This is selectivity, not silence** —
the engine names the step it stood down on every time, and
`KeystoneAnalysis.note` reports the dominant one. And **it is not evidence about
real markets**: a generated walk has no reason to produce a sweep, a divergence,
a displacement and a retracement in that order, so the low yield says more about
the fixture than about the model. What the measurement does establish is that no
stage is silently refusing everything for a reason unrelated to the market, which
is the failure this engine was built after.

Two defaults were loosened once measured, and both were wrong rather than
merely strict:

- **Session alignment** originally demanded that the session already be
  travelling *with* the setup. That fights the model — a sweep of a low happens
  during a pullback, and a pullback is a stretch where the session is going the
  other way. It now refuses only the narrower thing the rule is for: buying into
  a day that has spent itself selling.
- **Internal structure** was read back across the whole sweep-to-displacement
  window, which quietly turned "break the minor high" into "clear a twenty-bar
  breakout". It is now the handful of bars the trap was built from.

## A note on the timeframes

The specification names 1H and 15m. On a 1–5 minute execution chart the ladder
gives exactly that. On an M15 chart it gives H4/H1 — one step higher than the
specification's letter, because the alternative is to demand a bias from a
timeframe at or below the one being traded, which carries no independent
information. The rule is the relationship, and the ladder keeps the relationship
true on every chart.

## Presets

| | Scalping | Intraday | Swing |
|---|---|---|---|
| Reward floor | 1.5R | 1.5R | 2.0R |
| Default target | 1.5R | 2.0R | 3.0R |
| Pool age | 240 bars | 480 bars | 900 bars |
| SMT window | 8 bars | 12 bars | 18 bars |
| Max hold | 90 bars | 240 bars | 600 bars |
| Signals per day | 5 | 3 | 2 |
| Sessions | London + NY | London + NY | all |

The scalping preset drops the default target to 1.5R rather than keeping it at
2R. A short hold cannot reach a distant pool often enough to justify the wait,
and asking for both a high frequency and a large reward is asking the market for
something it does not offer.
