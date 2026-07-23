package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.domain.model.SignalGrade
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the AI Decision Engine to the alert system.
 *
 * When the [MasterDecisionEngine] approves a signal, this service builds a
 * [FoxAlert] and returns it for the caller to dispatch. It enforces:
 * - **Cooldown deduplication:** the same symbol + direction is not alerted
 *   again within [cooldownMs].
 * - **Minimum grade filter:** only alerts at or above [minGrade].
 *
 * This is a pure-domain service (no Android deps). The caller
 * (ChartViewModel) passes the alert to [AlertDispatcher] for actual delivery.
 */
@Singleton
class AiAlertService @Inject constructor() {

    // ConcurrentHashMap for safe access from multiple coroutines.
    private val recentAlerts = ConcurrentHashMap<String, Long>() // key -> last dispatch timestamp

    var cooldownMs: Long = DEFAULT_COOLDOWN_MS
    var minGrade: SignalGrade = SignalGrade.MODERATE

    /**
     * Evaluate a [DecisionResult] and produce a [FoxAlert] if it should fire.
     * Returns null if the decision is not approved, below minimum grade, or
     * within the cooldown window.
     */
    fun evaluate(decision: DecisionResult, symbol: String): FoxAlert? {
        if (!decision.approved) return null
        if (decision.grade.ordinal < minGrade.ordinal) return null

        val direction = decision.direction ?: return null
        val key = "$symbol-$direction"
        val now = System.currentTimeMillis()
        val lastFired = recentAlerts[key]
        if (lastFired != null && (now - lastFired) < cooldownMs) return null

        // Passed all gates — build the alert.
        recentAlerts[key] = now

        val priority = when (decision.grade) {
            SignalGrade.INSTITUTIONAL -> AlertPriority.CRITICAL
            SignalGrade.VERY_STRONG -> AlertPriority.HIGH
            SignalGrade.STRONG -> AlertPriority.HIGH
            SignalGrade.MODERATE -> AlertPriority.MEDIUM
            else -> AlertPriority.LOW
        }

        val confluences = decision.confluencePresent.joinToString(", ") { it.name }

        return FoxAlert(
            id = UUID.randomUUID().toString(),
            title = "AI Signal: $direction $symbol (${decision.grade})",
            body = "${decision.confidence.toInt()}% confidence | " +
                "${decision.confluencePresent.size}/9 confluences: $confluences",
            priority = priority,
            symbol = symbol,
            timestamp = now,
        )
    }

    /** Clear cooldown state (e.g. on symbol/timeframe change). */
    fun resetCooldowns() {
        recentAlerts.clear()
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 5 * 60_000L // 5 minutes
    }
}
