package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.asLitMayMadnessSignalConfig
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * POI divergence confirmation: the detector itself, and the gate it drives
 * inside [LitEngine].
 */
class LitPoiDivergenceTest {

    private val detector = LitPoiDivergenceDetector()

    private val engine = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = DisplacementDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
    )

    // ------------------------------------------------------------------
    // Detector
    // ------------------------------------------------------------------

    @Test
    fun `a lower price low against a higher rsi low is a bullish divergence`() {
        val candles = divergenceSeries(bullish = true, rsiConfirms = false)

        val found = detector.detect(
            candles = candles,
            retestIndex = candles.lastIndex,
            direction = Direction.BULLISH,
            lookback = 80,
            rsiPeriod = 14,
            minRsiGap = 1.0,
        )

        assertNotNull("price extended lower while RSI held up", found)
        requireNotNull(found)
        assertTrue("price must have made the lower low", found.toPrice < found.fromPrice)
        assertTrue("RSI must have refused to follow", found.toRsi > found.fromRsi)
    }

    @Test
    fun `a lower price low confirmed by a lower rsi low is not a divergence`() {
        val candles = divergenceSeries(bullish = true, rsiConfirms = true)

        assertNull(
            "momentum confirmed the new low, so there is nothing to fade",
            detector.detect(
                candles = candles,
                retestIndex = candles.lastIndex,
                direction = Direction.BULLISH,
                lookback = 80,
                rsiPeriod = 14,
                minRsiGap = 1.0,
            ),
        )
    }

    @Test
    fun `bearish detection is the mirror of bullish`() {
        val bearish = divergenceSeries(bullish = false, rsiConfirms = false)

        val found = detector.detect(
            candles = bearish,
            retestIndex = bearish.lastIndex,
            direction = Direction.BEARISH,
            lookback = 80,
            rsiPeriod = 14,
            minRsiGap = 1.0,
        )

        assertNotNull(found)
        requireNotNull(found)
        assertTrue("price must have made the higher high", found.toPrice > found.fromPrice)
        assertTrue("RSI must have refused to follow", found.toRsi < found.fromRsi)
    }

    @Test
    fun `a divergence narrower than the configured gap is rejected`() {
        val candles = divergenceSeries(bullish = true, rsiConfirms = false)

        val wide = detector.detect(
            candles, candles.lastIndex, Direction.BULLISH,
            lookback = 80, rsiPeriod = 14, minRsiGap = 1.0,
        )
        requireNotNull(wide)

        assertNull(
            "a gap threshold above the actual gap must reject it",
            detector.detect(
                candles, candles.lastIndex, Direction.BULLISH,
                lookback = 80, rsiPeriod = 14, minRsiGap = wide.rsiGap + 5.0,
            ),
        )
    }

    @Test
    fun `nothing after the retest bar is ever read`() {
        val candles = divergenceSeries(bullish = true, rsiConfirms = false)
        val retest = candles.lastIndex

        val withFuture = detector.detect(
            candles + tailNoise(candles.last(), 20),
            retest, Direction.BULLISH, lookback = 80, rsiPeriod = 14, minRsiGap = 1.0,
        )
        val withoutFuture = detector.detect(
            candles, retest, Direction.BULLISH, lookback = 80, rsiPeriod = 14, minRsiGap = 1.0,
        )
        assertEquals("appended bars changed a past verdict", withoutFuture, withFuture)
    }

    @Test
    fun `the earlier pivot is always one that had already confirmed`() {
        val candles = divergenceSeries(bullish = true, rsiConfirms = false)
        val pivotRight = 2

        val found = detector.detect(
            candles, candles.lastIndex, Direction.BULLISH,
            lookback = 80, rsiPeriod = 14, minRsiGap = 1.0,
            pivotLeft = 2, pivotRight = pivotRight,
        )
        requireNotNull(found)
        assertTrue(
            "the earlier pivot needed bars that had not closed yet",
            found.fromIndex + pivotRight <= candles.lastIndex,
        )
    }

    @Test
    fun `degenerate input is handled without throwing`() {
        val candles = divergenceSeries(bullish = true, rsiConfirms = false)
        assertNull(detector.detect(emptyList(), 0, Direction.BULLISH, 80, 14, 1.0))
        assertNull(detector.detect(candles, -1, Direction.BULLISH, 80, 14, 1.0))
        assertNull(detector.detect(candles, 9_999, Direction.BULLISH, 80, 14, 1.0))
        assertNull(detector.detect(candles.take(6), 5, Direction.BULLISH, 80, 14, 1.0))
        assertNull("a lookback below the floor is refused", detector.detect(candles, candles.lastIndex, Direction.BULLISH, 1, 14, 1.0))
    }

    // ------------------------------------------------------------------
    // The gate inside LitEngine
    // ------------------------------------------------------------------

    @Test
    fun `the gate never perturbs the sequence upstream of a signal`() {
        // The whole risk of adding a confirmation late in a long chain is that
        // it leaks backwards and changes structure detection. It cannot: for
        // every prefix that does not produce a signal, the gated and ungated
        // analyses must be identical, stage, context and narrative included.
        val on = LitConfig(requirePoiDivergence = true).sanitized()
        val off = LitConfig(requirePoiDivergence = false).sanitized()

        var compared = 0
        for (seed in listOf(1, 2, 3)) {
            val candles = randomWalk(900, seed)
            for (cutoff in 80..candles.size step 3) {
                val prefix = candles.subList(0, cutoff)
                val ungated = engine.analyze("EURUSD", Timeframe.M15, prefix, off)
                if (ungated.signal != null) continue
                val gated = engine.analyze("EURUSD", Timeframe.M15, prefix, on)
                assertEquals("the gate changed a non-signal analysis at $cutoff/$seed", ungated, gated)
                compared++
            }
        }
        assertTrue("the invariant must actually be exercised", compared > 500)
    }

    @Test
    fun `requiring divergence can only remove signals, never add them`() {
        // The gate is a filter on an existing setup, so anything it admits must
        // already have been admitted without it. If that inverts, the gate is
        // creating setups rather than confirming them.
        val on = LitConfig(requirePoiDivergence = true).sanitized()
        val off = LitConfig(requirePoiDivergence = false).sanitized()

        for (seed in listOf(1, 2, 3, 4)) {
            val candles = randomWalk(900, seed)
            for (cutoff in 80..candles.size step 3) {
                val prefix = candles.subList(0, cutoff)
                val gated = engine.analyze("EURUSD", Timeframe.M15, prefix, on).signal ?: continue
                val ungated = engine.analyze("EURUSD", Timeframe.M15, prefix, off).signal
                assertNotNull("the gate admitted a setup the base sequence rejected", ungated)
                assertEquals(ungated, gated.copy(confirmations = ungated!!.confirmations))
            }
        }
    }

    // The gate's own admit/reject outcomes are covered by the detector tests
    // above rather than through analyze(). The full LiT sequence — IDM, BOS,
    // CHOCH, aligned displacement, a post-shift POI and a first mitigation
    // landing exactly on the newest bar — is selective enough that no synthetic
    // series reaches it: random walks across many seeds, engineered waves
    // swept over amplitude/drift/wick, and series with a retest bar built
    // directly from the detected POI zone all stop at POI_READY. No test in
    // this repository asserts a non-null LiT signal for the same reason. What
    // is testable here is that the gate cannot disturb anything before that
    // point, which the two tests above check across hundreds of prefixes.

    @Test
    fun `the gate leaves the decision a pure function of the prefix`() {
        val on = LitConfig(requirePoiDivergence = true).sanitized()
        val candles = randomWalk(600, seed = 5)

        for (cutoff in 80..candles.size step 7) {
            val prefix = candles.subList(0, cutoff)
            val first = engine.analyze("EURUSD", Timeframe.M15, prefix, on)
            val second = engine.analyze("EURUSD", Timeframe.M15, ArrayList(prefix), on)
            assertEquals("analysis is not a pure function of the prefix at $cutoff", first, second)
        }
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    @Test
    fun `the shipped defaults are the requested behaviour`() {
        val cfg = LitConfig()
        assertTrue("POI entries must require divergence by default", cfg.requirePoiDivergence)
        assertTrue("historical arrows must be kept by default", cfg.historicalSignals)
        assertEquals("the live window is the last 400 candles", 400, cfg.liveWindowBars)
    }

    @Test
    fun `the new settings are clamped to usable ranges`() {
        val wild = LitConfig(
            poiDivergenceLookbackBars = -5,
            poiDivergenceRsiPeriod = 9_999,
            poiDivergenceMinRsiGap = -3.0,
            liveWindowBars = 1,
        ).sanitized()

        assertTrue(wild.poiDivergenceLookbackBars >= 10)
        assertTrue(wild.poiDivergenceRsiPeriod <= 100)
        assertTrue(wild.poiDivergenceMinRsiGap >= 0.0)
        assertTrue(wild.liveWindowBars >= 20)
    }

    @Test
    fun `legacy settings cannot disable history or change the live window`() {
        val migrated = LitConfig(
            historicalSignals = false,
            liveWindowBars = 100,
        ).asLitMayMadnessSignalConfig()

        assertTrue(migrated.historicalSignals)
        assertEquals(400, migrated.liveWindowBars)
    }

    @Test
    fun `live analysis selects exactly the newest 400 closed candles`() {
        assertEquals(0, litMayMadnessWindowStart(399))
        assertEquals(0, litMayMadnessWindowStart(400))
        assertEquals(250, litMayMadnessWindowStart(650))
    }

    @Test
    fun `presets keep the divergence confirmation on`() {
        SignalProfile.entries.forEach { profile ->
            assertTrue(
                "preset $profile silently dropped the confirmation",
                LitConfig.preset(profile).requirePoiDivergence,
            )
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Two declines into two lows, the second lower in price than the first.
     *
     * Which leg is choppy decides what RSI does. A monotonic run drives RSI
     * toward its floor whatever the slope, because the average gain decays to
     * zero; a choppy grind keeps some gain in the average and leaves RSI well
     * above it. So a sharp first leg and a grinding second leg gives a deeper
     * low at higher RSI — a divergence — and swapping them gives a deeper low
     * at lower RSI, which is momentum confirming the move.
     */
    private fun divergenceSeries(bullish: Boolean, rsiConfirms: Boolean): List<Candle> {
        val out = ArrayList<Candle>()
        var price = 100.0
        val sign = if (bullish) -1.0 else 1.0

        fun push() {
            val open = out.lastOrNull()?.close ?: price
            out += Candle(
                timestamp = 1_700_000_000_000L + out.size * 15 * 60_000L,
                open = open,
                high = maxOf(open, price) + 0.05,
                low = minOf(open, price) - 0.05,
                close = price,
                volume = 1_000.0,
            )
        }

        fun sharp(bars: Int) = repeat(bars) { price += sign * 1.0; push() }
        fun choppy(pairs: Int) = repeat(pairs) {
            price += sign * 1.2
            push()
            price -= sign * 0.8
            push()
        }

        // Warmup: enough settled bars for RSI to be defined and near neutral.
        repeat(30) { price += if (it % 2 == 0) 0.05 else -0.05; push() }

        if (rsiConfirms) choppy(18) else sharp(12)
        val firstExtreme = price

        // Recovery, which also confirms the first pivot and lifts RSI back up.
        repeat(14) { price -= sign * 0.5; push() }

        if (rsiConfirms) sharp(14) else choppy(22)

        check(if (bullish) price < firstExtreme else price > firstExtreme) {
            "fixture must extend past the first extreme (price=$price first=$firstExtreme)"
        }
        return out
    }

    private fun tailNoise(from: Candle, count: Int): List<Candle> = (1..count).map { i ->
        val close = from.close + i * 0.02
        Candle(
            timestamp = from.timestamp + i * 15 * 60_000L,
            open = close - 0.01,
            high = close + 0.05,
            low = close - 0.05,
            close = close,
            volume = 1_000.0,
        )
    }

    private fun randomWalk(size: Int, seed: Int): List<Candle> {
        val random = kotlin.random.Random(seed)
        var price = 100.0
        return (0 until size).map { index ->
            val drift = (random.nextDouble() - 0.5) * 1.2
            val open = price
            val close = open + drift
            price = close
            val wick = abs(drift) * 0.7 + 0.05
            Candle(
                timestamp = 1_700_000_000_000L + index * 15 * 60_000L,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0 + index,
            )
        }
    }
}
