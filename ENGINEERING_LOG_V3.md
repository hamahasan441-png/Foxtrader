# FOXTRADER v3 — Engineering Log

Executes the v3 plan in its stated order: **Gate 0 → Track A → Track B**.
Tracks C, D and E were not attempted; §4 says why, and why that is the right
call rather than an omission.

Same environment constraint as Sessions 1 and 2, and it is the reason Gate 0
comes first: **no Kotlin compiler, no Gradle, no Android SDK, no device.**
Nothing in this repository has been compiled by me across three sessions.

---

## Gate 0 — CI now gates the artifact on the tests

**File:** `.github/workflows/android.yml`

CI already existed, so the gap was not "no pipeline" — it was pipeline *order*.
The job ran:

```
assembleDebug → upload APK → testDebugUnitTest → upload reports
```

A commit that broke the unit tests still published a downloadable APK. The job
turned red afterwards, but by then an untested build was sitting in the
artifacts list waiting to be installed. Reordered to:

```
testDebugUnitTest → upload reports → assembleDebug → upload APK
```

Now a failing suite produces **no artifact at all**, which is the point: an APK
should be evidence that the tests passed, not a thing that happens to exist
alongside them. Test reports still upload on failure (`if: always()`), so a red
build is still diagnosable.

Validated: the YAML parses and the step order is as intended. That is the whole
change, and it is one of the few things in three sessions I could actually
verify end to end.

---

## Track A — Both LiT engines now have a prefix contract

**File:** `app/src/test/.../signalintel/LitPrefixNonRepaintTest.kt`

This was the largest coverage gap, carried since Session 1. SMT had a prefix
test — and a real repaint defect was found through it. Neither LiT engine had
one.

Both are non-repainting *by construction*: each emits at most one signal, pinned
to the right edge. But "by construction" is an argument, not evidence, and the
argument rests on invariants (`isFreshRetest`, `retestIndex == candles.lastIndex`)
that a refactor can quietly break without any test noticing. Session 2 added four
LiT Adventure modes, each touching that emission path — which makes the missing
test considerably more expensive than it was a session ago.

Six tests, three properties, applied to both engines:

1. **Right-edge emission.** Every signal's `confirmationIndex` equals
   `window.lastIndex` for the prefix that produced it.
2. **Purity.** The same prefixes are evaluated forwards and backwards on two
   engine instances and the outputs must match. Any hidden state carried between
   calls breaks this, which is the failure mode that a forwards-only walk cannot
   see.
3. **Reproducible replay.** Two independent walks over the same series must
   produce identical accumulated signal histories, compared by full fingerprint
   (direction, indices, entry/SL/TP, score, grade, confirmations) rather than by
   count.

Plus a chronology check on LiT May Madness: `sweepIndex ≤ shiftIndex ≤
confirmationIndex`. `LitSequenceValidator` is supposed to make an inversion
impossible; this asserts it on generated data instead of trusting the gate.

Each test that could pass vacuously asserts a non-zero signal count first. A
green suite that never produced a signal is worse than a red one, because it
reads as confirmation.

---

## Track B — Accuracy display no longer quotes noise

**Files:** `domain/usecase/signalintel/SignalOutcomeEvaluator.kt` and its new test

`SourceStats` gains `rateIsMeaningful` and `reportableWinRate`, gated on
`MIN_RESOLVED_FOR_RATE = 20`.

The failure mode this closes: **"100% (3 signals)"**. It reads as a strong
result, it is not one, and a trader who sizes up on it is being misled by the
app rather than by the market. Below 20 resolved signals a win rate is noise
wearing a percentage sign.

Deliberately *not* done by returning null from `winRate`: the counts stay
available, so the UI can render "4W / 1L — not enough data" rather than either a
fake percentage or a blank. Withholding the conclusion while showing the evidence
is the honest version.

20 is a judgement call and is documented as one. At n=20 a true 50% process still
shows roughly 30–70% at one standard error — wide, but no longer meaningless.
One named constant, so the trade-off is visible and adjustable in one place.

**This bites the feature added last session.** LiT Adventure SNIPER is designed
to fire rarely, so it will sit below the threshold for a long time and show no
percentage. That is the correct outcome: a selective mode has to be judged over a
longer horizon, not awarded a confident number early. A test pins exactly this
case so nobody "fixes" it later by lowering the bar.

---

## 4. Not attempted, and why that is deliberate

**Track C (symbol registry, contract tests, cross-provider reconciliation).**
The v2 fix corrected the two concrete symbol bugs I could demonstrate. The
registry refactor touches every provider adapter — the highest-blast-radius
change on the list — and I cannot compile a line of it. Doing it blind would put
the data layer at risk to fix a problem that is currently *fixed*. It should be
the first thing done once CI is green, not before.

**Track D (strategy tester + replay).** The plan sequenced this after A and C for
a reason that still holds: a tester built on unverified engines produces
confident wrong numbers, which is worse than no tester. Track A landed this
session; C has not.

**Track E (premium chart UI).** Unchanged from Session 2. Its entire value is
visual and I can render nothing. Several hundred lines of unverifiable styling
against a working chart is a bad trade at any point, and a worse one before CI
has ever run.

**Backend.** `backend/` (FastAPI, three routers) still unaudited across three
sessions.

I would rather hand you three things that are probably right than six things
where you cannot tell which three are wrong.

---

## 5. Run this first

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

New this session:
`LitPrefixNonRepaintTest`, `SignalOutcomeEvaluatorSampleSizeTest`.
Still unrun from earlier sessions:
`LitXModeTest`, `CryptoSymbolNormalizerTest`, `SmtPrefixNonRepaintPropertyTest`.

Expected trouble spots, in order of likelihood:

1. **`LitPrefixNonRepaintTest` / `LitXModeTest` vacuity assertions.** If the
   synthetic walk never reaches validation, the "fixture produced no signals"
   assertion fires. Fix by widening `SERIES_COUNT` / `BARS`, **not** by relaxing
   the config or deleting the assertion — a vacuous green is the outcome those
   lines exist to prevent.
2. **`SignalOutcomeEvaluator` companion visibility.** `private companion object`
   became `companion object` so the UI can read `MIN_RESOLVED_FOR_RATE`. If
   anything depended on it being private, that surfaces here.
3. **Purity test on LiT Adventure.** If the forwards/backwards walk disagrees,
   that is not a test bug — it means an engine is carrying state between calls,
   and it is the most valuable thing this session could find.

Then Track C, with a green build under it.
