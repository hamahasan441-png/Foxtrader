package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.indicators.RsiOrderFlow
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.SignalEvidenceReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalComputerRsiOrderFlowTest {
    private val computer = SignalComputer(SignalEvidenceReducer())

    @Test
    fun `confirmed RSI Orderflow setup becomes one stable risk-bounded chart arrow`() {
        val candles = List(10) { i ->
            val price = 100.0 + i
            Candle(
                timestamp = 1_700_000_000_000L + i * 60_000L,
                open = price - 0.2,
                high = price + 0.8,
                low = price - 0.8,
                close = price,
                volume = 100.0,
            )
        }
        val signal = RsiOrderFlowSignalEngine.Signal(
            symbol = "XAUUSD",
            timeframe = Timeframe.M1,
            direction = Direction.BULLISH,
            divergenceType = RsiOrderFlow.DivergenceType.REGULAR_BULLISH,
            pivotIndex = 6,
            confirmationIndex = 9,
            timestamp = candles[9].timestamp,
            entry = candles[9].close,
            stopLoss = candles[9].close - 2.0,
            takeProfit = candles[9].close + 4.0,
            confidence = 75,
            rsiAtPivot = 42.0,
            flowAtPivot = 55.0,
            positiveVolumeCoverage = 1.0,
            reasons = listOf("confirmed"),
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            rsiOrderFlowSignals = listOf(signal),
            latestConfirmedIndex = 9,
        )

        assertEquals(1, result.size)
        val chart = result.single()
        assertEquals(SignalSource.RSI_ORDERFLOW, chart.source)
        assertEquals(9, chart.barIndex)
        assertEquals(candles[9].timestamp, chart.timestamp)
        assertEquals(Direction.BULLISH, chart.direction)
        assertEquals(0.75, chart.confidence, 1e-9)
        assertTrue(chart.isLive)
        assertTrue(chart.sl < chart.entry)
        assertTrue(chart.tp > chart.entry)
        assertNotNull(chart.eventKey)
        assertTrue(chart.eventKey!!.startsWith("rsi_of_XAUUSD_M1_"))
    }

    @Test
    fun `mismatched confirmation timestamp is rejected`() {
        val candles = List(4) { i ->
            Candle(
                timestamp = 1_700_000_000_000L + i * 60_000L,
                open = 100.0,
                high = 101.0,
                low = 99.0,
                close = 100.0,
                volume = 100.0,
            )
        }
        val signal = RsiOrderFlowSignalEngine.Signal(
            symbol = "TEST",
            timeframe = Timeframe.M1,
            direction = Direction.BEARISH,
            divergenceType = RsiOrderFlow.DivergenceType.REGULAR_BEARISH,
            pivotIndex = 1,
            confirmationIndex = 3,
            timestamp = candles[3].timestamp + 1L,
            entry = 100.0,
            stopLoss = 102.0,
            takeProfit = 96.0,
            confidence = 60,
            rsiAtPivot = 60.0,
            flowAtPivot = 45.0,
            positiveVolumeCoverage = 1.0,
            reasons = emptyList(),
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            rsiOrderFlowSignals = listOf(signal),
            latestConfirmedIndex = 3,
        )

        assertTrue(result.isEmpty())
    }
}
