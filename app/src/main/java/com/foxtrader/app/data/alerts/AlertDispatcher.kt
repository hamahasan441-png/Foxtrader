package com.foxtrader.app.data.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.FoxAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-native alert dispatcher.
 * Bridges the domain [AlertEngine] output to Android's notification system
 * using NotificationCompat for backward compatibility (API 29+).
 */
@Singleton
class AlertDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        const val CHANNEL_TRADING = "fox_trading_alerts"
        const val CHANNEL_RISK = "fox_risk_alerts"
        const val CHANNEL_SCANNER = "fox_scanner_alerts"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    /**
     * Dispatch a [FoxAlert] as an Android notification.
     */
    fun dispatch(alert: FoxAlert) {
        val channelId = when (alert.priority) {
            AlertPriority.CRITICAL, AlertPriority.HIGH -> CHANNEL_RISK
            AlertPriority.MEDIUM -> CHANNEL_TRADING
            AlertPriority.LOW -> CHANNEL_SCANNER
        }

        val importance = when (alert.priority) {
            AlertPriority.CRITICAL -> NotificationCompat.PRIORITY_MAX
            AlertPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
            AlertPriority.MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
            AlertPriority.LOW -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: replace with fox icon
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setPriority(importance)
            .setAutoCancel(true)
            .setGroup("fox_alerts")
            .apply {
                if (alert.symbol != null) {
                    setSubText(alert.symbol)
                }
                if (alert.priority == AlertPriority.CRITICAL) {
                    setCategory(NotificationCompat.CATEGORY_ALARM)
                    setOngoing(true)
                }
            }
            .build()

        val notificationId = alert.id.hashCode()
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Cancel (dismiss) a notification by alert ID.
     */
    fun cancel(alertId: String) {
        notificationManager.cancel(alertId.hashCode())
    }

    /**
     * Cancel all FoxTrader notifications.
     */
    fun cancelAll() {
        notificationManager.cancelAll()
    }

    // ========================================================================
    // NOTIFICATION CHANNELS (required for Android 8.0+ / API 26+)
    // ========================================================================

    private fun createChannels() {
        val tradingChannel = NotificationChannel(
            CHANNEL_TRADING,
            "Trading Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Trade signals, BOS/CHOCH breaks, entry confirmations"
            enableVibration(true)
        }

        val riskChannel = NotificationChannel(
            CHANNEL_RISK,
            "Risk Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Critical risk events: drawdown, daily loss limit, halt"
            enableVibration(true)
            enableLights(true)
        }

        val scannerChannel = NotificationChannel(
            CHANNEL_SCANNER,
            "Scanner Updates",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Screener results and watchlist changes"
        }

        notificationManager.createNotificationChannels(
            listOf(tradingChannel, riskChannel, scannerChannel)
        )
    }
}
