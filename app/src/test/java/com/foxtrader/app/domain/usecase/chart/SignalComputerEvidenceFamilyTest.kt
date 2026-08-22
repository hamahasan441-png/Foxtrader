package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.usecase.signalintel.SignalEvidenceReducer
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalComputerEvidenceFamilyTest {
    private val computer = SignalComputer(SignalEvidenceReducer())
    private val candles = (0 until 5).map { index ->
        val close = 100.0 + index
        Candle(
            timestamp = 1_700_000_000_000L + index * 60_000L,
            open = close - 0.2,
            high = close + 0.5,
            low = close - 0.5,
            close = close,
            volume = 100.0,
        )
    }

    @Test
    fun `litx lit and sms do not boost each other on chart`() {
        val index = candles.lastIndex
        val signals = listOf(
            directional("litx", SignalSource.LITX, 0.72, index),
            directional("lit", SignalSource.LIT, 0.76, index),
            context("sms", SignalSource.SMS, 0.70, index),
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = signals,
            latestConfirmedIndex = index,
        )

        assertEquals(0.72, result.first { it.id == "litx" }.confidence, 1e-12)
        assertEquals(0.76, result.first { it.id == "lit" }.confidence, 1e-12)
        assertEquals(0.70, result.first { it.id == "sms" }.confidence, 1e-12)
    }

    @Test
    fun `independent smt family can boost lit family`() {
        val index = candles.lastIndex
        val signals = listOf(
            directional("lit", SignalSource.LIT, 0.76, index),
            context("smt", SignalSource.SMT, 0.70, index),
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = signals,
            latestConfirmedIndex = index,
        )

        assertEquals(0.80, result.first { it.id == "lit" }.confidence, 1e-12)
        assertEquals(0.74, result.first { it.id == "smt" }.confidence, 1e-12)
    }

    private fun directional(
        id: String,
        source: SignalSource,
        confidence: Double,
        index: Int,
    ) = ChartSignal(
        id = id,
        source = source,
        direction = Direction.BULLISH,
        entry = candles[index].close,
        sl = candles[index].close - 1.0,
        tp = candles[index].close + 2.0,
        barIndex = index,
        timestamp = candles[index].timestamp,
        confidence = confidence,
        isLive = true,
    )

    private fun context(
        id: String,
        source: SignalSource,
        confidence: Double,
        index: Int,
    ) = ChartSignal(
        id = id,
        source = source,
        direction = Direction.BULLISH,
        entry = candles[index].close,
        sl = 0.0,
        tp = 0.0,
        barIndex = index,
        timestamp = candles[index].timestamp,
        confidence = confidence,
        isLive = true,
    )
}
