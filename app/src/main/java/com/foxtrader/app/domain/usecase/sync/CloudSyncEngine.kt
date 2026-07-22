package com.foxtrader.app.domain.usecase.sync

import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.JournalEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud Sync Engine — backup and restore trading data.
 *
 * Manages:
 * - Journal entries backup/restore
 * - Drawing data backup/restore
 * - Settings sync
 * - Conflict resolution (last-write-wins)
 * - Sync status tracking
 *
 * The actual cloud API calls are in the data layer.
 * This engine provides domain logic: diff calculation, merge, conflict resolution.
 */
@Singleton
class CloudSyncEngine @Inject constructor() {

    private var lastSyncTimestamp: Long = 0L
    private var syncStatus: SyncStatus = SyncStatus.IDLE

    // ========================================================================
    // SYNC DATA MODELS
    // ========================================================================

    data class SyncPayload(
        val journalEntries: List<JournalEntry>,
        val drawings: List<ChartDrawing>,
        val settingsJson: String,
        val timestamp: Long = System.currentTimeMillis(),
        val deviceId: String = "",
    )

    data class SyncResult(
        val success: Boolean,
        val mergedEntries: Int = 0,
        val conflicts: Int = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val error: String? = null,
    )

    // ========================================================================
    // SYNC OPERATIONS
    // ========================================================================

    /**
     * Calculate the diff between local and remote data.
     * Returns items that need to be uploaded (added/modified locally since last sync).
     */
    fun calculateUploadDiff(
        localEntries: List<JournalEntry>,
        lastSyncTime: Long,
    ): List<JournalEntry> {
        return localEntries.filter { it.entryTime > lastSyncTime }
    }

    /**
     * Merge remote entries into local data.
     * Uses last-write-wins strategy.
     *
     * @return Merged list with conflicts resolved
     */
    fun mergeEntries(
        local: List<JournalEntry>,
        remote: List<JournalEntry>,
    ): Pair<List<JournalEntry>, Int> {
        val merged = local.toMutableList()
        var conflicts = 0

        for (remoteEntry in remote) {
            val localIdx = merged.indexOfFirst { it.id == remoteEntry.id }
            if (localIdx >= 0) {
                // Conflict: same ID exists locally — last-write-wins
                val localEntry = merged[localIdx]
                if (remoteEntry.entryTime > localEntry.entryTime) {
                    merged[localIdx] = remoteEntry
                    conflicts++
                }
            } else {
                // New remote entry — add it
                merged.add(remoteEntry)
            }
        }

        return merged to conflicts
    }

    /**
     * Merge remote drawings into local drawings.
     */
    fun mergeDrawings(
        local: List<ChartDrawing>,
        remote: List<ChartDrawing>,
    ): List<ChartDrawing> {
        val merged = local.toMutableList()
        for (remoteDrawing in remote) {
            if (merged.none { it.id == remoteDrawing.id }) {
                merged.add(remoteDrawing)
            }
        }
        return merged
    }

    // ========================================================================
    // STATUS
    // ========================================================================

    fun getLastSyncTime(): Long = lastSyncTimestamp

    fun updateLastSyncTime(timestamp: Long) {
        lastSyncTimestamp = timestamp
    }

    fun getSyncStatus(): SyncStatus = syncStatus

    fun setSyncStatus(status: SyncStatus) {
        syncStatus = status
    }

    fun needsSync(localModifiedAfter: Long): Boolean =
        localModifiedAfter > lastSyncTimestamp
}

/** Sync status. */
enum class SyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    FAILED,
    CONFLICT,
}
