package com.foxtrader.app.data.sync

import com.foxtrader.app.data.auth.TokenManager
import com.foxtrader.app.data.remote.api.SyncApi
import com.foxtrader.app.data.repository.CloudSyncRepositoryImpl
import com.foxtrader.app.domain.model.AuthResponse
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.LoginRequest
import com.foxtrader.app.domain.model.LogoutRequest
import com.foxtrader.app.domain.model.RefreshRequest
import com.foxtrader.app.domain.model.RegisterRequest
import com.foxtrader.app.domain.model.SyncEnvelope
import com.foxtrader.app.domain.model.SyncPullResponse
import com.foxtrader.app.domain.model.SyncPushRequest
import com.foxtrader.app.domain.model.SyncableType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.DrawingRepository
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.usecase.sync.CloudSyncEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Journal cloud sync end to end against in-memory fakes: pushed envelopes carry
 * the modification stamp, and pulled entries are actually stored.
 */
class SyncManagerTest {

    private class FakeJournal : JournalRepository {
        val entries = linkedMapOf<String, Pair<JournalEntry, Long>>()
        override fun observeEntries(): Flow<List<JournalEntry>> = flowOf(entries.values.map { it.first })
        override suspend fun getAllEntries() = entries.values.map { it.first }
        override suspend fun getModifiedSince(since: Long) =
            entries.values.filter { it.second > since }.map { it.first }
        override suspend fun upsert(entry: JournalEntry) { entries[entry.id] = entry to System.currentTimeMillis() }
        override suspend fun upsertAll(entries: List<JournalEntry>) = entries.forEach { upsert(it) }
        override suspend fun modificationStamps() = entries.mapValues { it.value.second }
        override suspend fun upsertFromSync(entry: JournalEntry, modifiedAt: Long) {
            entries[entry.id] = entry to modifiedAt
        }
        override suspend fun delete(id: String) { entries.remove(id) }
        override suspend fun clear() = entries.clear()
    }

    private class FakeApi : SyncApi {
        val pushed = mutableListOf<SyncEnvelope>()
        var toPull: List<SyncEnvelope> = emptyList()
        var serverTimestamp = 5_000L
        override suspend fun register(request: RegisterRequest): AuthResponse = error("unused")
        override suspend fun login(request: LoginRequest): AuthResponse = error("unused")
        override suspend fun refresh(request: RefreshRequest): AuthResponse = error("unused")
        override suspend fun logout(bearer: String, request: LogoutRequest) {}
        override suspend fun pushSync(request: SyncPushRequest) { pushed += request.items }
        override suspend fun pullSync(since: Long, types: String?) =
            SyncPullResponse(items = toPull, serverTimestamp = serverTimestamp, hasMore = false)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val journal = FakeJournal()
    private val api = FakeApi()
    private val engine = CloudSyncEngine()
    private val drawings = mockk<DrawingRepository> { coEvery { getAll() } returns emptyList() }
    private val tokens = mockk<TokenManager> { every { isLoggedIn() } returns true }
    private val manager = SyncManager(
        journalRepository = journal,
        drawingRepository = drawings,
        cloudSyncRepository = CloudSyncRepositoryImpl(api, engine, tokens, Dispatchers.Unconfined),
        syncEngine = engine,
        json = json,
    )

    private fun entry(id: String, notes: String = "", entryTime: Long = 1_000L) = JournalEntry(
        id = id, symbol = "EURUSD", direction = Direction.BULLISH, timeframe = Timeframe.H1,
        entryPrice = 1.1, exitPrice = null, stopLoss = 1.09, takeProfit = 1.12, volume = 0.1,
        entryTime = entryTime, exitTime = null, pnl = null, rMultiple = null, setupType = "BOS",
        notes = notes,
    )

    private fun remote(e: JournalEntry, updatedAt: Long, deleted: Boolean = false) = SyncEnvelope(
        id = e.id, type = SyncableType.JOURNAL,
        data = json.encodeToString(JournalSyncDto.serializer(), e.toSyncDto()),
        version = 1, updatedAt = updatedAt, deviceId = "other-device", deleted = deleted,
    )

    @Test
    fun `a pulled entry from another device is stored locally`() = runBlocking {
        // Regression: pulled items were counted and discarded.
        api.toPull = listOf(remote(entry("r1", notes = "from phone"), updatedAt = 2_000L))

        val result = manager.syncNow()

        assertTrue(result.success)
        assertEquals(1, result.mergedEntries)
        assertEquals("from phone", journal.entries["r1"]!!.first.notes)
        assertEquals(2_000L, journal.entries["r1"]!!.second)
        assertEquals(5_000L, engine.getLastSyncTime())
    }

    @Test
    fun `a newer local edit is not overwritten by an older remote one`() = runBlocking {
        journal.upsertFromSync(entry("e1", notes = "edited here"), modifiedAt = 3_000L)
        api.toPull = listOf(remote(entry("e1", notes = "stale"), updatedAt = 2_000L))

        manager.syncNow()

        assertEquals("edited here", journal.entries["e1"]!!.first.notes)
    }

    @Test
    fun `a remote deletion newer than the local copy removes it`() = runBlocking {
        journal.upsertFromSync(entry("d1"), modifiedAt = 1_000L)
        api.toPull = listOf(remote(entry("d1"), updatedAt = 2_000L, deleted = true))

        manager.syncNow()

        assertNull(journal.entries["d1"])
    }

    @Test
    fun `a push carries the modification stamp, not the trade time`() = runBlocking {
        // entryTime never changes when notes are edited, so it cannot order edits.
        journal.upsertFromSync(entry("p1", entryTime = 1_000L), modifiedAt = 9_000L)

        manager.syncNow()

        assertEquals(9_000L, api.pushed.single { it.id == "p1" }.updatedAt)
    }

    @Test
    fun `the cursor does not advance when applying pulled items fails`() = runBlocking {
        val failingJournal = object : JournalRepository by journal {
            override suspend fun upsertFromSync(entry: JournalEntry, modifiedAt: Long) =
                throw IllegalStateException("disk full")
        }
        val failing = SyncManager(
            failingJournal, drawings, CloudSyncRepositoryImpl(api, engine, tokens, Dispatchers.Unconfined),
            engine, json,
        )
        api.toPull = listOf(remote(entry("f1"), updatedAt = 2_000L))

        val result = failing.syncNow()

        assertFalse(result.success)
        assertEquals(0L, engine.getLastSyncTime())
    }
}
