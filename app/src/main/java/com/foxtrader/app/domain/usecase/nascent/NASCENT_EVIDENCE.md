# Nascent FX — evidence register

Every rule in this package carries an evidence level, and nothing may be
silently promoted upward. This file is the register; the enum that enforces it
is `model/EvidenceLevel.kt`.

| Level | Meaning |
| --- | --- |
| `NASCENT_VERIFIED` | Stated outright by the Nascent FX Primary Analysis material. |
| `CORROBORATED` | Strongly supported by closely related AlgoHub/LIT material, but not written in the Nascent source. |
| `INFERRED_V1` | Reconstructed from Nascent prose or diagrams. Implementable and testable, not an official definition. |
| `UNRESOLVED` | The name is real; the geometry or arithmetic is not known. |
| `RESEARCH_ONLY` | Experimental. Runs only in research mode. |

## Concepts

| Concept | Name | Geometry as implemented | Where |
| --- | --- | --- | --- |
| External / internal structure split | `NASCENT_VERIFIED` | `INFERRED_V1` (pivot width, break = closed body) | `NascentStructureEngine` |
| Multi-timeframe mapping | `NASCENT_VERIFIED` | `NASCENT_VERIFIED` except M30, which the source never lists (`INFERRED_V1`) | `NascentConfig.externalFor` |
| Liquidity cycle | `NASCENT_VERIFIED` | `INFERRED_V1` (four-pivot freeze rule) | `NascentLiquidityEngine` |
| ILQ = Inducement Liquidity, range high on B2S | `NASCENT_VERIFIED` | — | `LiquidityType.ILQ` |
| TLQ = Transactional Liquidity, range low on B2S | `NASCENT_VERIFIED` | — | `LiquidityType.TLQ` |
| SLQ = Structural Liquidity, inside the range | `NASCENT_VERIFIED` | `INFERRED_V1` (confirmed pivots only) | `NascentLiquidityEngine` |
| Decisional SLQ | name `NASCENT_VERIFIED` | **`UNRESOLVED`** — off by default, `enableDecisionalSlq` | `NascentLiquidityEngine` |
| Key-level gate ("no location, no signal") | `NASCENT_VERIFIED` | `INFERRED_V1` (ATR-scaled envelope over the setup window) | `NascentInternalContext.levelReachedBetween` |
| EPA = Efficient Price Action | `NASCENT_VERIFIED` | `INFERRED_V1` (prior range + delivery efficiency + mitigation + structural return) | `NascentEpaEngine` |
| EPA raises MSU3 probability | `NASCENT_VERIFIED` | Quality enhancer, required only in `SOURCE_STRICT` | `Msu3Detector` |
| "EPA + DP (momentum validity)" formula | term `NASCENT_VERIFIED` | **`UNRESOLVED`** — no RSI/ATR/body/volume rule is presented as this | `NascentEpaEngine` |
| MSU = Manipulation Setup | `CORROBORATED` | — | package `msu` |
| MSU Type 1 = continuation | `NASCENT_VERIFIED` | `INFERRED_V1` | `Msu1Detector` |
| MSU Type 2 = reversal | `NASCENT_VERIFIED` | `INFERRED_V1` | `Msu2Detector` |
| MSU Type 3 = continuation | `NASCENT_VERIFIED` | `INFERRED_V1` | `Msu3Detector` |
| DP = Direct Pullback | `CORROBORATED` (source spells out "Direct Pullback") | `INFERRED_V1` (structural retracement, not a midpoint touch) | `NascentDirectPullbackEngine` |
| "50% of the range" | `NASCENT_VERIFIED` | Zone, not float equality; ATR-scaled tolerance | `NascentDirectPullbackEngine` |
| EQ = Equilibrium | `CORROBORATED` | — | `NascentDirectPullbackEngine` |
| TOM = Transfer Of Money | `CORROBORATED` | **`UNRESOLVED`** completion; `RESEARCH_ONLY` transition rule | `NascentTomEngine` |
| Range / Simple / Structure Point Transaction | names `CORROBORATED` | **`UNRESOLVED`** — reported as UNRESOLVED, never decisive alone | `NascentTransactionEngine` |
| Sweep of High / Low | name `NASCENT_VERIFIED` | `INFERRED_V1` (trade beyond, close reclaims) | `SweepConfirmation` |
| Engulfing candle | name `NASCENT_VERIFIED` | `INFERRED_V1`, variant explicit and configurable | `EngulfConfirmation` |
| Direct Pullback + 50% | `NASCENT_VERIFIED` | `INFERRED_V1` | `DirectPullbackConfirmation` |
| Protected high / low | `INFERRED_V1` (engineering term, not Nascent's) | `INFERRED_V1` | `msu` detectors |

## Deliberate non-implementations

- **IPA (Inefficient Price Action).** The contrast is corroborated but Nascent
  never formalises a detector, so none exists here. No IPA rule is allowed to
  block an otherwise valid setup.
- **AOI** is treated as a contextual search area, never as an order block, FVG,
  or supply/demand zone.
- **TLQ is never a take-profit.** Targets come from an explicit R multiple, and
  the config says so at the field.

## Mode behaviour

| Mode | Admits |
| --- | --- |
| `SOURCE_STRICT` | `NASCENT_VERIFIED`, `CORROBORATED`. MSU3 requires EPA. TOM always `UNKNOWN`. |
| `BALANCED` (default) | Adds `INFERRED_V1`. TOM may reach `ACTIVE`, never `COMPLETED`. |
| `RESEARCH` | Adds `UNRESOLVED` and `RESEARCH_ONLY`, including the experimental TOM completion and `EPA_DP_TOM`. |

## Open questions

These are genuinely unknown and are not papered over anywhere in the code:

1. The exact TOM completion geometry.
2. The exact geometry of each of the three transaction types.
3. The exact Decisional SLQ detector.
4. The formula behind "momentum validity" in EPA + DP.
5. Every numeric threshold. Nascent publishes none, so all of them live in
   `NascentConfig` with defaults chosen to be reasonable, not authoritative.
