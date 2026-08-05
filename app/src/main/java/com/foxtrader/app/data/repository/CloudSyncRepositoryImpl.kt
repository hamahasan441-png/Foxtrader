package com.foxtrader.app.data.repository

import com.foxtrader.app.data.auth.TokenManager
import com.foxtrader.app.data.remote.api.SyncApi
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.SyncEnvelope
import com.foxtrader.app.domain.model.SyncPullResponse
import com.foxtrader.app.domain.model.SyncPushRequest
import com.foxtrader.app.domain.model.SyncableType
import com.foxtrader.app.domain.usecase.sync.CloudSyncEngine
import com.foxtrader.app.domain.usecase.sync.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud Sync Repository — data-layer implementation of push/pull sync
 * using the [SyncApi] backend endpoints.
 *
 * Orchestrates:
 * 1. Push local changes (journal/drawings/settings) to the server.
 * 2. Pull remote changes from other devices.
 * 3. Merge via [CloudSyncEngine] domain logic (last-write-wins + union).
 * 4. Update last-sync timestamp on success.
 *
 * Requires authentication — all API calls go through [AuthInterceptor]
 * which attaches the Bearer token and handles refresh transparently.
 *
 * SECURITY: sync payloads are transmitted over HTTPS (cert-pinned in
 * production). Sensitive data (API keys) is NEVER included in sync payloads.
 */
@Singleton
class CloudSyncRepositoryImpl @Inject constructor(
    private val syncApi: SyncApi,
    private val syncEngine: CloudSyncEngine,
    private val tokenManager: TokenManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Perform a full sync cycle: push local changes, then pull remote changes.
     *
     * @param localItems Items to push (already diffed by the caller/domain layer).
     * @param deviceId The local device identifier.
     * @return [CloudSyncEngine.SyncResult] with merge stats.
     */
    suspend fun sync(
        localItems: List<SyncEnvelope>,
        deviceId: String,
    ): CloudSyncEngine.SyncResult = withContext(io) {
        if (!tokenManager.isLoggedIn()) {
            return@withContext CloudSyncEngine.SyncResult(
                success = false,
                error = "Not authenticated — login required for sync.",
            )
        }

        syncEngine.setSyncStatus(SyncStatus.SYNCING)

        try {
            // 1. Push local changes.
            var pushAccepted = 0
            if (localItems.isNotEmpty()) {
                val pushRequest = SyncPushRequest(
                    items = localItems,
                    lastSyncTimestamp = syncEngine.getLastSyncTime(),
                    deviceId = deviceId,
                )
                val pushResponse = syncApi.pushSync(pushRequest)
                pushAccepted = pushResponse.accepted ?: localItems.size
            }

            // 2. Pull remote changes.
            val pullResponse = syncApi.pullSync(
                since = syncEngine.getLastSyncTime(),
            )

            // 3. Update last-sync timestamp only after both push and pull succeed.
            syncEngine.updateLastSyncTime(pullResponse.serverTimestamp)
            syncEngine.setSyncStatus(SyncStatus.SUCCESS)

            CloudSyncEngine.SyncResult(
                success = true,
                mergedEntries = pullResponse.items.size,
                conflicts = pullResponse.conflicts ?: 0,
                timestamp = pullResponse.serverTimestamp,
            )
        } catch (e: Exception) {
            syncEngine.setSyncStatus(SyncStatus.FAILED)
            CloudSyncEngine.SyncResult(
                success = false,
                error = e.message ?: "Sync failed",
            )
        }
    }

    /**
     * Pull-only sync (used for initial device setup / restore).
     */
    suspend fun pullAll(): SyncPullResponse? = withContext(io) {
        if (!tokenManager.isLoggedIn()) return@withContext null
        try {
            syncApi.pullSync(since = 0L) // Pull everything from the beginning
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if sync is available (user is authenticated).
     */
    fun isSyncAvailable(): Boolean = tokenManager.isLoggedIn()

    /**
     * Return the last sync error message, if any.
     */
    fun getLastSyncError(): String? = when (syncEngine.getSyncStatus()) {
        SyncStatus.FAILED -> "Last sync failed. Pull-to-refresh to retry."
        SyncStatus.SYNCING -> null
        else -> null
    }
}
