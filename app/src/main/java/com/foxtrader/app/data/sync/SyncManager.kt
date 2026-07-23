package com.foxtrader.app.data.sync

import com.foxtrader.app.data.repository.CloudSyncRepositoryImpl
import com.foxtrader.app.domain.model.SyncEnvelope
import com.foxtrader.app.domain.model.SyncableType
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.usecase.sync.CloudSyncEngine
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates a full cloud-sync cycle for user-authored data.
 *
 * Responsibilities:
 * 1. Collect journal entries modified since the last successful sync.
 * 2. Serialize each into a [SyncEnvelope] (data as a JSON string).
 * 3. Delegate the push/pull + timestamp bookkeeping to [CloudSyncRepositoryImpl].
 *
 * Currently syncs the trade journal; drawings/settings/watchlists follow the
 * same pattern (add another collector + SyncableType).
 *
 * Requires authentication — [CloudSyncRepositoryImpl] short-circuits with an
 * error result when the user is not logged in.
 */
@Singleton
class SyncManager @Inject constructor(
    private val journalRepository: JournalRepository,
    private val cloudSyncRepository: CloudSyncRepositoryImpl,
    private val syncEngine: CloudSyncEngine,
    private val json: Json,
) {

    // TODO(H3): persist a stable device ID (e.g. in EncryptedSharedPreferences).
    // A per-process UUID is sufficient for the initial wiring.
    private val deviceId: String = UUID.randomUUID().toString()

    /**
     * Run a sync cycle. Returns the [CloudSyncEngine.SyncResult] (success flag,
     * merged count, error message).
     */
    suspend fun syncNow(): CloudSyncEngine.SyncResult {
        if (!cloudSyncRepository.isSyncAvailable()) {
            return CloudSyncEngine.SyncResult(
                success = false,
                error = "Sign in to enable cloud sync.",
            )
        }

        // 1. Collect locally-modified journal entries since the last sync.
        val since = syncEngine.getLastSyncTime()
        val modified = journalRepository.getModifiedSince(since)

        // 2. Wrap each in a versioned envelope with a JSON-serialized payload.
        val items = modified.map { entry ->
            SyncEnvelope(
                id = entry.id,
                type = SyncableType.JOURNAL,
                data = json.encodeToString(JournalSyncDto.serializer(), entry.toSyncDto()),
                version = 1,
                updatedAt = entry.entryTime,
                deviceId = deviceId,
                deleted = false,
            )
        }

        // 3. Push + pull via the repository (handles auth, timestamps, errors).
        return cloudSyncRepository.sync(items, deviceId)
    }
}
