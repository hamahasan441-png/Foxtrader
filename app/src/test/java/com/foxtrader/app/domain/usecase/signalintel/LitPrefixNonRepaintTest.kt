package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LitBreakMode
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.LitXConfidenceScorer
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.litx.MitigationBlockDetector
import com.foxtrader.app.domain.usecase.litx.MssClassifier
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Prefix non-repaint contract for **both** LiT engines.
 *
 * This was the largest coverage gap carried out of Session 1. SMT had a prefix
 * test (and a real defect was found through it); LiT Adventure and LiT May
 * Madness had none. Both are believed non-repainting *by construction* — they
 * emit at most one signal, pinned to the right edge — but "by construction" is
 * an argument, not evidence, and the argument depends on invariants
 * (`isFreshRetest`, `retestIndex == candles.lastIndex`) that a future refactor
 * can quietly break.
 *
 * Three properties are pinned:
 *
 *  1. **Right-edge emission.** Every signal is confirmed on the newest bar of
 *     the prefix that produced it.
 *  2. **Replay equivalence.** Driving prefixes bar-by-bar and collecting what
 *     each one emits must yield exactly what a caller would accumulate live.
 *     Re-running any single prefix must give a byte-identical answer — the
 *     engines are pure functions of their input window.
 *  3. **Immutability after the fact.** A signal emitted at prefix `m` describes
 *     bar `m-1`. Re-deriving it later from a longer prefix is not possible for a
 *     right-edge emitter, so instead we assert the stronger practical property:
 *     the accumulated signal history from an incremental walk is identical to
 *     the history produced by a second, independent walk. Any hidden state that
 *     leaked between calls would break this.
 *
 * These tests are deliberately cheap to extend: add a mode or an engine and the
 * same three assertions apply.
 */
class LitPrefixNonRepaintTest {

    private fun litXEngine() = LitXEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        sessionDetector = SessionDetector(),
        displacementDetector = DisplacementDetector(),
        mitigationDetector = MitigationBlockDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
        mssClassifier = MssClassifier(),
        scorer = LitXConfidenceScorer(),
    )

    private fun litEngine() = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = DisplacementDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
    )

    /** Permissive thresholds so the fixture actually reaches validation. */
    private val litXConfig = LitXConfig(
        minGrade = LitXGrade.B,
        minRiskReward = 1.2,
        requireHtfAlignment = false,
        requireStrongMss = false,
        requireDirectionalZone = false,
        minConfidenceScore = 55,
        profile = SignalProfile.INTRADAY,
        mode = LitXMode.PRECISION,
    )

    /** Permissive but production-sanitized settings for exercising LiT Pro. */
    private val litConfig = LitConfig(
        minConfidence = 50,
        requireDirectionalZone = false,
        setupLookback = 180,
        minRiskReward = 1.0,
        displacementAtrMultiple = 0.8,
        swingLeftBars = 2,
        swingRightBars = 2,
        breakMode = LitBreakMode.BODY,
        maxIdmToBosBars = 30,
        maxBosToChochBars = 36,
        maxPoiAgeBars = 80,
        followDeeperPoiCandle = false,
        hiddenShadowMaxAtrFraction = 1.0,
        stopAtrBuffer = 0.02,
    )

    // ---------------------------------------------------------------- LiT X

    @Test
    fun `lit adventure emits only on the newest confirmed bar`() {
        val engine = litXEngine()
        var emitted = 0
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            for (end in WARMUP..series.size) {
                val window = series.subList(0, end)
                val signal = engine.analyze(SYMBOL, Timeframe.M15, window, litXConfig).signal
                    ?: continue
                emitted++
                assertEquals(
                    "LiT Adventure anchored a signal off the right edge (seed=$seed bars=$end)",
                    window.lastIndex,
                    signal.confirmationIndex,
                )
            }
        }
        assertTrue("fixture produced no LiT Adventure signals; test is vacuous", emitted > 0)
    }

    @Test
    fun `lit adventure is a pure function of its window`() {
        val a = litXEngine()
        val b = litXEngine()
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            // Walk A forwards; walk B over the same prefixes in reverse order.
            // A stateful engine would disagree between the two orders.
            val forward = (WARMUP..series.size).map { end ->
                end to a.analyze(SYMBOL, Timeframe.M15, series.subList(0, end), litXConfig).signal
            }
            val reverse = (WARMUP..series.size).reversed().map { end ->
                end to b.analyze(SYMBOL, Timeframe.M15, series.subList(0, end), litXConfig).signal
            }.reversed()

            assertEquals(
                "LiT Adventure output depends on call order, so it is carrying state (seed=$seed)",
                forward.map { it.first to it.second?.let(::fingerprintX) },
                reverse.map { it.first to it.second?.let(::fingerprintX) },
            )
        }
    }

    @Test
    fun `lit adventure incremental history matches an independent replay`() {
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            val first = accumulateX(litXEngine(), series)
            val second = accumulateX(litXEngine(), series)
            assertEquals("LiT Adventure replay is not reproducible (seed=$seed)", first, second)
        }
    }

    // ------------------------------------------------------------ LiT May Madness

    @Test
    fun `lit may madness emits only on the newest confirmed bar`() {
        val engine = litEngine()
        var emitted = 0
        val stages = mutableMapOf<String, Int>()
        val narratives = mutableMapOf<String, Int>()
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            for (end in WARMUP..series.size) {
                val window = series.subList(0, end)
                val analysis = engine.analyze(SYMBOL, Timeframe.M15, window, litConfig)
                val signal = analysis.signal
                if (signal == null) {
                    stages.merge(analysis.stage.name, 1, Int::plus)
                    narratives.merge(analysis.narrative, 1, Int::plus)
                    continue
                }
                emitted++
                assertEquals(
                    "LiT May Madness anchored a signal off the right edge (seed=$seed bars=$end)",
                    window.lastIndex,
                    signal.confirmationIndex,
                )
            }
        }
        val dominantRejections = narratives.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString { "${it.value}x ${it.key}" }
        assertTrue(
            "fixture produced no LiT May Madness signals; stages=$stages; reasons=$dominantRejections",
            emitted > 0,
        )
    }

    @Test
    fun `lit may madness never references a bar after its own confirmation`() {
        val engine = litEngine()
        val offenders = mutableListOf<String>()
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            for (end in WARMUP..series.size) {
                val signal = engine.analyze(SYMBOL, Timeframe.M15, series.subList(0, end), litConfig)
                    .signal ?: continue
                // The documented chronology: sweep precedes shift precedes
                // confirmation. Any inversion means the sequence gate let an
                // impossible ordering through.
                if (signal.sweepIndex > signal.shiftIndex) {
                    offenders += "seed=$seed bars=$end sweep=${signal.sweepIndex} > shift=${signal.shiftIndex}"
                }
                if (signal.shiftIndex > signal.confirmationIndex) {
                    offenders += "seed=$seed bars=$end shift=${signal.shiftIndex} > confirm=${signal.confirmationIndex}"
                }
            }
        }
        assertTrue(offenders.take(5).joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `lit may madness incremental history matches an independent replay`() {
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            val first = accumulate(litEngine(), series)
            val second = accumulate(litEngine(), series)
            assertEquals("LiT May Madness replay is not reproducible (seed=$seed)", first, second)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun accumulateX(engine: LitXEngine, series: List<Candle>): List<String> =
        (WARMUP..series.size).mapNotNull { end ->
            engine.analyze(SYMBOL, Timeframe.M15, series.subList(0, end), litXConfig)
                .signal?.let(::fingerprintX)
        }

    private fun accumulate(engine: LitEngine, series: List<Candle>): List<String> =
        (WARMUP..series.size).mapNotNull { end ->
            engine.analyze(SYMBOL, Timeframe.M15, series.subList(0, end), litConfig)
                .signal?.let(::fingerprint)
        }

    private fun fingerprintX(s: com.foxtrader.app.domain.model.LitXSignal): String =
        listOf(
            s.direction, s.confirmationIndex, s.entry, s.stopLoss,
            s.takeProfit1, s.takeProfit2, s.riskReward,
            s.confidence.score, s.confidence.grade, s.confirmations.sorted(),
        ).joinToString("|")

    private fun fingerprint(s: com.foxtrader.app.domain.model.LitSignal): String =
        listOf(
            s.direction, s.confirmationIndex, s.sweepIndex, s.shiftIndex,
            s.entry, s.stopLoss, s.takeProfit, s.confidence, s.confirmations.sorted(),
        ).joinToString("|")

    /**
     * Deterministic random walk with periodic impulses and pullbacks, so the
     * sweep -> shift -> retest pipeline can actually complete on some windows.
     */
    private fun walk(seed: Int): List<Candle> {
        val random = Random(seed)
        val out = ArrayList<Candle>(BARS)
        var price = 1.1000
        var drift = 0.00004
        for (i in 0 until BARS) {
            if (i % 40 == 0) drift = random.nextDouble(-0.00012, 0.00012)
            val impulse = if (random.nextInt(18) == 0) random.nextDouble(-0.0020, 0.0020) else 0.0
            val open = price
            price += drift + random.nextDouble(-0.0006, 0.0006) + impulse
            val close = price
            out += Candle(
                timestamp = BASE_TIMESTAMP + i * 900_000L,
                open = open,
                high = maxOf(open, close) + random.nextDouble(0.00005, 0.0004),
                low = minOf(open, close) - random.nextDouble(0.00005, 0.0004),
                close = close,
                volume = random.nextDouble(600.0, 2_400.0),
            )
        }
        return out
    }

    private companion object {
        const val SYMBOL = "EURUSD"
        const val SERIES_COUNT = 10
        const val BARS = 220
        const val WARMUP = 80
        const val BASE_TIMESTAMP = 1_700_000_000_000L
    }
}
