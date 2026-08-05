package com.foxtrader.app.domain.usecase.sessions

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.KillZone
import com.foxtrader.app.domain.model.KillZoneRange
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * ICT Kill Zone Detector — identifies the Asian Range, London Open, New York Open,
 * and London Close windows within a candle series.
 *
 * Maps each zone's open/close hours (UTC) to bar indices, computing the high/low
 * over each window. Mirrors [SessionDetector.detectSessions] exactly: same hour
 * membership test (including overnight-wrap handling), same high/low accumulation,
 * and the same end-of-data flush. Used to render kill-zone overlays on the chart.
 *
 * Pure domain logic — no Android dependencies.
 */
@Singleton
class KillZoneDetector @Inject constructor() {

    /**
     * Detect all kill-zone ranges in the given candle series.
     *
     * @param candles Full candle dataset.
     * @param zones Which kill zones to detect (default: all).
     * @return Kill-zone ranges with start/end indices and high/low, sorted by start index.
     */
    fun detect(
        candles: List<Candle>,
        zones: List<KillZone> = KillZone.entries,
    ): List<KillZoneRange> {
        if (candles.isEmpty()) return emptyList()
        val result = mutableListOf<KillZoneRange>()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        for (zone in zones) {
            var zoneStart = -1
            var zoneHigh = Double.NEGATIVE_INFINITY
            var zoneLow = Double.POSITIVE_INFINITY

            for (i in candles.indices) {
                calendar.timeInMillis = candles[i].timestamp
                val hour = calendar.get(Calendar.HOUR_OF_DAY)

                val inZone = if (zone.startHourUtc < zone.endHourUtc) {
                    hour in zone.startHourUtc until zone.endHourUtc
                } else {
                    // Overnight window (wraps midnight)
                    hour >= zone.startHourUtc || hour < zone.endHourUtc
                }

                if (inZone) {
                    if (zoneStart == -1) zoneStart = i
                    zoneHigh = max(zoneHigh, candles[i].high)
                    zoneLow = min(zoneLow, candles[i].low)
                } else if (zoneStart != -1) {
                    // Window just ended
                    result.add(
                        KillZoneRange(
                            zone = zone,
                            startIndex = zoneStart,
                            endIndex = i - 1,
                            high = zoneHigh,
                            low = zoneLow,
                        )
                    )
                    zoneStart = -1
                    zoneHigh = Double.NEGATIVE_INFINITY
                    zoneLow = Double.POSITIVE_INFINITY
                }
            }

            // Handle window still open at end of data
            if (zoneStart != -1) {
                result.add(
                    KillZoneRange(
                        zone = zone,
                        startIndex = zoneStart,
                        endIndex = candles.lastIndex,
                        high = zoneHigh,
                        low = zoneLow,
                    )
                )
            }
        }

        return result.sortedBy { it.startIndex }
    }

    /**
     * Return the [KillZone] active at the given [timestampMs] (UTC), or null if the
     * timestamp falls outside every kill-zone window.
     */
    fun isInKillZone(timestampMs: Long): KillZone? {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timestampMs
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        return KillZone.entries.firstOrNull { zone ->
            if (zone.startHourUtc < zone.endHourUtc) {
                hour in zone.startHourUtc until zone.endHourUtc
            } else {
                hour >= zone.startHourUtc || hour < zone.endHourUtc
            }
        }
    }
}
