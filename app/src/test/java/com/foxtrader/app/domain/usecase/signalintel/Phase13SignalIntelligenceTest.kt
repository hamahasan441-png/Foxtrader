package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Displacement
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.litx.MssClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13SignalIntelligenceTest {
    private val start = 1_700_000_000_000L

    private fun candles(count: Int = 12): List<Candle> = List(count) { i ->
        val open = 1.10 + i * 0.0001
        Candle(start + i * 900_000L, open, open + 0.001, open - 0.001, open + 0.0002, 100.0)
    }

    @Test
    fun `active candle is excluded from confirmed prefix`() {
        val data = candles()
        val now = data.last().timestamp + 5 * 60_000L
        assertEquals(10, ConfirmedBarPolicy.latestConfirmedIndex(data, Timeframe.M15, now))
    }

    @Test
    fun `duplicate timestamp fails candle integrity`() {
        val data = candles().toMutableList()
        data[5] = data[5].copy(timestamp = data[4].timestamp)
        assertFalse(SignalSeriesIntegrity.validate(data, minimumBars = 5).valid)
    }

    @Test
    fun `MSS refuses displacement that happened before structure shift`() {
        val shift = StructureBreak(StructureBreakType.CHOCH, Direction.BULLISH, 1.10, start, 20, true)
        val before = Displacement(Direction.BULLISH, 19, 19, 1.0, 1.1, 0.8, 1.5, true)
        val after = Displacement(Direction.BULLISH, 20, 21, 1.0, 1.1, 0.8, 1.5, true)
        val classifier = MssClassifier()
        assertFalse(classifier.classify(listOf(shift), before).isStrong)
        assertTrue(classifier.classify(listOf(shift), after).isStrong)
    }

    @Test
    fun `fixed expiry binary markers are excluded from SL TP accuracy evaluator`() {
        val data = candles()
        val signal = ChartSignal(
            id = "b3", source = SignalSource.BINARY3M, direction = Direction.BULLISH,
            entry = data[1].close, sl = 0.0, tp = 0.0, barIndex = 1,
            timestamp = data[1].timestamp, confidence = 0.82, isLive = false,
        )
        assertTrue(SignalOutcomeEvaluator().evaluate(listOf(signal), data).isEmpty())
    }

    @Test
    fun `accuracy evaluator counts ambiguous SL and TP candle as loss`() {
        val data = candles().toMutableList()
        data[2] = data[2].copy(open = 1.10, high = 1.13, low = 1.08, close = 1.11)
        val signal = ChartSignal(
            id = "test", source = SignalSource.LITX, direction = Direction.BULLISH,
            entry = 1.10, sl = 1.09, tp = 1.12, barIndex = 1,
            timestamp = data[1].timestamp, confidence = 0.9, isLive = false,
        )
        val result = SignalOutcomeEvaluator().evaluate(listOf(signal), data).single()
        assertEquals(SignalOutcomeEvaluator.Outcome.LOSS, result.outcome)
        assertEquals(-1.0, result.rMultiple!!, 0.0)
    }
}
