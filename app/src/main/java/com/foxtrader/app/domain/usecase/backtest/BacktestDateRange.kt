package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The research period a backtest measures, TradingView-style.
 *
 * A preset is resolved against "now" every time it is used, so a "1Y" run made
 * tomorrow measures a window shifted by one day. [CUSTOM] pins absolute
 * calendar dates instead, which is what makes a published result reproducible:
 * re-running "2024-01-01 → 2024-03-31" next month must measure exactly the same
 * bars it measured today.
 */
enum class BacktestRangePreset(val label: String, val days: Int?) {
    /** Everything the provider returned for the newest refresh. */
    LOADED("Loaded", null),
    ONE_MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    YEAR_TO_DATE("YTD", null),
    ONE_YEAR("1Y", 365),
    TWO_YEARS("2Y", 730),
    /** Explicit calendar start and end chosen by the trader. */
    CUSTOM("Custom", null);

    val isCustom: Boolean get() = this == CUSTOM
}

/** A resolved, absolute [startMillis]..[endMillis] research period. */
data class BacktestDateRange(
    val startMillis: Long,
    val endMillis: Long,
) {
    init {
        require(endMillis >= startMillis) { "Backtest range end must not precede its start." }
    }

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        /** UTC midnight at the start of [date]. */
        fun startOfDay(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        /** The last millisecond of [date], so a single-day range is inclusive. */
        fun endOfDay(date: LocalDate): Long =
            date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1L

        fun toLocalDate(millis: Long): LocalDate =
            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

        /**
         * Resolve [preset] to absolute bounds, or null when the whole loaded
         * series should be measured.
         *
         * [customStart]/[customEnd] are only read for [BacktestRangePreset.CUSTOM].
         * A custom range with either bound missing also resolves to null so the
         * run degrades to "everything loaded" instead of throwing while the
         * trader is still half-way through picking dates.
         */
        fun resolve(
            preset: BacktestRangePreset,
            customStart: Long?,
            customEnd: Long?,
            now: Long = System.currentTimeMillis(),
        ): BacktestDateRange? = when {
            preset == BacktestRangePreset.LOADED -> null
            preset == BacktestRangePreset.CUSTOM -> {
                if (customStart == null || customEnd == null) {
                    null
                } else {
                    // Tolerate reversed pickers rather than rejecting the run.
                    val start = minOf(customStart, customEnd)
                    val end = maxOf(customStart, customEnd)
                    BacktestDateRange(start, end)
                }
            }
            preset == BacktestRangePreset.YEAR_TO_DATE -> {
                val today = toLocalDate(now)
                BacktestDateRange(startOfDay(LocalDate.of(today.year, 1, 1)), now)
            }
            preset.days != null -> BacktestDateRange(now - preset.days * MILLIS_PER_DAY, now)
            else -> null
        }
    }
}

/**
 * Map an absolute date range onto bar indices of an ascending [candles] series.
 *
 * Returns null when the range selects fewer than two bars — a one-bar window
 * cannot produce an entry and an exit, so measuring it would report a
 * meaningless zero-trade result rather than an honest "no data" message.
 */
fun BacktestDateRange.toWindow(candles: List<Candle>): HistoricalTestWindow? {
    if (candles.isEmpty()) return null
    val startIndex = candles.indexOfFirst { it.timestamp >= startMillis }
    if (startIndex < 0) return null
    val endIndex = candles.indexOfLast { it.timestamp <= endMillis }
    if (endIndex < startIndex) return null
    if (endIndex - startIndex + 1 < 2) return null
    return HistoricalTestWindow(startIndex, endIndex)
}
