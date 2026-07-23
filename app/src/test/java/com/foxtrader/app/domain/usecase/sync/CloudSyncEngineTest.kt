package com.foxtrader.app.domain.usecase.sync

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CloudSyncEngine — merge logic + status.
 */
class CloudSyncEngineTest {

    private lateinit var engine: CloudSyncEngine

    @Before
    fun setup() {
        engine = CloudSyncEngine()
    }

    private fun entry(id: String, entryTime: Long) = JournalEntry(
        id = id,
        symbol = "EURUSD",
        direction = Direction.BULLISH,
        timeframe = Timeframe.H1,
        entryPrice = 1.1,
        exitPrice = 1.11,
        stopLoss = 1.09,
        takeProfit = 1.12,
        volume = 0.1,
        entryTime = entryTime,
        exitTime = entryTime + 3600_000,
        pnl = 100.0,
        rMultiple = 1.0,
        setupType = "Test",
        emotionTag = EmotionTag.NEUTRAL,
    )

    @Test
    fun `mergeEntries adds new remote entries`() {
        val local = listOf(entry("A", 1000))
        val remote = listOf(entry("B", 2000))
        val (merged, conflicts) = engine.mergeEntries(local, remote)

        assertEquals(2, merged.size)
        assertEquals(0, conflicts)
        assertTrue(merged.any { it.id == "A" })
        assertTrue(merged.any { it.id == "B" })
    }

    @Test
    fun `mergeEntries resolves conflict with last-write-wins`() {
        val local = listOf(entry("A", 1000))
        val remoteNewer = listOf(entry("A", 2000))
        val (merged, conflicts) = engine.mergeEntries(local, remoteNewer)

        assertEquals(1, merged.size)
        assertEquals(1, conflicts)
        assertEquals(2000L, merged[0].entryTime)
    }

    @Test
    fun `mergeEntries keeps local when local is newer`() {
        val local = listOf(entry("A", 3000))
        val remoteOlder = listOf(entry("A", 1000))
        val (merged, conflicts) = engine.mergeEntries(local, remoteOlder)

        assertEquals(1, merged.size)
        assertEquals(0, conflicts) // local wins — not counted as a conflict override
        assertEquals(3000L, merged[0].entryTime)
    }

    @Test
    fun `calculateUploadDiff returns entries newer than lastSync`() {
        val entries = listOf(entry("A", 1000), entry("B", 3000), entry("C", 5000))
        val diff = engine.calculateUploadDiff(entries, lastSyncTime = 2000)

        assertEquals(2, diff.size) // B (3000) and C (5000) are newer than 2000
        assertTrue(diff.none { it.id == "A" })
    }

    @Test
    fun `sync status transitions correctly`() {
        assertEquals(SyncStatus.IDLE, engine.getSyncStatus())
        engine.setSyncStatus(SyncStatus.SYNCING)
        assertEquals(SyncStatus.SYNCING, engine.getSyncStatus())
        engine.setSyncStatus(SyncStatus.SUCCESS)
        assertEquals(SyncStatus.SUCCESS, engine.getSyncStatus())
    }

    @Test
    fun `needsSync returns true when local data is newer`() {
        engine.updateLastSyncTime(1000)
        assertTrue(engine.needsSync(localModifiedAfter = 2000))
    }
}
