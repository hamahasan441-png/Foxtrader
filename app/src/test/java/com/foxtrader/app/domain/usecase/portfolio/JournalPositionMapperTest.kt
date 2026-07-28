package com.foxtrader.app.domain.usecase.portfolio

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Journal -> position mapping.
 *
 * The subtle risks here are (a) counting closed trades as open exposure, and
 * (b) inventing a P&L number when no live price is known.
 */
class JournalPositionMapperTest {

    private val mapper = JournalPositionMapper(InstrumentTypeResolver())

    private fun entry(
        id: String,
        symbol: String,
        direction: Direction = Direction.BULLISH,
        entryPrice: Double = 1.1000,
        exitPrice: Double? = null,
        volume: Double = 1.0,
    ) = JournalEntry(
        id = id,
        symbol = symbol,
        direction = direction,
        timeframe = Timeframe.H1,
        entryPrice = entryPrice,
        exitPrice = exitPrice,
        stopLoss = entryPrice - 0.01,
        takeProfit = entryPrice + 0.02,
        volume = volume,
        entryTime = 1_000L,
        exitTime = if (exitPrice != null) 2_000L else null,
        pnl = if (exitPrice != null) 100.0 else null,
        rMultiple = null,
        setupType = "BOS",
    )

    @Test
    fun `closed trades are excluded from open exposure`() {
        val positions = mapper.toPositions(
            listOf(
                entry("open", "EURUSD"),
                entry("closed", "GBPUSD", exitPrice = 1.2500),
            )
        )
        assertEquals(1, positions.size)
        assertEquals("EURUSD", positions.first().symbol)
    }

    @Test
    fun `symbols are normalised to uppercase`() {
        val positions = mapper.toPositions(listOf(entry("1", "eurusd")))
        assertEquals("EURUSD", positions.first().symbol)
    }

    @Test
    fun `missing live price falls back to entry price with zero pnl`() {
        // Inventing a mark price would fabricate P&L. Zero is the honest answer.
        val positions = mapper.toPositions(listOf(entry("1", "EURUSD", entryPrice = 1.1000)))
        val p = positions.first()
        assertEquals(1.1000, p.currentPrice, 1e-9)
        assertEquals(0.0, p.unrealizedPnl, 1e-9)
    }

    @Test
    fun `non-positive live price is ignored`() {
        val positions = mapper.toPositions(
            listOf(entry("1", "EURUSD", entryPrice = 1.1000)),
            livePrices = mapOf("EURUSD" to 0.0),
        )
        assertEquals(1.1000, positions.first().currentPrice, 1e-9)
        assertEquals(0.0, positions.first().unrealizedPnl, 1e-9)
    }

    @Test
    fun `long position profits when price rises`() {
        val positions = mapper.toPositions(
            listOf(entry("1", "EURUSD", Direction.BULLISH, entryPrice = 1.1000, volume = 1.0)),
            livePrices = mapOf("EURUSD" to 1.1010),
        )
        // 0.0010 * 1.0 lot * 100_000 contract = 100.0
        assertEquals(100.0, positions.first().unrealizedPnl, 1e-6)
    }

    @Test
    fun `short position profits when price falls`() {
        val positions = mapper.toPositions(
            listOf(entry("1", "EURUSD", Direction.BEARISH, entryPrice = 1.1000, volume = 1.0)),
            livePrices = mapOf("EURUSD" to 1.0990),
        )
        assertEquals(100.0, positions.first().unrealizedPnl, 1e-6)
    }

    @Test
    fun `short position loses when price rises`() {
        val positions = mapper.toPositions(
            listOf(entry("1", "EURUSD", Direction.BEARISH, entryPrice = 1.1000, volume = 1.0)),
            livePrices = mapOf("EURUSD" to 1.1010),
        )
        assertTrue(positions.first().unrealizedPnl < 0.0)
    }

    @Test
    fun `empty journal yields no positions`() {
        assertTrue(mapper.toPositions(emptyList()).isEmpty())
    }
}
