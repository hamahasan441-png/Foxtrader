package com.foxtrader.app.domain.usecase.alerts

import com.foxtrader.app.domain.model.AlertChannel
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.FoxAlert
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alert Engine — domain-layer alert management.
 *
 * Handles priority filtering, cooldown deduplication, hourly rate limiting,
 * and acknowledgment tracking. The actual Android notification dispatch
 * (NotificationCompat / channels) is in the data layer [AlertDispatcher].
 *
 * This keeps the domain layer framework-free while still providing the full
 * alert lifecycle: send → filter → rate-limit → dispatch → acknowledge.
 */
@Singleton
class AlertEngine @Inject constructor() {

    private var config: AlertConfig = AlertConfig()
    private val alerts = mutableListOf<FoxAlert>()
    private val cooldowns = mutableMapOf<String, Long>()
    private var hourlyCount = 0
    private var hourlyResetTime = System.currentTimeMillis() + 3_600_000L
    private var alertSeq = 0

    /**
     * Attempt to send an alert. Returns the alert if it passed all filters,
     * or null if it was suppressed (priority/cooldown/rate-limit).
     *
     * The caller (data layer) is responsible for dispatching the returned alert
     * to Android's NotificationManager.
     */
    fun send(
        title: String,
        body: String,
        priority: AlertPriority,
        symbol: String? = null,
        cooldownKey: String? = null,
    ): FoxAlert? {
        // Priority filter
        if (!meetsMinPriority(priority)) return null

        // Cooldown dedup
        val key = cooldownKey ?: "${title}_$symbol"
        val lastSent = cooldowns[key] ?: 0L
        if (System.currentTimeMillis() - lastSent < config.cooldownMs) return null

        // Hourly rate limit
        if (System.currentTimeMillis() > hourlyResetTime) {
            hourlyCount = 0
            hourlyResetTime = System.currentTimeMillis() + 3_600_000L
        }
        if (hourlyCount >= config.maxAlertsPerHour) return null

        val alert = FoxAlert(
            id = "alert_${++alertSeq}_${System.currentTimeMillis()}",
            title = title,
            body = body,
            priority = priority,
            symbol = symbol,
            dispatchedTo = config.enabledChannels,
            timestamp = System.currentTimeMillis(),
            acknowledged = false,
        )

        alerts += alert
        cooldowns[key] = System.currentTimeMillis()
        hourlyCount++

        return alert
    }

    /** Acknowledge (dismiss) an alert by ID. */
    fun acknowledge(id: String) {
        val idx = alerts.indexOfFirst { it.id == id }
        if (idx >= 0) alerts[idx] = alerts[idx].copy(acknowledged = true)
    }

    /** Get all alerts (newest first). */
    fun getAlerts(limit: Int? = null): List<FoxAlert> {
        val sorted = alerts.sortedByDescending { it.timestamp }
        return if (limit != null) sorted.take(limit) else sorted
    }

    /** Get unacknowledged alerts. */
    fun getUnacknowledged(): List<FoxAlert> = alerts.filter { !it.acknowledged }

    /** Clear all stored alerts. */
    fun clearAll() { alerts.clear() }

    /** Update alert configuration. */
    fun updateConfig(newConfig: AlertConfig) { config = newConfig }

    /** Get current configuration. */
    fun getConfig(): AlertConfig = config

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun meetsMinPriority(priority: AlertPriority): Boolean {
        val levels = AlertPriority.entries
        return levels.indexOf(priority) >= levels.indexOf(config.minPriority)
    }
}
