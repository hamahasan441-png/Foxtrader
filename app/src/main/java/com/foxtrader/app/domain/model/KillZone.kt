package com.foxtrader.app.domain.model

/**
 * ICT Kill Zones — high-probability intraday windows (UTC) during which
 * institutional order flow concentrates. Traders watch these windows for
 * liquidity sweeps and structure shifts.
 *
 * Hours are half-open windows `[startHourUtc, endHourUtc)`.
 *
 * Pure domain model — no Android dependencies.
 */
enum class KillZone(
    val label: String,
    val startHourUtc: Int,
    val endHourUtc: Int,
) {
    ASIAN_RANGE("Asian Range", 0, 5),
    LONDON_OPEN("London Open", 7, 10),
    NEW_YORK_OPEN("New York Open", 12, 15),
    LONDON_CLOSE("London Close", 15, 17),
}

/**
 * A kill-zone time range for a specific occurrence, expressed in bar indices.
 *
 * @param zone The kill zone this range belongs to.
 * @param startIndex First bar index within the window.
 * @param endIndex Last bar index within the window.
 * @param high Highest candle high over the window.
 * @param low Lowest candle low over the window.
 */
data class KillZoneRange(
    val zone: KillZone,
    val startIndex: Int,
    val endIndex: Int,
    val high: Double,
    val low: Double,
)
