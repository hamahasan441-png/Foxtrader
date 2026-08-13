package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.risk.RiskEngine
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
 * StateFlows. Also verifies the mandatory risk gate: an order whose volume
 * would exceed the configured risk % is rejected (with reasons) and never
 * reaches [PaperBroker]. Uses real collaborators + `runBlocking`; no mocking.
 */
class PaperTradingSessionTest {

    private lateinit var session: PaperTradingSession

    @Before
    fun setup() {
        session = buildSession(RiskEngine(InstrumentTypeResolver()).apply { reset() })
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
        assertTrue(session.buy("EURUSD", 0.1).accepted)
        val account = session.account.value
        assertEquals(1, account.positions.size)
        assertEquals(Direction.BULLISH, account.positions[0].direction)
        assertEquals(1.1000, account.positions[0].entryPrice, 1e-9)
    }

    @Test
    fun `buy without a fed price is rejected and opens nothing`() = runBlocking {
        assertFalse(session.buy("EURUSD", 0.1).accepted)
        assertTrue(session.account.value.positions.isEmpty())
    }

    @Test
    fun `sell opens a short position`() = runBlocking {
        session.onPrice("EURUSD", 1.1000)
        assertTrue(session.sell("EURUSD", 0.1).accepted)
        assertEquals(Direction.BEARISH, session.account.value.positions[0].direction)
    }

    @Test
    fun `accepted order surfaces the risk-computed filled volume`() = runBlocking {
        session.onPrice("EURUSD", 1.1000)
        val result = session.buy("EURUSD", 0.1)
        assertTrue(result.accepted)
        assertTrue(result.rejectionReasons.isEmpty())
        // The volume that actually filled comes from the risk-adjusted sizing,
        // not from re-reading the free-typed input.
        assertEquals(result.sizing?.volume ?: 0.0, session.account.value.positions[0].volume, 1e-9)
    }

    @Test
    fun `trade exceeding the configured risk percent is rejected and never reaches the broker`() = runBlocking {
        // Configure a tight risk budget: 1% of a 1_000 balance = 10 currency
        // units max per trade. A 50-lot EURUSD order with a 0.5% stop is far
        // beyond that.
        val riskEngine = RiskEngine(InstrumentTypeResolver()).apply {
            reset()
            updateConfig(RiskConfig(accountBalance = 1_000.0, riskPercentPerTrade = 1.0))
            updateBalance(1_000.0)
        }
        // Wire a broker to observe whether the gate leaked through.
        val broker = PaperBroker(PaperTradingEngine(InstrumentTypeResolver()))
        val spySession = PaperTradingSession(broker, RiskGatedBrokerExecutor(riskEngine))

        spySession.onPrice("EURUSD", 1.1000)
        val result = spySession.buy("EURUSD", 50.0)

        assertFalse(result.accepted)
        assertTrue(result.rejectionReasons.isNotEmpty())
        assertTrue(result.rejectionReasons.any { it.contains("exceeds per-trade limit") })
        // The risk gate must stop the order before it reaches the broker.
        assertTrue(spySession.account.value.positions.isEmpty())
        assertTrue(broker.snapshot().positions.isEmpty())
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

    private fun buildSession(riskEngine: RiskEngine): PaperTradingSession {
        val broker = PaperBroker(PaperTradingEngine(InstrumentTypeResolver()))
        return PaperTradingSession(broker, RiskGatedBrokerExecutor(riskEngine))
    }
}
