package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
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
import kotlin.math.sin

/**
 * Contract tests for [LiveStrategyEngine] — the bridge that turns backtestable
 * strategies into chart-renderable signals.
 */
class LiveStrategyEngineTest {

    private val library = StrategyLibrary(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        ichimokuCloud = IchimokuCloud(),
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
    )

    private val engine = LiveStrategyEngine(library)

    private fun trendingSeries(size: Int = 300): List<Candle> = (0 until size).map { i ->
        val trend = 100.0 + i * 0.3
        val noise = sin(i / 4.0) * 2.0
        val open = trend + noise
        val close = trend + noise + 0.5
        Candle(
            timestamp = 1_000L + i * 3_600_000L,
            open = open,
            high = maxOf(open, close) + 1.5,
            low = minOf(open, close) - 1.5,
            close = close,
            volume = 1000.0 + (i % 7) * 200.0,
        )
    }

    /**
     * Guard test. Several assertions below iterate over the produced signals,
     * so if the fixture ever stopped generating any they would pass vacuously.
     * This pins the fixture as signal-producing.
     */
    @Test
    fun `fixture produces signals so the assertions below are meaningful`() {
        val signals = engine.evaluate(StrategyType.CONFLUENCE, trendingSeries())
        assertTrue("fixture must produce signals", signals.isNotEmpty())
    }

    @Test
    fun `returns empty when candle series is empty`() {
        val result = engine.evaluate(StrategyType.CONFLUENCE, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty when there are fewer bars than the strategy warm-up`() {
        val minimumBars = library.get(StrategyType.CONFLUENCE).minimumBars
        val result = engine.evaluate(StrategyType.CONFLUENCE, trendingSeries(minimumBars - 1))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `every emitted signal is tagged as a STRATEGY signal carrying its name`() {
        val expectedName = library.get(StrategyType.CONFLUENCE).name
        val signals = engine.evaluate(StrategyType.CONFLUENCE, trendingSeries())

        for (signal in signals) {
            assertEquals(SignalSource.STRATEGY, signal.source)
            assertEquals(expectedName, signal.label)
        }
    }

    @Test
    fun `only a setup on the current bar can be live`() {
        val candles = trendingSeries()
        val signals = engine.evaluate(StrategyType.CONFLUENCE, candles)
        assertTrue(signals.isNotEmpty())

        assertTrue(signals.count { it.isLive } <= 1)
        signals.singleOrNull { it.isLive }?.let { live ->
            assertEquals(candles.lastIndex, live.barIndex)
        }
    }

    @Test
    fun `newest historical custom setup is not mislabeled live`() {
        val candles = trendingSeries()
        val historicalIndex = candles.lastIndex - 12
        val signals = engine.evaluateCustom(
            strategyId = "history_only",
            strategyName = "History only",
            minimumBars = 20,
            function = { series, index ->
                if (index != historicalIndex) return@evaluateCustom null
                val entry = series[index].close
                StrategySignal(
                    index = index,
                    timestamp = series[index].timestamp,
                    direction = Direction.BULLISH,
                    entry = entry,
                    stopLoss = entry - 1.0,
                    takeProfit = entry + 2.0,
                )
            },
            candles = candles,
        )

        assertEquals(1, signals.size)
        assertTrue(signals.none { it.isLive })
    }

    @Test
    fun `custom setup on current forming bar is live`() {
        val candles = trendingSeries()
        val liveIndex = candles.lastIndex
        val signals = engine.evaluateCustom(
            strategyId = "current_bar",
            strategyName = "Current bar",
            minimumBars = 20,
            function = { series, index ->
                if (index != liveIndex) return@evaluateCustom null
                val entry = series[index].close
                StrategySignal(
                    index = index,
                    timestamp = series[index].timestamp,
                    direction = Direction.BEARISH,
                    entry = entry,
                    stopLoss = entry + 1.0,
                    takeProfit = entry - 2.0,
                )
            },
            candles = candles,
        )

        assertEquals(1, signals.size)
        assertTrue(signals.single().isLive)
    }

    @Test
    fun `custom strategy signals with stop and target on wrong sides are rejected`() {
        val candles = trendingSeries()
        val liveIndex = candles.lastIndex
        val signals = engine.evaluateCustom(
            strategyId = "invalid_risk",
            strategyName = "Invalid risk",
            minimumBars = 20,
            function = { series, index ->
                if (index != liveIndex) return@evaluateCustom null
                val entry = series[index].close
                StrategySignal(
                    index = index,
                    timestamp = series[index].timestamp,
                    direction = Direction.BULLISH,
                    entry = entry,
                    stopLoss = entry + 1.0,
                    takeProfit = entry - 2.0,
                )
            },
            candles = candles,
        )

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `custom strategy receives no future candles`() {
        val candles = trendingSeries(80)
        var calls = 0

        engine.evaluateCustom(
            strategyId = "no_lookahead",
            strategyName = "No lookahead",
            minimumBars = 20,
            function = { visible, index ->
                calls++
                assertEquals(index + 1, visible.size)
                null
            },
            candles = candles,
            scanWindow = candles.size,
        )

        assertTrue(calls > 0)
    }

    @Test
    fun `signals are ordered by bar index and stay inside the series`() {
        val candles = trendingSeries()
        val signals = engine.evaluate(StrategyType.CONFLUENCE, candles)

        var previous = -1
        for (signal in signals) {
            assertTrue("bar index must be ascending", signal.barIndex > previous)
            assertTrue("bar index must be within the series", signal.barIndex in candles.indices)
            previous = signal.barIndex
        }
    }

    @Test
    fun `signals always carry a non-zero risk distance and normalised confidence`() {
        val signals = engine.evaluate(StrategyType.CONFLUENCE, trendingSeries())

        for (signal in signals) {
            assertTrue("entry must be positive", signal.entry > 0.0)
            assertTrue(
                "entry and stop must differ so risk is computable",
                kotlin.math.abs(signal.entry - signal.sl) > 0.0,
            )
            assertTrue("confidence must be normalised", signal.confidence in 0.0..1.0)
        }
    }

    @Test
    fun `never emits more than the requested maximum`() {
        val signals = engine.evaluate(
            type = StrategyType.CONFLUENCE,
            candles = trendingSeries(400),
            maxSignals = 5,
        )
        assertTrue(signals.size <= 5)
    }

    @Test
    fun `is non-repainting - historical markers do not move when a new bar arrives`() {
        val candles = trendingSeries(320)
        val before = engine.evaluate(StrategyType.CONFLUENCE, candles)

        // Append one bar, exactly as a live feed would.
        val last = candles.last()
        val extended = candles + last.copy(
            timestamp = last.timestamp + 3_600_000L,
            open = last.close,
            high = last.close + 1.0,
            low = last.close - 1.0,
            close = last.close + 0.4,
        )
        val after = engine.evaluate(StrategyType.CONFLUENCE, extended)

        // Every signal on a bar that had already closed must be unchanged in
        // direction and price levels. Only the previously-live bar may change.
        val settledBefore = before.filter { it.barIndex < candles.lastIndex }
        val afterByIndex = after.associateBy { it.barIndex }

        for (old in settledBefore) {
            val new = afterByIndex[old.barIndex] ?: continue
            assertEquals("direction changed at bar ${old.barIndex}", old.direction, new.direction)
            assertEquals("entry moved at bar ${old.barIndex}", old.entry, new.entry, 1e-9)
            assertEquals("stop moved at bar ${old.barIndex}", old.sl, new.sl, 1e-9)
            assertEquals("target moved at bar ${old.barIndex}", old.tp, new.tp, 1e-9)
        }
    }

    @Test
    fun `all registered strategies can be evaluated without throwing`() {
        val candles = trendingSeries()
        for (type in StrategyType.entries) {
            // Must not throw for any strategy, even ones with no setup present.
            val signals = engine.evaluate(type, candles)
            for (signal in signals) {
                assertEquals(SignalSource.STRATEGY, signal.source)
            }
        }
    }
}
