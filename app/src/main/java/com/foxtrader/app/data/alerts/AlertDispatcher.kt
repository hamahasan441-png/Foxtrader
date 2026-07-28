package com.foxtrader.app.data.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.domain.repository.AlertRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.foxtrader.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-native alert dispatcher.
 * Bridges domain alert output to Android's notification system using
 * NotificationCompat for backward compatibility (API 29+).
 *
 * Dispatching also **records the alert to the inbox**. Persistence is done here
 * rather than at each call site deliberately: a notification the user swipes
 * away used to be gone forever, and making history a side effect of delivery
 * means no future caller can post an alert that silently vanishes.
 */
@Singleton
class AlertDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertRepository: AlertRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Scope for persistence. Deliberately application-scoped (SupervisorJob):
     * a caller's coroutine may be cancelled the moment its ViewModel clears,
     * which must not drop the history write for an alert already shown.
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

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
     * Dispatch a [FoxAlert] as an Android notification and record it in the
     * inbox.
     */
    fun dispatch(alert: FoxAlert) {
        scope.launch { alertRepository.record(alert) }
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
            .setSmallIcon(R.drawable.ic_notification_fox)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
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
