package com.foxtrader.app.domain.usecase.portfolio

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.sdk.broker.Position
import com.foxtrader.app.domain.usecase.correlation.CorrelationMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioEngineTest {

    private val engine = PortfolioEngine()

    @Test
    fun `analyze computes long short net exposure and unrealized pnl`() {
        val snapshot = engine.analyze(
            positions = listOf(
                position("EURUSD", Direction.BULLISH, volume = 0.5, price = 1.10, pnl = 250.0),
                position("GBPUSD", Direction.BEARISH, volume = 0.25, price = 1.30, pnl = -100.0),
            ),
            accountEquity = 100_000.0,
        )

        assertEquals(87.5, snapshot.totalExposurePercent, 0.001)
        assertEquals(55.0, snapshot.longExposurePercent, 0.001)
        assertEquals(32.5, snapshot.shortExposurePercent, 0.001)
        assertEquals(22.5, snapshot.netDirectionalExposurePercent, 0.001)
        assertEquals(150.0, snapshot.unrealizedPnl, 0.001)
        assertEquals("EURUSD", snapshot.largestSymbol)
    }

    @Test
    fun `proposed position is included in correlated exposure cluster`() {
        val matrix = CorrelationMatrix.MatrixResult(
            symbols = listOf("EURUSD", "GBPUSD", "USDJPY"),
            matrix = arrayOf(
                doubleArrayOf(1.0, 0.82, 0.10),
                doubleArrayOf(0.82, 1.0, 0.05),
                doubleArrayOf(0.10, 0.05, 1.0),
            ),
            pairs = listOf(
                CorrelationMatrix.CorrelationPair(
                    symbolA = "EURUSD",
                    symbolB = "GBPUSD",
                    correlation = 0.82,
                    strength = CorrelationMatrix.CorrelationStrength.STRONG_POSITIVE,
                    dataPoints = 100,
                ),
                CorrelationMatrix.CorrelationPair(
                    symbolA = "EURUSD",
                    symbolB = "USDJPY",
                    correlation = 0.10,
                    strength = CorrelationMatrix.CorrelationStrength.WEAK,
                    dataPoints = 100,
                ),
            ),
            period = 100,
        )

        val snapshot = engine.analyze(
            positions = listOf(
                position("GBPUSD", Direction.BULLISH, volume = 0.5, price = 1.30, pnl = 0.0),
                position("USDJPY", Direction.BULLISH, volume = 0.5, price = 1.00, pnl = 0.0),
            ),
            accountEquity = 100_000.0,
            correlationMatrix = matrix,
            proposedPosition = ProposedPosition(
                symbol = "EURUSD",
                direction = Direction.BULLISH,
                volume = 0.5,
                entryPrice = 1.10,
            ),
        )

        // Correlated cluster for proposed EURUSD includes EURUSD proposed (55%) + GBPUSD (65%), not USDJPY.
        assertEquals(120.0, snapshot.correlatedExposurePercent, 0.001)
        assertTrue(snapshot.positions.any { it.proposed && it.symbol == "EURUSD" })
    }

    @Test
    fun `warnings flag high concentration and correlated exposure`() {
        val matrix = CorrelationMatrix.MatrixResult(
            symbols = listOf("BTCUSDT", "ETHUSDT"),
            matrix = arrayOf(doubleArrayOf(1.0, 0.9), doubleArrayOf(0.9, 1.0)),
            pairs = listOf(
                CorrelationMatrix.CorrelationPair(
                    symbolA = "BTCUSDT",
                    symbolB = "ETHUSDT",
                    correlation = 0.9,
                    strength = CorrelationMatrix.CorrelationStrength.STRONG_POSITIVE,
                    dataPoints = 100,
                )
            ),
            period = 100,
        )

        // Crypto is sized at 1 coin per unit (contract size 1), so exposure is
        // volume * price. A heavily-leveraged book: 8 BTC @ 50k = 400% of a 100k
        // account, 120 ETH @ 2.5k = 300% -> 700% total, 400% single-symbol, and
        // a fully-correlated (0.9) 700% cluster. Each trips its warning.
        val snapshot = engine.analyze(
            positions = listOf(
                position("BTCUSDT", Direction.BULLISH, volume = 8.0, price = 50_000.0, pnl = 0.0),
                position("ETHUSDT", Direction.BULLISH, volume = 120.0, price = 2_500.0, pnl = 0.0),
            ),
            accountEquity = 100_000.0,
            correlationMatrix = matrix,
        )

        // 8 * 50_000 / 100_000 = 400%, 120 * 2_500 / 100_000 = 300%.
        assertEquals(700.0, snapshot.totalExposurePercent, 0.001)
        assertEquals(400.0, snapshot.largestSymbolExposurePercent, 0.001)
        assertTrue(snapshot.warnings.any { it.contains("total exposure", ignoreCase = true) })
        assertTrue(snapshot.warnings.any { it.contains("Concentrated", ignoreCase = true) })
        assertTrue(snapshot.warnings.any { it.contains("correlated", ignoreCase = true) })
    }

    private fun position(
        symbol: String,
        direction: Direction,
        volume: Double,
        price: Double,
        pnl: Double,
    ): Position = Position(
        symbol = symbol,
        direction = direction,
        volume = volume,
        entryPrice = price,
        currentPrice = price,
        unrealizedPnl = pnl,
    )
}
