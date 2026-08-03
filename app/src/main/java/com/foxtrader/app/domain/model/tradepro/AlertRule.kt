package com.foxtrader.app.domain.model.tradepro

import kotlinx.serialization.Serializable

/**
 * A user-defined smart alert rule for the TRADEPRO framework. Each rule watches either a specific
 * symbol or the whole watchlist on a chosen timeframe, and fires when its [trigger] condition is met
 * by the live TRADEPRO read. Rules are serialised to preferences so they persist across sessions.
 */
@Serializable
data class AlertRule(
    val id: String,
    val name: String,
    /** Empty = applies to every watchlist symbol; otherwise a single symbol. */
    val symbol: String = "",
    /** Timeframe label (e.g. "1H"); empty = the user's default. */
    val timeframeLabel: String = "",
    val trigger: AlertTriggerType,
    /** Generic threshold used by CONFIDENCE_ABOVE (percent) and RR_ABOVE (ratio). */
    val threshold: Double = 0.0,
    /** Minimum stage used by STAGE_REACHED. */
    val minStage: AlertStage = AlertStage.CONFIRMATION,
    val enabled: Boolean = true,
    /** Minimum minutes between repeat fires for the same rule+symbol. */
    val cooldownMinutes: Int = 15,
    val createdAtEpochMs: Long = 0L,
) {
    val appliesToAllSymbols: Boolean get() = symbol.isBlank()

    /** Human description of what this rule watches for. */
    fun conditionText(): String = when (trigger) {
        AlertTriggerType.EXECUTABLE_SETUP -> "Setup becomes executable (EXECUTE)"
        AlertTriggerType.STAGE_REACHED -> "Setup reaches ${minStage.label} or better"
        AlertTriggerType.ZONE_ENTERED -> "Price enters a Buy/Sell-Hold zone"
        AlertTriggerType.HTF_ALIGNED -> "Setup aligns with higher-timeframe bias"
        AlertTriggerType.CONFIDENCE_ABOVE -> "Confidence rises above ${threshold.toInt()}%"
        AlertTriggerType.RR_ABOVE -> "Reward:risk exceeds ${"%.1f".format(threshold)}"
        AlertTriggerType.BIAS_FLIP -> "Flip Zone bias flips direction"
    }
}

/**
 * The condition families a rule can watch. Kept as a simple enum (plus params on [AlertRule]) so the
 * whole rule serialises trivially and the builder UI can enumerate options.
 */
@Serializable
enum class AlertTriggerType(val label: String, val usesThreshold: Boolean, val usesStage: Boolean) {
    EXECUTABLE_SETUP("Executable setup", false, false),
    STAGE_REACHED("Stage reached", false, true),
    ZONE_ENTERED("Price in zone", false, false),
    HTF_ALIGNED("HTF aligned", false, false),
    CONFIDENCE_ABOVE("Confidence above", true, false),
    RR_ABOVE("R:R above", true, false),
    BIAS_FLIP("Bias flip", false, false),
}

/** A serialisable mirror of [SetupStage] for rule thresholds (avoids leaking non-serialised enums). */
@Serializable
enum class AlertStage(val label: String) {
    LEVEL("Level"),
    ZONE("Zone"),
    CONFIRMATION("Confirmation"),
    EXECUTE("Execute"),
}

/** Priority of a fired alert, used for sorting and notification gating. */
enum class AlertPriority(val label: String, val rank: Int) {
    LOW("Low", 0),
    MEDIUM("Medium", 1),
    HIGH("High", 2),
    CRITICAL("Critical", 3),
}

/**
 * An alert produced when a rule's condition is satisfied for a symbol at a point in time.
 */
data class TriggeredAlert(
    val ruleId: String,
    val ruleName: String,
    val symbol: String,
    val priority: AlertPriority,
    val message: String,
    val triggeredAtEpochMs: Long,
) {
    /** Stable key for cooldown/dedup: one live alert per rule+symbol. */
    val dedupeKey: String get() = "$ruleId::$symbol"
}
