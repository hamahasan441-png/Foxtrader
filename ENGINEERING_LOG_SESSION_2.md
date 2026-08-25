# FOXTRADER — Engineering Log, Session 2

Continues `ENGINEERING_LOG_SESSION_1.md`. Same environment constraint as before,
restated because it governs how much of this you should trust:

**No Kotlin compiler, no Gradle, no Android SDK, no network, no device.** Nothing
here has been compiled. Where I write "verified", it means the logic was ported
to a runnable reference and the property was measured; the Kotlin translation is
unverified. Everything else is a code reading.

You asked for five things. I completed two, audited a third, and did not attempt
two. That split is deliberate — see §5.

---

## 1. LiT Adventure execution modes (done)

**Files:** `domain/model/LitX.kt`, `domain/usecase/litx/LitXEngine.kt`

`SignalProfile` (SCALPING/INTRADAY/SWING) already varied *thresholds* against one
fixed rule set. A mode varies **which rules apply at all**, so two modes on the
same candles can disagree about whether a setup exists — not merely about its
grade. That distinction is the whole point; a "mode" that only moves numbers is
a preset wearing a costume.

`LitXMode` adds four rule sets:

| gate | SNIPER | PRECISION | MOMENTUM | SWEEP_REVERSAL |
|---|---|---|---|---|
| liquidity sweep required | yes | yes | **no** | yes |
| POI retest required | yes | yes | **no** | yes |
| in-band tap (not near-band) | **yes** | no | n/a | no |
| FVG admissible as POI origin | **no** | yes | yes | yes |
| aligned displacement required | **yes** | no | **yes** | no |
| kill-zone session required | **yes** | no | no | no |
| HTF trend agreement (preset) | yes | yes | yes | **no** |
| premium/discount origin (preset) | yes | yes | **no** | yes |

- **SNIPER** — the accuracy-first mode you asked for. Every confluence
  mandatory, in-band entry only, and a fair value gap is refused as a standalone
  origin (it is the weakest POI kind at quality 65 vs 78/88 and carries no
  order-flow evidence of participation). Expect very few signals. That is the
  design, not a defect.
- **PRECISION** — the repository's existing behaviour, unchanged, and the
  default. Legacy persisted config and every existing positional
  `LitXConfig(...)` call deserialize into it.
- **MOMENTUM** — displacement-led continuation. Fires on the bar the shift
  becomes knowable rather than waiting for a retest, so the premium/discount
  gate is dropped: a continuation entry is by definition not at a discount.
- **SWEEP_REVERSAL** — liquidity-led counter-trend turn. HTF agreement is not
  required, because a mode that trades reversals cannot also demand that the
  higher timeframe already agrees.

### The causal contract is unchanged

Every mode still emits at most one signal, pinned to `candles.lastIndex`.
Retest modes trigger on the first POI tap; MOMENTUM triggers on
`shiftKnowledgeIndex == candles.lastIndex`. A mode can change how selective the
engine is; it can never change when the engine is allowed to know something.

### One judgement call you should review

MOMENTUM does not evaluate retests, so scoring it as a *failed* retest (30) would
penalise it for a rule it does not run and it would almost never clear
`minConfidenceScore`. It gets a neutral 60 instead, and pays for the factor by
requiring aligned displacement, which PRECISION does not. This is a defensible
choice, not an obviously correct one — if you disagree, the constant is
`NEUTRAL_RETEST_SCORE` in `LitXEngine`.

### Traceability

Every emitted signal now carries `MODE_<NAME>` in `confirmations`, and the mode
label leads the rationale string. Without this, per-mode accuracy cannot be
measured after the fact — a stored signal has to be attributable to the rule set
that produced it. The master prompt requires exactly this.

### Tests added (not executed)

`app/src/test/.../litx/LitXModeTest.kt`:

- SNIPER is a strict subset of PRECISION on identical inputs **and identical
  thresholds**, so any difference is attributable to the rule set alone.
- The modes actually disagree somewhere — guards against the modes collapsing
  into the same behaviour, which would make the whole feature decoration.
- Every mode emits only at `candles.lastIndex`.
- Every emitted signal records its mode.
- Default is PRECISION.

---

## 2. On-chart signal history and accuracy

**Already built — I did not rebuild it.** `ChartSignalHistory`,
`SignalHistoryOverlay`, `state.showSignalHistory` and `state.liveSignalStats`
exist and work; `SignalOutcomeEvaluator` scores outcomes conservatively (a candle
touching both SL and TP counts as LOSS, because intrabar ordering is unknowable
from OHLC — accuracy is deliberately hard to inflate, which is correct).
`LitXSignalEntity` + `LitXSignalDao` persist accepted setups across sessions.

The gap was attribution, not display, and §1 closes it: history entries can now
be grouped by `MODE_*` so you can compare Sniper's hit rate against Precision's
on your own data instead of trusting a claim.

I have not added a per-mode accuracy panel. That is UI work I cannot render or
verify, and a wrong number in an accuracy display is worse than no display.

---

## 3. Provider / connection / symbol review — one real bug found and fixed

**Files:** new `data/remote/api/CryptoSymbolNormalizer.kt`; `KuCoinDataSource`
and `OkxDataSource` now delegate to it.

Most of the remote layer is already hardened: reconnect loops are generation-
pinned, heartbeats stop themselves when superseded, no `GlobalScope`, no
unbounded retry storms, `containedOrNull` isolates each engine so a bad provider
response degrades one overlay instead of crashing the frame. Earlier audit rounds
did that work. I found no new crash path there.

Symbol handling was the weak spot. `normalizeSymbol` was duplicated **verbatim**
in `KuCoinDataSource` and `OkxDataSource`, and both copies carried the same two
defects:

**Defect 1 — quote assets ending in another quote asset were mis-split.**
The candidate list was scanned in declaration order with `USD` ahead of the
stablecoins that end in it:

| input | before | after |
|---|---|---|
| `BTCBUSD` | `BTCB-USD` ❌ | `BTC-BUSD` |
| `BTCTUSD` | `BTCT-USD` ❌ | `BTC-TUSD` |
| `BTCFDUSD` | `BTCFD-USD` ❌ | `BTC-FDUSD` |

BUSD, TUSD and FDUSD are among the most-traded quote assets on these venues.
The failure was **silent**: the mis-split produced a syntactically valid
instrument, so the request went out and came back empty — or, on a venue where
`BTCB` exists, came back with *someone else's candles* attributed to the symbol
you asked for. Candidates are now matched longest-first, so precedence no longer
depends on declaration order.

**Defect 2 — common fiat and exchange quotes were missing entirely.**
`BTCTRY`, `ETHBRL`, `BNBJPY` matched nothing, fell through unchanged, and were
rejected by the venue with no diagnostic. The symbol simply returned no data.

The splitter fails closed: when no confident split exists it returns the input
untouched, so the venue reports an unknown instrument rather than the app
inventing a plausible wrong one.

Verified: the 20 cases in `CryptoSymbolNormalizerTest` were run against a port of
the new logic; all pass. The Kotlin is uncompiled.

Left alone deliberately: the per-venue `*_QUOTE_SUFFIXES` lists. Those are
*support predicates* ("does this venue plausibly list this symbol"), not
splitters, and they are correctly venue-specific. The fix incidentally makes them
more accurate — `BTCBUSD` is no longer misrouted to KuCoin as `BTCB-USD`.

---

## 4. What I did NOT do, and why

**Premium chart restyling.** This is Compose UI work whose entire value is
visual. I cannot render it, screenshot it, or check a single frame. Writing
several hundred lines of unverifiable styling into a chart that currently works
risks trading a working chart for a broken one, and you would not find out until
you built it. Not a good trade without a build loop.

**Strategy tester on chart with replay history across all indicators.** The
pieces exist — `LiveStrategyEngine`, the Backtest Lab, and a replay path that
already shares `StrategyFunction` with the backtester, so plotted markers and
backtest results cannot diverge. Wiring a full on-chart tester across every
indicator is multi-session work that needs the test suite running to be safe. It
is the right next project; it is not something to half-build blind.

**Backend review.** `backend/` (FastAPI, three routers) was not audited this
session. Session 1 and 2 both went to the Android signal path.

---

## 5. Do this first

1. **Build and run the tests.** Everything above is downstream of this:
   ```
   ./gradlew :app:testDebugUnitTest --tests "*LitXMode*" --tests "*CryptoSymbolNormalizer*" --tests "*Smt*"
   ./gradlew :app:assembleDebug
   ```
   If `LitXModeTest` fails its "fixture must produce PRECISION setups" assertion,
   the synthetic walk isn't reaching validation — widen `SERIES_COUNT`/`BARS`
   rather than weakening the config, or the test becomes vacuous.
2. Decide on `NEUTRAL_RETEST_SCORE` (§1).
3. Prefix tests for both LiT engines — still the largest coverage gap, carried
   over from Session 1.
