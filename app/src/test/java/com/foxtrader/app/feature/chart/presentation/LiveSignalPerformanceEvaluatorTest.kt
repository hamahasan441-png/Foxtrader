package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSignalPerformanceEvaluatorTest {
    private val evaluator = LiveSignalPerformanceEvaluator()

    @Test
    fun `risk bounded win is counted only after confirmation bar`() {
        val candles = listOf(
            candle(0, 100.0, 101.0, 99.0, 100.0),
            candle(1, 100.0, 111.0, 99.0, 110.0),
        )
        val stats = evaluator.evaluate(
            signals = listOf(riskSignal(index = 0, timestamp = candles[0].timestamp)),
            candles = candles,
            timeframe = Timeframe.M1,
        )

        assertEquals(1, stats.totalObserved)
        assertEquals(1, stats.wins)
        assertEquals(0, stats.losses)
        assertEquals(1, stats.decided)
        assertEquals(100.0, stats.winRatePercent ?: -1.0, 1e-9)
    }

    @Test
    fun `same bar stop and target resolves conservatively as loss`() {
        val candles = listOf(
            candle(0, 100.0, 101.0, 99.0, 100.0),
            candle(1, 100.0, 111.0, 94.0, 100.0),
        )
        val stats = evaluator.evaluate(
            signals = listOf(riskSignal(index = 0, timestamp = candles[0].timestamp)),
            candles = candles,
            timeframe = Timeframe.M1,
        )

        assertEquals(0, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(0.0, stats.winRatePercent ?: -1.0, 1e-9)
    }

    @Test
    fun `binary three minute rule uses next open and third close`() {
        val candles = listOf(
            candle(0, 100.0, 100.5, 99.5, 100.0),
            candle(1, 100.0, 100.4, 99.7, 99.8),
            candle(2, 99.8, 100.1, 99.5, 99.9),
            candle(3, 99.9, 101.3, 99.8, 101.0),
        )
        val signal = ChartSignal(
            id = "b3",
            source = SignalSource.BINARY3M,
            direction = Direction.BULLISH,
            entry = 100.0,
            sl = 0.0,
            tp = 0.0,
            barIndex = 0,
            timestamp = candles[0].timestamp,
            confidence = 0.8,
            isLive = false,
        )

        val stats = evaluator.evaluate(listOf(signal), candles, Timeframe.M1)

        assertEquals(1, stats.wins)
        assertEquals(1, stats.decided)
        assertEquals(100.0, stats.winRatePercent ?: -1.0, 1e-9)
    }

    @Test
    fun `binary without expiry bar stays unresolved not a loss`() {
        val candles = listOf(
            candle(0, 100.0, 100.5, 99.5, 100.0),
            candle(1, 100.0, 100.2, 99.8, 100.1),
        )
        val signal = ChartSignal(
            id = "b3-open",
            source = SignalSource.BINARY3M,
            direction = Direction.BULLISH,
            entry = 100.0,
            sl = 0.0,
            tp = 0.0,
            barIndex = 0,
            timestamp = candles[0].timestamp,
            confidence = 0.8,
            isLive = false,
        )

        val stats = evaluator.evaluate(listOf(signal), candles, Timeframe.M1)

        assertEquals(1, stats.unresolved)
        assertEquals(0, stats.decided)
        assertNull(stats.winRatePercent)
    }

    @Test
    fun `context only signal is explicit not evaluable`() {
        val candles = listOf(candle(0, 100.0, 101.0, 99.0, 100.0))
        val signal = ChartSignal(
            id = "smt",
            source = SignalSource.SMT,
            direction = Direction.BULLISH,
            entry = 100.0,
            sl = 0.0,
            tp = 0.0,
            barIndex = 0,
            timestamp = candles[0].timestamp,
            confidence = 0.7,
            isLive = false,
        )

        val stats = evaluator.evaluate(listOf(signal), candles, Timeframe.M1)

        assertEquals(1, stats.notEvaluable)
        assertEquals(0, stats.evaluable)
        assertTrue(stats.decided == 0)
        assertNull(stats.winRatePercent)
    }

    private fun riskSignal(index: Int, timestamp: Long) = ChartSignal(
        id = "risk-$index",
        source = SignalSource.TRADEPRO,
        direction = Direction.BULLISH,
        entry = 100.0,
        sl = 95.0,
        tp = 110.0,
        barIndex = index,
        timestamp = timestamp,
        confidence = 0.8,
        isLive = false,
    )

    private fun candle(minute: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        timestamp = 60_000L * (minute + 1),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 100.0,
    )
}
