package com.foxtrader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a dispatched alert.
 *
 * Alerts were previously fire-and-forget: [com.foxtrader.app.data.alerts.AlertDispatcher]
 * posted an Android notification and nothing was retained. If the user missed
 * or swiped the notification, the signal was gone — and `FoxAlert.acknowledged`
 * existed in the domain model with nothing able to set it.
 *
 * Persisting alerts makes the inbox possible and gives acknowledgement a home.
 *
 * Indexed on `timestamp` because the inbox always reads newest-first, and on
 * `acknowledged` to keep the unread badge count cheap.
 */
@Entity(
    tableName = "alerts",
    indices = [Index(value = ["timestamp"]), Index(value = ["acknowledged"])],
)
data class AlertEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val priority: String,      // AlertPriority.name
    val symbol: String?,
    val timestamp: Long,
    val acknowledged: Boolean,
)
