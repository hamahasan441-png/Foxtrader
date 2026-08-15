package com.foxtrader.app.domain.usecase.execution

/**
 * Policy knobs governing live order execution. Every knob is conservative by
 * default: live mode is off, the kill switch is disengaged only when false, and
 * fresh confirmation is required. These defaults mean a newly constructed
 * policy can never let an order through by accident — it must be explicitly
 * configured (and, per the product's safety stance, the persisted live-mode
 * setting is not yet wired up).
 */
data class ExecutionPolicy(
    /**
     * Master gate. When false, no live order may be placed regardless of any
     * other setting. This stays false until the broker adapter, audit log, and
     * reconciliation are complete.
     */
    val liveModeEnabled: Boolean = false,
    /**
     * Emergency stop. When true, no live order may be placed. Set by the UI's
     * kill switch and cleared only by explicit user action.
     */
    val emergencyKillSwitch: Boolean = false,
    /** Whether a fresh, explicit user confirmation is mandatory. */
    val requireFreshConfirmation: Boolean = true,
    /** Max age (ms) of the confirmation before it is considered stale. */
    val confirmationMaxAgeMs: Long = 60_000L,
    /** Max age (ms) of the reference quote before it is considered stale. */
    val staleQuoteMaxAgeMs: Long = 5_000L,
    /** Ceiling on the day's realized loss (account currency); 0 disables the gate. */
    val maxDailyLossInAccountCurrency: Double = 0.0,
    /** Minimum free margin required to place an order (account currency); 0 disables. */
    val minFreeMarginInAccountCurrency: Double = 0.0,
)
