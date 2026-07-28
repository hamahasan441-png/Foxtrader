package com.foxtrader.app.data.market.model

import com.foxtrader.app.domain.model.Timeframe
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Timeframes the real-time candle engine can build from raw ticks.
 *
 * This is intentionally a **superset** of the chart-facing [Timeframe] enum:
 * the mission requires M2, M3 and M10 bars, which the chart enum does not carry.
 * Rather than add entries to [Timeframe] (which would force edits to every
 * exhaustive `when(timeframe)` in the data-source layer and risk the build), the
 * engine owns its own complete, precisely-aligned model and bridges to the chart
 * enum losslessly for the nine overlapping buckets via [toChartTimeframe].
 *
 * ## Bucket alignment (the no-repaint foundation)
 *
 * A candle is only well-defined if every tick maps to exactly one bucket and the
 * bucket boundaries are stable. Sub-day and daily buckets use fixed lengths that
 * evenly divide a UTC day, so `floorDiv(epochMs, durationMs)` aligns them to UTC
 * midnight. Weeks are aligned to Monday UTC and months to the first day of the
 * UTC month, using `java.time` (available natively at minSdk 29 and in JVM unit
 * tests). Alignment is pure and deterministic — the same timestamp always lands
 * in the same bucket, which is what makes "no repainting" provable.
 */
enum class MarketTimeframe(
    val label: String,
    /** Fixed bucket length in milliseconds, or `null` for calendar-aligned buckets (week/month). */
    val fixedDurationMs: Long?,
) {
    M1("1m", 60_000L),
    M2("2m", 120_000L),
    M3("3m", 180_000L),
    M5("5m", 300_000L),
    M10("10m", 600_000L),
    M15("15m", 900_000L),
    M30("30m", 1_800_000L),
    H1("1h", 3_600_000L),
    H4("4h", 14_400_000L),
    D1("1d", 86_400_000L),
    W1("1w", null),
    MN("1M", null),
    ;

    /** True for fixed-length buckets that divide a UTC day evenly. */
    val isIntraday: Boolean get() = fixedDurationMs != null && this <= D1

    /**
     * The inclusive-open bucket start (epoch millis) that contains [epochMs].
     * Pure and deterministic: a given timestamp always resolves to the same start.
     */
    fun bucketStart(epochMs: Long): Long = when (this) {
        W1 -> {
            val date = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            monday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        MN -> {
            val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
            zoned.toLocalDate().withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        else -> {
            val duration = requireNotNull(fixedDurationMs) { "$this has no fixed duration" }
            Math.floorDiv(epochMs, duration) * duration
        }
    }

    /** The exclusive bucket end for a bucket that starts at [bucketStartMs]. */
    fun bucketEnd(bucketStartMs: Long): Long = when (this) {
        W1 -> bucketStartMs + DAYS_PER_WEEK * MILLIS_PER_DAY
        MN -> Instant.ofEpochMilli(bucketStartMs).atZone(ZoneOffset.UTC)
            .plusMonths(1).toInstant().toEpochMilli()
        else -> bucketStartMs + requireNotNull(fixedDurationMs) { "$this has no fixed duration" }
    }

    /**
     * Lossless bridge to the chart enum for the nine overlapping buckets.
     * Returns `null` for the engine-only timeframes (M2, M3, M10) that the chart
     * surface does not expose.
     */
    fun toChartTimeframe(): Timeframe? = when (this) {
        M1 -> Timeframe.M1
        M5 -> Timeframe.M5
        M15 -> Timeframe.M15
        M30 -> Timeframe.M30
        H1 -> Timeframe.H1
        H4 -> Timeframe.H4
        D1 -> Timeframe.D1
        W1 -> Timeframe.W1
        MN -> Timeframe.MN
        M2, M3, M10 -> null
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val DAYS_PER_WEEK = 7L

        /** All twelve engine timeframes in ascending duration order. */
        val ALL: List<MarketTimeframe> = entries

        /** Resolves a label such as `"5m"`; `null` when unknown. */
        fun fromLabel(label: String): MarketTimeframe? = entries.firstOrNull { it.label == label }

        /** Builds a [MarketTimeframe] from a chart [Timeframe]; never fails. */
        fun fromChart(timeframe: Timeframe): MarketTimeframe = when (timeframe) {
            // Mapped explicitly (not by label) because the chart enum uses
            // uppercase hour/day/week labels ("1H","1D","1W") while the engine
            // uses lowercase; a label compare would miss them and also collide
            // M1("1m") with MN("1M") under a case-insensitive compare.
            Timeframe.M1 -> M1
            Timeframe.M5 -> M5
            Timeframe.M15 -> M15
            Timeframe.M30 -> M30
            Timeframe.H1 -> H1
            Timeframe.H4 -> H4
            Timeframe.D1 -> D1
            Timeframe.W1 -> W1
            Timeframe.MN -> MN
        }
    }
}
