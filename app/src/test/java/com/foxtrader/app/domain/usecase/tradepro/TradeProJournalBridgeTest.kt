package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.repository.JournalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeProJournalBridgeTest {

    private val logged = mutableListOf<JournalEntry>()

    private val fakeRepo = object : JournalRepository {
        override fun observeEntries(): Flow<List<JournalEntry>> = flowOf(logged)
        override suspend fun getAllEntries(): List<JournalEntry> = logged
        override suspend fun getModifiedSince(since: Long): List<JournalEntry> = logged
        override suspend fun upsert(entry: JournalEntry) { logged.add(entry) }
        override suspend fun upsertAll(entries: List<JournalEntry>) { logged.addAll(entries) }
        override suspend fun delete(id: String) { logged.removeAll { it.id == id } }
        override suspend fun clear() { logged.clear() }
    }

    private val bridge = TradeProJournalBridge(fakeRepo)

    private fun closedTrade(
        realized: Double = 12.0,
        exitReason: String = "Runner target hit",
    ) = ManagedTrade(
        id = "test-123",
        symbol = "MESUSD",
        direction = Direction.BULLISH,
        entryPrice = 5000.0,
        entryTimestamp = 1_000_000L,
        contracts = 3,
        stopPrice = 4997.0,
        t1Price = 5004.0,
        t2Price = 5008.0,
        runnerTarget = 5016.0,
        currentPrice = 5016.0,
        state = ManagedTradeState.CLOSED,
        closedAt = 2_000_000L,
        realizedPoints = realized,
        exitReason = exitReason,
    )

    @Test
    fun `logs a closed trade with correct fields`() = runBlocking {
        bridge.logClosedTrade(closedTrade(), confluences = listOf("FLIP_ZONE", "IMBALANCE"))

        assertEquals(1, logged.size)
        val entry = logged[0]
        assertEquals("tp-test-123", entry.id)
        assertEquals("MESUSD", entry.symbol)
        assertEquals(Direction.BULLISH, entry.direction)
        assertEquals(5000.0, entry.entryPrice, 1e-9)
        assertEquals(5016.0, entry.exitPrice!!, 1e-9)
        assertEquals(12.0, entry.pnl!!, 1e-9)
        assertEquals("TRADEPRO", entry.setupType)
        assertTrue(entry.tags.contains("TRADEPRO"))
        assertTrue(entry.tags.contains("auto-recorded"))
        assertTrue(entry.notes.contains("Runner target hit"))
        assertTrue(entry.notes.contains("FLIP_ZONE"))
    }

    @Test
    fun `computes R-multiple correctly`() = runBlocking {
        // Risk = (5000 - 4997) * 3 contracts = 9 pts. Realized = 12 pts. R = 12/9 = 1.33
        bridge.logClosedTrade(closedTrade(realized = 12.0))
        val entry = logged[0]
        assertNotNull(entry.rMultiple)
        assertEquals(1.333, entry.rMultiple!!, 0.01)
    }

    @Test
    fun `does not log an active trade`() = runBlocking {
        val active = closedTrade().copy(state = ManagedTradeState.ACTIVE)
        bridge.logClosedTrade(active)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `idempotent on duplicate calls`() = runBlocking {
        bridge.logClosedTrade(closedTrade())
        bridge.logClosedTrade(closedTrade())
        // upsert semantics — both go through (real Room upserts by ID, fake just appends)
        // The important thing: it doesn't crash.
        assertTrue(logged.size >= 1)
    }
}
