package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PaperTradingSession] — the reactive coordinator over
 * [PaperBroker]. Verifies price feed → market snapshot, one-tap buy/sell,
 * fail-fast without a price, close, and reset, all reflected in the exposed
 * StateFlows. Uses real collaborators + `runBlocking`; no mocking.
 */
class PaperTradingSessionTest {

    private lateinit var session: PaperTradingSession

    @Before
    fun setup() {
        session = PaperTradingSession(PaperBroker(PaperTradingEngine(InstrumentTypeResolver())))
    }

    @Test
    fun `onPrice publishes the latest market snapshot`() = runBlocking {
        session.onPrice("EURUSD", 1.1000)
        val market = session.market.value
        assertEquals("EURUSD", market?.symbol)
        assertEquals(1.1000, market?.price ?: 0.0, 1e-9)
    }

    @Test
    fun `buy opens a long position at the fed price`() = runBlocking {
        session.onPrice("EURUSD", 1.1000)
        assertTrue(session.buy("EURUSD", 0.1))
        val account = session.account.value
        assertEquals(1, account.positions.size)
        assertEquals(Direction.BULLISH, account.positions[0].direction)
        assertEquals(1.1000, account.positions[0].entryPrice, 1e-9)
    }

    @Test
    fun `buy without a fed price is rejected and opens nothing`() = runBlocking {
        assertFalse(session.buy("EURUSD", 0.1))
        assertTrue(session.account.value.positions.isEmpty())
    }

    @Test
    fun `sell opens a short position`() = runBlocking {
        session.onPrice("EURUSD", 1.1000)
        assertTrue(session.sell("EURUSD", 0.1))
        assertEquals(Direction.BEARISH, session.account.value.positions[0].direction)
    }

    @Test
    fun `close removes the position`() = runBlocking {
        session.onPrice("EURUSD", 1.1000)
        session.buy("EURUSD", 0.1)
        val id = session.account.value.positions[0].id
        assertTrue(session.close(id))
        assertTrue(session.account.value.positions.isEmpty())
    }

    @Test
    fun `reset clears positions and the market snapshot`() = runBlocking {
        session.onPrice("EURUSD", 1.1000)
        session.buy("EURUSD", 0.1)
        session.reset()
        assertTrue(session.account.value.positions.isEmpty())
        assertNull(session.market.value)
    }
}
