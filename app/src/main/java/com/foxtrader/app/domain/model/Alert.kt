package com.foxtrader.app.domain.model

/** Alert delivery channels. */
enum class AlertChannel {
    PUSH, DESKTOP, WEBHOOK, TELEGRAM, EMAIL, MOBILE
}

/** Alert priority levels. */
enum class AlertPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * Alert configuration — persisted in user settings.
 */
data class AlertConfig(
    val enabledChannels: List<AlertChannel> = listOf(AlertChannel.PUSH),
    val minPriority: AlertPriority = AlertPriority.MEDIUM,
    val cooldownMs: Long = 60_000L,
    val soundEnabled: Boolean = true,
    val maxAlertsPerHour: Int = 30,
    val webhookUrl: String? = null,
    val telegramBotToken: String? = null,
    val telegramChatId: String? = null,
)

/**
 * A dispatched alert record.
 */
data class FoxAlert(
    val id: String,
    val title: String,
    val body: String,
    val priority: AlertPriority,
    val symbol: String? = null,
    val dispatchedTo: List<AlertChannel> = emptyList(),
    val timestamp: Long,
    val acknowledged: Boolean = false,
)
