package com.foxtrader.app.domain.model

/**
 * Unified chart signal combining first-class FOXTRADER analysis-system output
 * for display on the chart as live/history markers.
 */
data class ChartSignal(
    val id: String,
    val source: SignalSource,
    val direction: Direction,
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val barIndex: Int,
    val timestamp: Long,
    val confidence: Double,
    val isLive: Boolean,
    /**
     * Optional human-readable origin of the signal (e.g. the strategy name
     * "SMC Order Block Retest"). Rendered in the signal history panel so a
     * trader can tell *which* rule fired, not just which engine.
     */
    val label: String? = null,
    /**
     * Optional semantic identity shared by multiple integration paths that
     * represent the same objectively confirmed market event. Unlike [id], this
     * key is allowed to be common to a canonical engine signal and its
     * StrategyLibrary mirror so the chart can deduplicate them without changing
     * legacy IDs consumed by UI/tests/persistence.
     */
    val eventKey: String? = null,
    /**
     * Machine-readable rule-set identity within [source] — the LiT Adventure
     * mode that produced a LITX signal, for example.
     *
     * Deliberately separate from [label]: label is prose for the history panel
     * and is free to change wording, while accuracy is grouped on this. Grouping
     * statistics on a display string would silently re-partition every stored
     * result the first time someone reworded a label.
     *
     * Null means "the source has only one rule set", which is true of every
     * source except LITX.
     */
    val variant: String? = null,
) {
    /** Risk (entry → stop) in price units, or null when no stop is defined. */
    val risk: Double? get() = if (sl == 0.0) null else kotlin.math.abs(entry - sl)

    /** Reward (entry → target) in price units, or null when no target is defined. */
    val reward: Double? get() = if (tp == 0.0) null else kotlin.math.abs(tp - entry)

    /**
     * Realised reward-to-risk multiple of the signal, or null when either leg
     * is undefined (e.g. SMT markers carry no SL/TP).
     */
    val riskReward: Double?
        get() {
            val r = risk ?: return null
            val rw = reward ?: return null
            return if (r <= 0.0) null else rw / r
        }
}

enum class SignalSource {
    LITX,
    LIT,
    SMS,
    TRADEPRO,
    SMT,

    /** Confirmed RSI + candle-derived orderflow-proxy setup. */
    RSI_ORDERFLOW,

    /**
     * RSI Orderflow Reversal: an HTF price extreme RSI refused to confirm,
     * armed after an RSI structure break and entered on LTF confirmation.
     */
    RSI_REVERSAL,

    /**
     * Liquidity Sweep: higher-timeframe bias, a marked liquidity level taken
     * and reclaimed, entered on the reaction.
     */
    LIQUIDITY_SWEEP,

    /**
     * Virgin Wick: an untested higher-timeframe wick the market closed away
     * from, entered when price returns to it and an inversion confirms.
     */
    VIRGIN_WICK,

    /** Previous-day pivot sweep/reclaim + confirmed dual divergence setup. */
    PIVOT_SWEEP_DIVERGENCE,

    /** Previous-session value-area sweep/reclaim + absorption/structure setup. */
    VALUE_AREA_LIQUIDITY_REJECTION,

    /** Deriv 3-minute fixed-expiry CALL/PUT setup confirmed on a closed M1 bar. */
    BINARY3M,

    /** Accumulation-Manipulation-Distribution cycle: range sweep + displaced reversal. */
    ACCUMULATION_MANIPULATION_DISTRIBUTION,

    /** Nascent FX Primary Analysis: external key level + internal MSU/EPA setup. */
    NASCENT,

    /**
     * Apex: several methodologies independently agreeing on the same trade,
     * published only while the engine's own measured record supports the
     * configured hit rate.
     */
    APEX,

    /**
     * Compass: a primary call published only when a calibrated estimate of its
     * directional accuracy clears a threshold earned on past resolved calls.
     */
    COMPASS,

    /**
     * Crucible: a condition discovered by search, published only after
     * surviving purged out-of-sample testing, false-discovery control and a
     * measurement of the search's own tendency to overfit.
     */
    CRUCIBLE,

    /** A rule from the backtestable [com.foxtrader.app.domain.usecase.strategies.StrategyLibrary]. */
    STRATEGY,
}
