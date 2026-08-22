# Foxtrader LIT Architecture Map and Audit Gate

Status: **LIT ENGINE NOT READY**

This document is the source-of-truth audit map for the current repository. It deliberately separates what is actually implemented from terminology that has no formal implementation in the codebase.

## 1. Canonical LIT execution path

The first-class production LIT path is currently:

```text
provider / websocket / repository candles
  -> ChartDataController / ChartViewModel
  -> confirmed candle series
  -> LitProStructureDetector
       -> confirmed swings
       -> Pullback
       -> IDM sweep/reclaim
       -> BOS
       -> CHOCH
       -> POI
       -> optional SCOB
  -> LitSequenceValidator
       -> hard chronology gate: IDM -> opposite BOS -> CHOCH
       -> max IDM-to-BOS bars
       -> max BOS-to-CHOCH bars
  -> DisplacementDetector (frozen at CHOCH boundary)
  -> PremiumDiscountCalculator (entry-prefix only)
  -> structural target / R:R gate
  -> LiT quality score
  -> LitSignal
  -> SignalFusionEngine
  -> SignalComputer
  -> ChartSignal / on-chart arrow
```

Primary files:

- `app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/LitEngine.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/LitProStructureDetector.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/LitSequenceValidator.kt`
- `app/src/main/java/com/foxtrader/app/domain/model/SignalIntelligence.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/litx/DisplacementDetector.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/litx/PremiumDiscountCalculator.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/chart/SignalComputer.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/signalintel/SignalFusionEngine.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt`

## 2. Backtest path

`StrategyLibrary` delegates `StrategyType.LIT` to the same `LitEngine` used by live consumers.

For historical bar `i`, it calls the engine with `candles[0..i]` and accepts a signal only when the signal confirmation index and timestamp equal bar `i`.

```text
BacktestEngine
  -> StrategyLibrary / StrategyType.LIT
  -> prefix candles [0..i]
  -> LitEngine
  -> StrategySignal
```

File:

- `app/src/main/java/com/foxtrader/app/domain/usecase/strategies/StrategyLibrary.kt`

This architecture is the correct direction for live/backtest parity, but an explicit parity regression suite is still required before the release gate can pass.

## 3. Scanner / agent consumers

The same first-class engine is referenced by:

- `app/src/main/java/com/foxtrader/app/domain/usecase/scanner/ScannerUseCase.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/ai/agents/LitAgent.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/strategies/StrategyLibrary.kt`
- `app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt`

The intended rule is: these consumers must not reimplement LIT chronology independently.

## 4. Separate LIT X implementation

The repository also contains a second engine:

- `app/src/main/java/com/foxtrader/app/domain/usecase/litx/LitXEngine.kt`

`LitXEngine` is not the canonical LiT Pro sequence. It orchestrates generic market-structure / SMC primitives including:

- `SmcDetector`
- liquidity pools / sweeps
- order blocks
- fair-value gaps
- MSS / CHOCH
- premium / discount
- displacement
- POI retest
- a multi-factor confidence score

It must not be treated as proof that the repository implements proprietary LIT entry models. It remains a separate `StrategyType.LITX` compatibility/product feature until an explicit product decision merges or removes it.

## 5. Repository-defined LIT rule map

The following sequence is explicitly represented in Kotlin code and comments:

```text
Pullback
  -> IDM sweep/reclaim
  -> opposite continuation BOS
  -> CHOCH
  -> displacement aligned with CHOCH
  -> post-shift POI
  -> optional SCOB rejection
  -> first POI retest
  -> structural target + minimum R:R
  -> confidence gate
  -> signal
```

The hard causal core enforced by this branch is:

```text
IDM.confirmationIndex < BOS.confirmationIndex < CHOCH.confirmationIndex
IDM -> BOS <= maxIdmToBosBars
BOS -> CHOCH <= maxBosToChochBars
IDM.direction == CHOCH.direction
BOS.direction != CHOCH.direction
POI, when present, is tied to the active CHOCH
SCOB, when present, confirms after CHOCH in the CHOCH direction
```

## 6. Non-repaint boundaries already present

The current canonical implementation contains several useful non-repaint controls:

- swing points require right-side confirmation bars;
- every structural level has `originIndex` and `confirmationIndex`;
- displacement is recomputed only through the CHOCH confirmation boundary;
- premium/discount is calculated only through the entry bar;
- a LIT signal is emitted only on the first POI retest;
- the backtest strategy sends only prefix candles `[0..i]` into `LitEngine`;
- chart markers are placed on `confirmationIndex`, not hindsight origin pivots.

These controls reduce repaint risk but do **not** by themselves prove the complete release gate.

## 7. P1 fixed in this branch: chronology was not a hard gate

Before this branch, `LitEngine` required BOS before CHOCH and IDM before CHOCH, but it did not require:

```text
IDM < BOS < CHOCH
```

as one strict sequence.

Also, `maxIdmToBosBars` and `maxBosToChochBars` existed in `LitConfig`, but the engine did not hard-reject a setup based on both actual transition distances.

`LitSequenceValidator` now owns these rules as a pure deterministic gate before displacement, POI execution, R:R, or confidence scoring.

## 8. Specification-required terminology

The following concepts do not have a complete, authoritative implementation/specification in the current repository and **must not be invented**:

| Term | Repository status |
|---|---|
| Major Inducement | **SPECIFICATION REQUIRED** |
| Medium Inducement | **SPECIFICATION REQUIRED** |
| Minor Inducement hierarchy | **SPECIFICATION REQUIRED** |
| LET entry model | **SPECIFICATION REQUIRED** |
| MMM1 entry model | **SPECIFICATION REQUIRED** |
| MMM2 entry model | **SPECIFICATION REQUIRED** |
| EDM entry model | **SPECIFICATION REQUIRED** |
| LIT Vector as a proprietary formal rule | **SPECIFICATION REQUIRED** — generic `DisplacementDetector` exists, but equivalence is not proven |
| AH-specific LIT rules | **SPECIFICATION REQUIRED** |
| FO-specific LIT rules | **SPECIFICATION REQUIRED** |
| LO-specific LIT rules | **SPECIFICATION REQUIRED** |
| NY-specific LIT rules | **SPECIFICATION REQUIRED** |
| Major/Medium/Minor inducement precedence | **SPECIFICATION REQUIRED** |
| formal LIT cycle-completion model | **SPECIFICATION REQUIRED** |
| proprietary projection rules | **SPECIFICATION REQUIRED** |

No implementation should silently map these names onto generic ICT/SMC concepts.

## 9. SMT implementation status

SMT is implemented separately in:

- `app/src/main/java/com/foxtrader/app/domain/usecase/smt/SmtDivergenceDetector.kt`

Current strengths include:

- timestamp alignment;
- bounded timestamp skew;
- correlation gate;
- confirmed right-side swings;
- synchronized swing-pair tolerance;
- divergence strength gate;
- confirmation index distinct from the swing origin.

Remaining audit concern:

- event confidence/qualification must be frozen at the information boundary where the divergence became knowable; using later bars to recalculate correlation for a historical divergence would violate strict historical reproducibility.

Until a prefix-stability test proves this, SMT is not counted as a passed non-repaint release gate for LIT.

## 10. Signal-fusion correlation risk

`SignalFusionEngine` currently treats `LiTX`, `LiT`, `SMS`, `SMT`, and `TradePro` as named components with separate weights.

This is not equivalent to statistical independence.

`LiTX`, `LiT`, and `SMS` share structural/liquidity primitives, and TradePro may already incorporate overlapping institutional evidence. Therefore:

- source count must not be interpreted as independent confirmation count;
- diversity boosts must be grouped by evidence family or empirically validated;
- no accuracy claim may rely on stacked correlated confirmations.

This is currently a **P1 confidence-calibration risk**.

## 11. Confidence / accuracy status

The repository contains heuristic scores and thresholds. That does not prove an edge.

The following are still required before any accuracy claim:

- sufficiently large sample sizes;
- chronological backtest;
- untouched out-of-sample set;
- walk-forward analysis;
- confidence intervals;
- ablation tests;
- per-session / per-symbol / per-timeframe results;
- spread/slippage/latency stress;
- live-vs-backtest signal parity;
- Monte Carlo trade-order/execution perturbation where applicable.

Until those artifacts exist, UI confidence values are **setup-quality scores**, not probabilities of winning.

## 12. Missing release-gate tests

Required additions beyond the existing tests:

- `LitSequenceValidatorTest` — added in this branch;
- `LitFutureLeakageTest` — required;
- `LitLiveBacktestParityTest` — required;
- `LitSignalDeduplicationTest` — required;
- `LitSessionDstTest` — blocked by LIT session specification;
- `LitGoldenDatasetTest` — blocked until authoritative LIT fixtures/spec are supplied;
- SMT prefix-stability/future-mutation test — required;
- evidence-family fusion/ablation test — required.

The existing `LitProStructureDetectorTest` checks confirmed boundaries and a monotonic BOS condition, but that is not sufficient to prove full historical snapshot stability.

## 13. Session status

The app contains generic session infrastructure, but the canonical `LitEngine` does not currently implement an authoritative LIT-specific AH -> FO -> LO -> NY cycle.

Therefore session-specific LIT behavior remains **SPECIFICATION REQUIRED** rather than being inferred from generic kill-zone/session code.

## 14. Target / invalidation status

Implemented:

- entry at the first POI retest close;
- stop beyond POI/SCOB with a volatility buffer;
- structural opposing target;
- minimum R:R gate;
- POI age limit;
- one-shot consumption after first retest.

Not yet formalized as a persistent lifecycle object:

- target state transitions;
- explicit setup invalidation event log;
- deterministic signal-forensics event stream / signal ID replay.

## 15. Data-provider / precision status

Provider routing and live-data separation exist outside LIT. The LIT release gate still requires provider-specific parity tests proving that normalized candles do not materially change:

- IDM;
- BOS/CHOCH;
- POI;
- displacement;
- SMT;
- final signal.

No LIT code should hardcode instrument decimal precision or exact floating-point equality for cross-provider structural equality.

## 16. Current severity table

| Severity | Finding | Status |
|---|---|---|
| P1 | IDM/BOS/CHOCH chronology not hard-gated | **FIXED IN THIS BRANCH** |
| P1 | configured IDM->BOS and BOS->CHOCH limits not enforced as final gate | **FIXED IN THIS BRANCH** |
| P1 | LIT and LIT X are distinct engines and can be mistaken for one methodology | DOCUMENTED; product decision required |
| P1 | correlated LiTX/LiT/SMS evidence can be double-counted in fusion | OPEN |
| P1 | official MMM1/MMM2/EDM/LET rules absent | **SPECIFICATION REQUIRED** |
| P1 | official Major/Medium/Minor inducement hierarchy absent | **SPECIFICATION REQUIRED** |
| P1 | strict SMT historical confidence freeze not yet proven | OPEN |
| P2 | no complete event-sourced signal forensic trace | OPEN |
| P2 | no LIT-specific session/DST regression suite | BLOCKED BY SPECIFICATION |

## 17. Release gates

Current state:

```text
LIT RULE MAP                  PARTIAL
LIQUIDITY ENGINE              PARTIAL / shared structural primitives
INDUCEMENT ENGINE             PARTIAL: IDM only
INDUCEMENT HIERARCHY          SPECIFICATION REQUIRED
CYCLE ENGINE                  SPECIFICATION REQUIRED
SESSION ENGINE                SPECIFICATION REQUIRED for LIT semantics
SMT ENGINE                    IMPLEMENTED / STRICT PREFIX AUDIT REQUIRED
VECTOR ENGINE                 SPECIFICATION REQUIRED (displacement exists)
ENTRY MODELS                  SPECIFICATION REQUIRED for LET/MMM1/MMM2/EDM
NON-REPAINT                   PARTIAL / more regression tests required
FUTURE LEAKAGE                NOT YET PROVEN
LIVE/BACKTEST PARITY          ARCHITECTURE ALIGNED / TEST REQUIRED
PROVIDER NORMALIZATION        APP SUPPORT EXISTS / LIT PARITY TEST REQUIRED
OUT-OF-SAMPLE TEST            NOT PROVEN
APK STABILITY                 CI REQUIRED
CHART SIGNALS                 IMPLEMENTED / CI + UI regression required
BUILD                         CI REQUIRED FOR THIS BRANCH
UNIT TESTS                    CI REQUIRED FOR THIS BRANCH
INSTRUMENTED TESTS            NOT PROVEN
```

## 18. Final status

**LIT ENGINE NOT READY**

Reason: the canonical engine now has a stricter causal chronology gate, but proprietary entry/cycle/hierarchy specifications, strict future-leak proof, evidence-family calibration, and statistical out-of-sample validation are still incomplete.

The engine must not be labeled `PRODUCTION READY` merely because it compiles or produces attractive historical signals.
