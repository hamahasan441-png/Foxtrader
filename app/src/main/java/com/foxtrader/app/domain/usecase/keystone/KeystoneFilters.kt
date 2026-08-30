package com.foxtrader.app.domain.usecase.keystone

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

/**
 * Step 8 — the conditions under which the model is not worth trading even when
 * the setup is perfect.
 *
 * Each of these removes trades, and every one of them removes some winners
 * along with the losers. They are here because the losers they remove are
 * systematic and the winners are not: a sequence that fires at 03:00 on a
 * two-pip spread in a dead market is the same geometry paying a different
 * price, and it is the price that decides whether an edge survives.
 *
 * One honest limitation, stated rather than hidden: this app carries no spread
 * feed and no economic calendar. The spread test therefore runs against an
 * assumed spread the trader configures, and the news blackout covers recurring
 * release times rather than actual events. Both are coarser than the real
 * thing. Neither is a placeholder that silently passes everything — an assumed
 * spread that is too large for the risk still stands the trade down, which is
 * the behaviour that matters when the stop is tight.
 */
class KeystoneFilters {

    private val calendar: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    /** The session [timestamp] falls in, or null when it falls in none. */
    fun sessionAt(timestamp: Long): KeystoneSession? {
        val hour = hourOf(timestamp)
        return KeystoneSession.entries.firstOrNull { it.contains(hour) }
    }

    /** True when entries are permitted at [timestamp]. */
    fun sessionAllowed(timestamp: Long, config: KeystoneConfig): Boolean {
        if (config.sessions.isEmpty()) return true
        val hour = hourOf(timestamp)
        return config.sessions.any { it.contains(hour) }
    }

    /**
     * True when [timestamp] sits inside a scheduled release window.
     *
     * The window is symmetric around the release: the minutes before a print
     * are as bad a place to be filled as the minutes after, because the spread
     * widens in advance of it.
     */
    fun inNewsWindow(timestamp: Long, config: KeystoneConfig): Boolean {
        if (config.newsBlackoutMinutes <= 0 || config.newsWindowsUtc.isEmpty()) return false
        val minute = minuteOfDay(timestamp)
        return config.newsWindowsUtc.any { window ->
            val delta = abs(minute - window.minuteOfDay)
            // Wrap across midnight so a window at 23:50 covers 00:10.
            min(delta, MINUTES_PER_DAY - delta) <= config.newsBlackoutMinutes
        }
    }

    /** True when volatility at [index] is above the floor. */
    fun volatilityOk(
        atr: DoubleArray,
        median: DoubleArray,
        index: Int,
        config: KeystoneConfig,
    ): Boolean {
        val current = atr.getOrElse(index) { 0.0 }
        if (current <= 0.0) return false
        val reference = median.getOrElse(index) { 0.0 }
        // Before the median window has filled there is nothing to compare
        // against, and refusing every early bar would be a data artefact rather
        // than a volatility judgement.
        if (reference <= 0.0) return true
        return current >= reference * config.volatilityFloorFraction
    }

    /**
     * True when the assumed spread is small enough against the trade's risk.
     *
     * Measured against risk rather than against price: a fixed spread is
     * negligible on a 40-pip stop and ruinous on a 3-pip one, and it is the
     * ratio that decides whether the expectancy survives the cost.
     */
    fun spreadOk(entry: Double, risk: Double, config: KeystoneConfig): Boolean {
        if (risk <= 0.0) return false
        val spread = abs(entry) * config.assumedSpreadFraction
        return spread <= risk * config.maxSpreadShareOfRisk
    }

    /** Calendar day of [timestamp] in UTC, usable as a grouping key. */
    fun dayKey(timestamp: Long): Int {
        calendar.timeInMillis = timestamp
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun hourOf(timestamp: Long): Int {
        calendar.timeInMillis = timestamp
        return calendar.get(Calendar.HOUR_OF_DAY)
    }

    private fun minuteOfDay(timestamp: Long): Int {
        calendar.timeInMillis = timestamp
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
