package com.foxtrader.app.data.sync

import com.foxtrader.app.data.repository.CloudSyncRepositoryImpl
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.SyncEnvelope
import com.foxtrader.app.domain.model.SyncableType
import com.foxtrader.app.domain.repository.DrawingRepository
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.usecase.sync.CloudSyncEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates cloud sync for all user-authored data (journal + drawings).
 */
@Singleton
class SyncManager @Inject constructor(
    private val journalRepository: JournalRepository,
    private val drawingRepository: DrawingRepository,
    private val cloudSyncRepository: CloudSyncRepositoryImpl,
    private val syncEngine: CloudSyncEngine,
    private val json: Json,
) {

    private val deviceId: String = UUID.randomUUID().toString()

    /**
     * Local-clock watermark of the last successful push. Kept apart from the
     * pull cursor, which is the server's clock: comparing local modification
     * stamps against a server timestamp skips edits whenever the device clock
     * runs behind the server.
     */
    @Volatile private var lastPushedAt: Long = 0L

    suspend fun syncNow(): CloudSyncEngine.SyncResult {
        if (!cloudSyncRepository.isSyncAvailable()) {
            return CloudSyncEngine.SyncResult(success = false, error = "Sign in to enable cloud sync.")
        }

        val pushStartedAt = System.currentTimeMillis()
        val items = mutableListOf<SyncEnvelope>()

        // Journal entries. updatedAt is the entry's last local write — the
        // last-write-wins key — not its entryTime, which no edit ever changes.
        val stamps = journalRepository.modificationStamps()
        journalRepository.getModifiedSince(lastPushedAt).forEach { entry ->
            items += SyncEnvelope(
                id = entry.id,
                type = SyncableType.JOURNAL,
                data = json.encodeToString(JournalSyncDto.serializer(), entry.toSyncDto()),
                version = 1, updatedAt = stamps[entry.id] ?: entry.entryTime, deviceId = deviceId,
            )
        }

        // Drawings
        drawingRepository.getAll().forEach { drawing ->
            items += SyncEnvelope(
                id = drawing.id,
                type = SyncableType.DRAWINGS,
                data = json.encodeToString(DrawingSyncDto.serializer(), drawing.toSyncDto()),
                version = 1, updatedAt = drawing.createdAt, deviceId = deviceId,
            )
        }

        val result = cloudSyncRepository.sync(items, deviceId, ::applyRemote)
        if (result.success) lastPushedAt = pushStartedAt
        return result
    }

    /**
     * Store journal entries other devices wrote, last-write-wins on the
     * modification stamp. Returns how many local entries changed.
     *
     * Drawings are pushed but not applied: their payload carries no symbol or
     * timeframe, so a pulled drawing cannot be placed on any chart.
     */
    private suspend fun applyRemote(remote: List<SyncEnvelope>): Int {
        val journal = remote.filter { it.type == SyncableType.JOURNAL && it.deviceId != deviceId }
        if (journal.isEmpty()) return 0
        val localStamps = journalRepository.modificationStamps()
        // Screenshot paths are device-local and not synced; an edit arriving
        // for an existing entry must not drop the one stored here.
        val localScreenshots = if (journal.any { it.id in localStamps }) {
            journalRepository.getAllEntries().associate { it.id to it.screenshot }
        } else {
            emptyMap()
        }
        var applied = 0
        for (envelope in journal) {
            val localStamp = localStamps[envelope.id]
            if (localStamp != null && localStamp >= envelope.updatedAt) continue
            if (envelope.deleted) {
                if (localStamp != null) {
                    journalRepository.delete(envelope.id)
                    applied++
                }
                continue
            }
            val dto = runCatching {
                json.decodeFromString(JournalSyncDto.serializer(), envelope.data)
            }.getOrNull() ?: continue
            val entry = dto.toJournalEntry(localScreenshot = localScreenshots[envelope.id]) ?: continue
            journalRepository.upsertFromSync(entry, envelope.updatedAt)
            applied++
        }
        return applied
    }
}

// ============================================================================
// DRAWING SYNC DTO
// ============================================================================

@Serializable
data class DrawingSyncDto(
    val id: String,
    val symbol: String,
    val timeframe: String,
    val type: String,
    val points: String,
    val color: Long,
    val lineWidth: Float,
    val label: String? = null,
    val createdAt: Long,
)

fun ChartDrawing.toSyncDto(): DrawingSyncDto = DrawingSyncDto(
    id = id,
    symbol = "", // filled by caller if needed
    timeframe = "",
    type = type.name,
    points = points.joinToString(";") { "${it.index},${it.price},${it.timestamp}" },
    color = color,
    lineWidth = lineWidth,
    label = label,
    createdAt = createdAt,
)
