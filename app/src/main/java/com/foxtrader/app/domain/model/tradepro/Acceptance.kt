package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Direction

/** Outcome of testing whether a level break was truly accepted or merely a wick/rejection. */
enum class AcceptanceState {
    /** Price held beyond the level, formed new structure, and defended a pullback. */
    ACCEPTED,

    /** Price failed to hold / snapped back through the level — a false break. */
    REJECTED,

    /** Not enough bars yet to decide. */
    PENDING,
}

/**
 * Result of an acceptance test around a level.
 *
 * "Breaking a level is easy; acceptance is what happens after." Acceptance requires: (a) price holds
 * time beyond the level, (b) new structure forms beyond it (higher-low for bulls / lower-high for
 * bears), and (c) pullbacks into the level are defended. If any fail, it is rejection — information,
 * not a trend change.
 */
data class AcceptanceResult(
    val state: AcceptanceState,
    val level: Double,
    val direction: Direction,
    val barsHeld: Int,
    val formedNewStructure: Boolean,
    val defendedPullback: Boolean,
    val detail: String,
) {
    val isAccepted: Boolean get() = state == AcceptanceState.ACCEPTED
}
