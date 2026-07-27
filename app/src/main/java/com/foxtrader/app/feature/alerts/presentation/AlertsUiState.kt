package com.foxtrader.app.feature.alerts.presentation

import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.FoxAlert

/**
 * Immutable UI state for the Alerts inbox.
 */
data class AlertsUiState(
    val alerts: List<FoxAlert> = emptyList(),
    /** null = show everything. */
    val priorityFilter: AlertPriority? = null,
    val unreadOnly: Boolean = false,
    val isLoading: Boolean = true,
) {
    /**
     * Priority filtering is "this level and above", not an exact match: a
     * trader filtering to HIGH wants CRITICAL too, and an exact filter would
     * hide the most important alerts in the app.
     */
    val visibleAlerts: List<FoxAlert>
        get() = alerts.filter { alert ->
            val priorityOk = priorityFilter?.let { alert.priority.ordinal >= it.ordinal } ?: true
            val readOk = !unreadOnly || !alert.acknowledged
            priorityOk && readOk
        }

    val unreadCount: Int get() = alerts.count { !it.acknowledged }
    val hasAlerts: Boolean get() = alerts.isNotEmpty()
    val hasVisibleAlerts: Boolean get() = visibleAlerts.isNotEmpty()
}
