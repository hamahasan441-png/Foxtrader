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

    suspend fun syncNow(): CloudSyncEngine.SyncResult {
        if (!cloudSyncRepository.isSyncAvailable()) {
            return CloudSyncEngine.SyncResult(success = false, error = "Sign in to enable cloud sync.")
        }

        val since = syncEngine.getLastSyncTime()
        val items = mutableListOf<SyncEnvelope>()

        // Journal entries
        journalRepository.getModifiedSince(since).forEach { entry ->
            items += SyncEnvelope(
                id = entry.id,
                type = SyncableType.JOURNAL,
                data = json.encodeToString(JournalSyncDto.serializer(), entry.toSyncDto()),
                version = 1, updatedAt = entry.entryTime, deviceId = deviceId,
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

        return cloudSyncRepository.sync(items, deviceId)
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
