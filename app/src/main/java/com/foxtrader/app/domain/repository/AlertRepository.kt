package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.FoxAlert
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for alert history.
 *
 * Alerts used to be fire-and-forget notifications; persisting them is what
 * makes an inbox, an unread badge and acknowledgement possible.
 */
interface AlertRepository {

    /** Observe the full inbox, newest first. */
    fun observeAlerts(): Flow<List<FoxAlert>>

    /** Observe the unacknowledged count for the navigation badge. */
    fun observeUnacknowledgedCount(): Flow<Int>

    /** Record a dispatched alert (also applies retention pruning). */
    suspend fun record(alert: FoxAlert)

    suspend fun acknowledge(id: String)

    suspend fun acknowledgeAll()

    suspend fun delete(id: String)

    suspend fun clear()
}
