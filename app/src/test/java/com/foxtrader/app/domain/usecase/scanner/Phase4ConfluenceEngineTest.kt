package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4ConfluenceEngineTest {
    private val engine = Phase4ConfluenceEngine(SmtDivergenceDetector())

    private fun trend(count: Int, step: Double): List<Candle> = List(count) { i ->
        val open = 100.0 + i * step
        val close = open + step * 0.7
        Candle(
            timestamp = i * 3_600_000L,
            open = open,
            high = maxOf(open, close) + 0.3,
            low = minOf(open, close) - 0.3,
            close = close,
            volume = 100.0 + i,
        )
    }

    private fun baseResult() = ScreenerResult(
        symbol = "EURUSD",
        assetClass = AssetClass.FOREX,
        strategy = StrategyType.CONFLUENCE,
        direction = Direction.BULLISH,
        score = 72,
        bias = Bias.BULLISH,
        trendStrength = 60.0,
        momentum = 60.0,
        volatility = 30.0,
        setupQuality = 70.0,
        categories = emptyList(),
        tags = emptyList(),
        lastPrice = 1.10,
        changePercent = 0.5,
        riskLevel = ScannerRiskLevel.LOW,
    )

    @Test
    fun `aligned higher timeframes promote a good base scan to actionable`() {
        val result = engine.enrich(
            base = baseResult(),
            baseCandles = trend(120, 0.1),
            higherTimeframeCandles = mapOf(
                Timeframe.H4 to trend(90, 0.4),
                Timeframe.D1 to trend(90, 1.0),
            ),
            correlatedCandles = emptyMap(),
            dataTrustworthy = true,
        )

        assertEquals(1.0, result.mtfAlignment, 1e-9)
        assertTrue(result.actionable)
        assertTrue(result.score > 72)
        assertTrue(result.tags.any { it.contains("P4 confirmed") })
    }

    @Test
    fun `untrusted data can never become actionable`() {
        val result = engine.enrich(
            base = baseResult(),
            baseCandles = trend(120, 0.1),
            higherTimeframeCandles = mapOf(Timeframe.H4 to trend(90, 0.4)),
            correlatedCandles = emptyMap(),
            dataTrustworthy = false,
        )

        assertFalse(result.actionable)
        assertEquals(0.25, result.riskMultiplier, 1e-9)
        assertTrue(result.tags.any { it.contains("data blocked") })
    }

    @Test
    fun `fresh SMT divergence is surfaced as a confirmation`() {
        val primary = divergencePrimary()
        val peer = divergencePeerLowerHigh()
        val bearishBase = baseResult().copy(direction = Direction.BEARISH, bias = Bias.BEARISH)

        val result = engine.enrich(
            base = bearishBase,
            baseCandles = primary,
            higherTimeframeCandles = mapOf(
                Timeframe.H4 to trend(90, -0.4),
                Timeframe.D1 to trend(90, -1.0),
            ),
            correlatedCandles = mapOf("GBPUSD" to peer),
            dataTrustworthy = true,
        )

        assertTrue(result.smtConfirmed)
        assertEquals("GBPUSD", result.smtPeer)
        assertTrue(result.tags.any { it.contains("SMT GBPUSD") })
    }

    private fun divergencePrimary(): List<Candle> =
        (0 until 80).map { i ->
            val close = 100.0 + i * 0.08 + if (i % 2 == 0) 0.02 else -0.01
            val high = when (i) {
                30 -> 110.0
                60 -> 111.0
                else -> close + 0.25
            }
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = high,
                low = close - 0.25,
                close = close,
                volume = 100.0,
            )
        }

    private fun divergencePeerLowerHigh(): List<Candle> =
        (0 until 80).map { i ->
            val close = 100.0 + i * 0.08 + if (i % 2 == 0) 0.02 else -0.01
            val high = when (i) {
                30 -> 110.0
                60 -> 109.5
                else -> close + 0.25
            }
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = high,
                low = close - 0.25,
                close = close,
                volume = 100.0,
            )
        }

    @Test
    fun `peer resolver uses explicit market relationships`() {
        assertTrue("GBPUSD" in Phase4SmtPeerResolver.peersFor("EURUSD"))
        assertTrue("ETHUSDT" in Phase4SmtPeerResolver.peersFor("BTCUSDT"))
        assertTrue(Phase4SmtPeerResolver.peersFor("UNKNOWN").isEmpty())
    }
}
