package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.Candle
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Contract for the LiT Adventure mode comparison.
 *
 * The point of this runner is to make a claim about which rule set performed
 * better, so the tests concentrate on the ways such a claim can be dishonest:
 * ranking a mode on three lucky trades, letting a mode see a bar it could not
 * have seen live, or varying thresholds between modes so the comparison
 * measures presets rather than rules.
 */
class LitXModeComparisonRunnerTest {

    private val runner = LitXModeComparisonRunner(
        litXEngine = LitXEngine(
            smcDetector = SmcDetector(),
            analyzeStructure = AnalyzeMarketStructureUseCase(),
            sessionDetector = SessionDetector(),
            displacementDetector = DisplacementDetector(),
            mitigationDetector = MitigationBlockDetector(),
            premiumDiscount = PremiumDiscountCalculator(),
            mssClassifier = MssClassifier(),
            scorer = LitXConfidenceScorer(),
        ),
        backtestEngine = BacktestEngine(),
        analyticsEngine = BacktestAnalyticsEngine(),
    )

    private val permissive = LitXConfig(
        minGrade = LitXGrade.B,
        minRiskReward = 1.2,
        requireHtfAlignment = false,
        requireStrongMss = false,
        requireDirectionalZone = false,
        minConfidenceScore = 55,
        profile = SignalProfile.INTRADAY,
    )

    @Test
    fun `every mode is reported even when it never traded`() = runBlocking {
        val report = runner(
            candles = walk(seed = 1, bars = 600),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            baseConfig = permissive,
        )
        assertEquals(
            "a mode that produced nothing must still appear, not be silently dropped",
            LitXMode.entries.toSet(),
            report.comparison.map { it.mode }.toSet(),
        )
        assertEquals(600, report.barsAnalyzed)
    }

    @Test
    fun `modes with a thin sample are excluded from the ranking`() = runBlocking {
        val report = runner(
            candles = walk(seed = 2, bars = 500),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            baseConfig = permissive,
        )
        report.ranked.forEach {
            assertTrue(
                "${it.mode} was ranked on ${it.trades} trades",
                it.trades >= LitXModeComparisonRunner.MIN_TRADES_FOR_COMPARISON,
            )
        }
        report.inadequateSample.forEach { mode ->
            val outcome = report.comparison.single { it.mode == mode }
            assertTrue(outcome.trades < LitXModeComparisonRunner.MIN_TRADES_FOR_COMPARISON)
        }
        assertEquals(
            LitXMode.entries.size,
            report.ranked.size + report.inadequateSample.size,
        )
    }

    @Test
    fun `an inconclusive run names no winner`() = runBlocking {
        // Short, flat history: nothing should reach a judgeable sample.
        val flat = (0 until 200).map { i ->
            Candle(BASE + i * 900_000L, 1.1000, 1.1002, 1.0998, 1.1000, 1_000.0)
        }
        val report = runner(
            candles = flat,
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            baseConfig = permissive,
        )
        assertTrue("a flat market cannot produce a judgeable comparison", report.inconclusive)
        assertNull("no winner may be named without evidence", report.winner)
        assertTrue(report.ranked.isEmpty())
    }

    @Test
    fun `the run is deterministic`() = runBlocking {
        val candles = walk(seed = 3, bars = 500)
        val first = runner(candles, "EURUSD", Timeframe.M15, baseConfig = permissive)
        val second = runner(candles, "EURUSD", Timeframe.M15, baseConfig = permissive)
        assertEquals(
            "the same history must produce the same comparison every time",
            first.comparison.associate { it.mode to it.trades },
            second.comparison.associate { it.mode to it.trades },
        )
        assertEquals(first.winner, second.winner)
    }

    @Test
    fun `progress is reported once per mode`() = runBlocking {
        val seen = mutableListOf<Pair<Int, Int>>()
        runner(
            candles = walk(seed = 4, bars = 300),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            baseConfig = permissive,
            onProgress = { done, total -> seen += done to total },
        )
        assertEquals(
            (1..LitXMode.entries.size).map { it to LitXMode.entries.size },
            seen,
        )
    }

    @Test
    fun `a subset of modes can be compared`() = runBlocking {
        val report = runner(
            candles = walk(seed = 5, bars = 300),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            baseConfig = permissive,
            modes = listOf(LitXMode.SNIPER, LitXMode.PRECISION),
        )
        assertEquals(2, report.comparison.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an analysis window too small to confirm a setup is rejected`() {
        runBlocking {
            runner(
                candles = walk(seed = 6, bars = 200),
                symbol = "EURUSD",
                timeframe = Timeframe.M15,
                baseConfig = permissive,
                analysisWindow = 10,
            )
        }
    }

    @Test
    fun `sniper never trades more than precision on the same history`() = runBlocking {
        // SNIPER adds gates and removes none, so on identical thresholds its
        // trade count is bounded by PRECISION's. If this ever inverts, the mode
        // table is not what it claims to be.
        val candles = walk(seed = 7, bars = 600)
        val report = runner(
            candles = candles,
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            baseConfig = permissive,
            modes = listOf(LitXMode.SNIPER, LitXMode.PRECISION),
        )
        val sniper = report.comparison.single { it.mode == LitXMode.SNIPER }.trades
        val precision = report.comparison.single { it.mode == LitXMode.PRECISION }.trades
        assertTrue("SNIPER=$sniper PRECISION=$precision", sniper <= precision)
    }

    @Test
    fun `backtest config is shared identically across modes`() = runBlocking {
        val config = BacktestConfig(initialBalance = 50_000.0, riskPercent = 0.5)
        val report = runner(
            candles = walk(seed = 8, bars = 400),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            backtestConfig = config,
            baseConfig = permissive,
        )
        report.comparison.forEach {
            assertEquals(50_000.0, it.result.config.initialBalance, 1e-9)
            assertEquals(0.5, it.result.config.riskPercent, 1e-9)
        }
    }

    private fun walk(seed: Int, bars: Int): List<Candle> {
        val random = Random(seed)
        val out = ArrayList<Candle>(bars)
        var price = 1.1000
        var drift = 0.00004
        for (i in 0 until bars) {
            if (i % 40 == 0) drift = random.nextDouble(-0.00012, 0.00012)
            val impulse = if (random.nextInt(18) == 0) random.nextDouble(-0.0020, 0.0020) else 0.0
            val open = price
            price += drift + random.nextDouble(-0.0006, 0.0006) + impulse
            val close = price
            out += Candle(
                timestamp = BASE + i * 900_000L,
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
        const val BASE = 1_700_000_000_000L
    }
}
