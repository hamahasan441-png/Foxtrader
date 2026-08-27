package com.foxtrader.app.domain.usecase.virginwick.model

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.KillZone
import com.foxtrader.app.domain.model.Timeframe

/** Which side of the body a wick sits on. */
enum class WickSide { UPPER, LOWER }

/**
 * An untested wick on the context timeframe.
 *
 * The region runs from the body edge to the extreme. It stays virgin only while
 * no later bar has traded back into it — that is the whole idea: price left
 * something behind and has not come back for it.
 */
data class VirginWick(
    val side: WickSide,
    val timeframe: Timeframe,
    /** Body edge; the near side of the region as price approaches it. */
    val proximal: Double,
    /** The wick's extreme; the far side of the region. */
    val distal: Double,
    /** Context-series index of the bar that formed it. */
    val contextIndex: Int,
    /** Execution index at which that bar closed and the wick became knowable. */
    val knownFromIndex: Int,
    val timestamp: Long,
) {
    val height: Double get() = kotlin.math.abs(distal - proximal)

    val low: Double get() = minOf(proximal, distal)

    val high: Double get() = maxOf(proximal, distal)

    /** A lower wick left behind is demand; an upper wick is supply. */
    val direction: Direction
        get() = if (side == WickSide.LOWER) Direction.BULLISH else Direction.BEARISH
}

/**
 * A virgin wick the context timeframe has closed away from, making it a point
 * of interest price is expected to return to.
 */
data class WickPoi(
    val wick: VirginWick,
    /** Execution index at which the activating context close became knowable. */
    val activatedAtIndex: Int,
    val activatingCloses: Int,
) {
    val direction: Direction get() = wick.direction

    /** Price at which price first enters the zone on its way back. */
    val proximal: Double get() = wick.proximal

    val distal: Double get() = wick.distal

    fun contains(price: Double): Boolean = price >= wick.low && price <= wick.high
}

/** The inverted fair value gap that confirmed the return. */
data class IfvgConfirmation(
    val direction: Direction,
    val high: Double,
    val low: Double,
    /** Execution index of the original gap. */
    val originIndex: Int,
    /** Execution index at which the gap inverted. */
    val inversionIndex: Int,
)

/** How the entry was confirmed. */
enum class VirginWickEntryType { POI_TOUCH, IFVG, IFVG_IN_POI }

/** Where the target came from. */
enum class TargetSource { DRAW_ON_LIQUIDITY, FIXED_MULTIPLE }

/** Formal states, so the model is a machine rather than scattered conditionals. */
enum class VirginWickState {
    IDLE,
    WICKS_MARKED,
    POI_ACTIVE,
    PRICE_RETURNED,
    CONFIRMED,
    EXPIRED,
}

/** A fully confirmed, tradeable setup. */
data class VirginWickSignal(
    val symbol: String,
    val executionTimeframe: Timeframe,
    val poi: WickPoi,
    val entryType: VirginWickEntryType,
    val ifvg: IfvgConfirmation?,
    val entryIndex: Int,
    val timestamp: Long,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val targetSource: TargetSource,
    val killZone: KillZone?,
    val reasons: List<String>,
) {
    val direction: Direction get() = poi.direction

    val risk: Double get() = kotlin.math.abs(entry - stop)

    val rewardMultiple: Double
        get() = if (risk <= 0.0) 0.0 else kotlin.math.abs(target - entry) / risk

    /**
     * Structural identity: the wick that created the setup plus the bar it was
     * entered on. Recalculation noise or a re-chosen target cannot manufacture
     * a second arrow for one confirmed return.
     */
    val key: String
        get() = "$symbol|${executionTimeframe.label}|${direction.name}|" +
            "${poi.wick.contextIndex}|${poi.wick.distal}|$entryIndex"
}

/** Everything the engine produced for one series. */
data class VirginWickAnalysis(
    val wicks: List<VirginWick>,
    val pois: List<WickPoi>,
    val signals: List<VirginWickSignal>,
    val state: VirginWickState,
    val statusText: String,
) {
    companion object {
        fun empty(reason: String) = VirginWickAnalysis(
            wicks = emptyList(),
            pois = emptyList(),
            signals = emptyList(),
            state = VirginWickState.IDLE,
            statusText = reason,
        )
    }
}
