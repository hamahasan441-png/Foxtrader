package com.foxtrader.app.domain.sdk.wearable

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalGrade

/**
 * Wearable communication contract (Wear OS companion).
 *
 * Defines the data models sent from the phone to the watch via the
 * DataLayer API. The watch shows glanceable tiles/complications:
 * - Current AI bias + grade
 * - Last alert summary
 * - Watchlist top mover
 *
 * RULE: No chart interaction or trading from the wrist — read-only.
 */

/** Compact payload for a watch tile / complication. */
data class WatchTileData(
    val symbol: String,
    val bias: Bias,
    val grade: SignalGrade,
    val confidence: Int,
    val lastPrice: Double,
    val priceChangePercent: Double,
    val alertCount: Int,
    val timestamp: Long,
)

/** A simplified alert for watch notification. */
data class WatchAlert(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val grade: SignalGrade,
    val message: String,
    val timestamp: Long,
)

/** Contract paths for DataLayer messages. */
object WearPaths {
    const val TILE_DATA = "/fox/tile"
    const val ALERT = "/fox/alert"
    const val WATCHLIST = "/fox/watchlist"
}
