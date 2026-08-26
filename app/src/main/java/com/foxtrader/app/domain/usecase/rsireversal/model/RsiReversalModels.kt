package com.foxtrader.app.domain.usecase.rsireversal.model

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe

/**
 * One RSI Orderflow candle (§3.2).
 *
 * [open]/[close] come from RSI over the bar's own open/close series; [high] and
 * [low] are the extremes of all four RSI values, so the candle body is always
 * contained by its wicks by construction.
 */
data class RsiCandle(
    val index: Int,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
) {
    /** §3.2: bullish when the RSI close did not fall below the RSI open. */
    val isBullish: Boolean get() = close >= open
}

/** Which series a pivot was detected on. */
enum class PivotSeries { PRICE, RSI }

/**
 * A confirmed swing point.
 *
 * [confirmedIndex] is the bar at which the pivot became knowable — the pivot
 * bar plus the required right-hand bars. Nothing may act on a pivot before it.
 */
data class RsiReversalPivot(
    val series: PivotSeries,
    val isHigh: Boolean,
    val index: Int,
    val confirmedIndex: Int,
    val timestamp: Long,
    val value: Double,
)

/**
 * One numbered point of the master pattern: P1, P2, then the final price
 * extreme and each recursive extreme after it (§7, §9, §11).
 */
data class PatternPoint(
    /** 1-based ordinal: 1 = P1, 2 = P2, 4 = P4, 5 = P5, ... */
    val ordinal: Int,
    val index: Int,
    val timestamp: Long,
    val price: Double,
    val rsi: Double,
)

/** The RSI structure break, P3 (§8). */
data class RsiStructureBreak(
    val index: Int,
    val timestamp: Long,
    /** The protected RSI level that was broken. */
    val brokenLevel: Double,
    val rsiValue: Double,
)

/** Formal state machine states (§13). */
enum class RsiReversalState {
    IDLE,
    FOUND_P1,
    DIVERGENCE_FOUND,
    WAIT_RSI_STRUCTURE_BREAK,
    RSI_BREAK_CONFIRMED,
    WAIT_FINAL_PRICE_EXTREME,
    WAIT_RECURSIVE_EXTREME,
    ARMED,
    WAIT_LTF_CONFIRMATION,
    ENTRY_READY,
    EXPIRED,
    INVALIDATED,
}

/** Which lower-timeframe pattern produced the entry (§16, §17). */
enum class LtfConfirmationType {
    /** E1 — liquidity sweep then CHOCH. */
    SWEEP_CHOCH,

    /** E2 — sweep, displacement, then BOS. */
    SWEEP_DISPLACEMENT_BOS,

    /** E3 — CHOCH, higher low / lower high, BOS, retest. */
    CHOCH_BOS_RETEST,
}

/**
 * A higher-timeframe setup that reached ARMED, with the full point history that
 * produced it (§21).
 */
data class RsiReversalSetup(
    val direction: Direction,
    val symbol: String,
    val contextTimeframe: Timeframe,
    val p1: PatternPoint,
    val p2: PatternPoint,
    val p3: RsiStructureBreak,
    /** The extreme that armed the setup — P4, or a later recursive extreme. */
    val finalExtreme: PatternPoint,
    /** Recursive extremes beyond P4, in order. Empty for the direct pattern. */
    val recursiveExtremes: List<PatternPoint>,
    /** Bar on which the setup became armed and knowable. */
    val armedIndex: Int,
    val armedTimestamp: Long,
) {
    /** 0 for the direct P4 pattern, 1 for P5, and so on (§35). */
    val recursiveDepth: Int get() = recursiveExtremes.size

    /**
     * Stable identity of the setup (§30).
     *
     * Deliberately built from structural indices only, so recalculation noise,
     * entry geometry or confidence changes cannot manufacture a second arrow
     * for one objectively confirmed setup.
     */
    val key: String
        get() = "$symbol|${contextTimeframe.label}|${direction.name}|${p1.index}|${p2.index}|${finalExtreme.index}"
}

/** A fully confirmed, tradeable signal (§21). */
data class RsiReversalSignal(
    val setup: RsiReversalSetup,
    val entryTimeframe: Timeframe,
    val confirmationType: LtfConfirmationType,
    val entry: Double,
    val stop: Double,
    val target: Double,
    /** Bar index on the context timeframe that the arrow belongs to. */
    val contextIndex: Int,
    /** Bar timestamp on the entry timeframe at which confirmation closed. */
    val confirmedAt: Long,
    val reasons: List<String>,
) {
    val direction: Direction get() = setup.direction

    val risk: Double get() = kotlin.math.abs(entry - stop)

    val riskReward: Double
        get() = if (risk <= 0.0) 0.0 else kotlin.math.abs(target - entry) / risk

    /** Signal identity, inherited from the setup so it survives re-entry geometry. */
    val key: String get() = "${setup.key}|${confirmationType.name}|$confirmedAt"
}

/** Complete engine output for one series (§21, §45). */
data class RsiReversalAnalysis(
    val rsiCandles: List<RsiCandle>,
    val pricePivots: List<RsiReversalPivot>,
    val rsiPivots: List<RsiReversalPivot>,
    val armedSetups: List<RsiReversalSetup>,
    val signals: List<RsiReversalSignal>,
    val state: RsiReversalState,
    /** Human-readable current status for the compact status UI (§45). */
    val statusText: String,
)
