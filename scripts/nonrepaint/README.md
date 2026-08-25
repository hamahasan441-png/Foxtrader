# Non-repaint reference harness

Runnable reference implementations used to measure the SMT prefix non-repaint
contract without an Android/Gradle toolchain.

`smt_prefix_reference.py` is a line-for-line port of the pure decision core of
`SmtDivergenceDetector` (`findSwings`, `synchronizedPairs`, the sweep/held
comparisons, `confirmationAligned = max(p1,q1) + swingLookback`). Peer
timestamps are identical to primary, so `align()` is the identity and the
alignment layer is factored out — this isolates the pair matcher.

`smt_prefix_reference_fixed.py` swaps in the earliest-confirmable selection rule
that now ships in the Kotlin engine.

    python3 smt_prefix_reference.py         # nearest-distance matching (defective)
    python3 smt_prefix_reference_fixed.py   # earliest-confirmable matching (shipped)

Measured on 200 correlated series x 400 bars, every prefix, SmtConfig INTRADAY
defaults (maxSwingSyncBars=4, maxSignalAgeBars=24):

| | nearest-distance | earliest-confirmable |
|---|---|---|
| confirmed events | 2519 | 2513 |
| vanished inside maxSignalAgeBars | 186 (7.4%) | 0 |

These scripts are documentation and a design guard, not part of the build. The
authoritative regression test is the JVM one:
`app/src/test/java/com/foxtrader/app/domain/usecase/smt/SmtPrefixNonRepaintPropertyTest.kt`
