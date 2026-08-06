package com.foxtrader.app.domain.model

/**
 * Bar/candle construction methods supported by the chart engine.
 *
 * [TIME] is the classic fixed-timeframe candle. The remaining types are
 * "non-time" bars built from a tick stream, where a new bar is printed on a
 * price move ([RENKO], [RANGE]), on accumulated traded volume ([VOLUME]), or on
 * a fixed number of ticks ([TICK]). These reveal market *activity* rather than
 * the passage of time and are a staple of professional analysis platforms.
 *
 * This is an original implementation of well-established public charting
 * concepts — no third-party trading-platform code is used.
 */
enum class BarType(val label: String) {
    TIME("Time"),
    RENKO("Renko"),
    RANGE("Range"),
    VOLUME("Volume"),
    TICK("Tick"),
}
