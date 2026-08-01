package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Direction

/**
 * Configuration for the TRADEPRO engines. Defaults follow the course's baseline for an ES/MES-style
 * instrument where 1 point = 1.0 price unit; [pointSize] rescales for other instruments.
 *
 * All risk is expressed in *points*, not dollars — points don't change with contract size, so the plan
 * stays stable as size scales.
 */
data class TradeProConfig(
    /** Price units per "point". 1.0 for ES/MES; set smaller for FX/crypto to keep point-math sane. */
    val pointSize: Double = 1.0,
    /** Structural stop distance in points (baseline 3, widening to 4-5 in high volatility). */
    val stopPoints: Double = 3.0,
    /** First target in points (T1). */
    val target1Points: Double = 4.0,
    /** Second target in points (T2). */
    val target2Points: Double = 8.0,
    /** Runner target in points when no structural magnet is found. */
    val runnerPoints: Double = 16.0,
    /** Number of contracts split into thirds (T1 / T2 / runner). */
    val contracts: Int = 3,
    /** Baseline total risk budget per trade in points (~10). */
    val maxRiskPoints: Double = 10.0,
    /** Imbalance qualifies when dominant volume >= ratio * opposing volume. */
    val imbalanceRatio: Double = 3.0,
    /** Minimum bars a break must hold before acceptance can be granted. */
    val acceptanceMinBars: Int = 2,
    /** Left/right bars for swing detection feeding the Flip Zone. */
    val swingLookback: Int = 5,
    // --- Edge protection / daily limits ---
    /** Stop for the day after this many consecutive losses. */
    val maxConsecutiveLosses: Int = 3,
    /** Stop for the day after this many points of cumulative loss (~3 trades at 9 pts risk). */
    val maxDailyLossPoints: Double = 27.0,
    /** Step back if plan compliance drops below this percent over a rolling window. */
    val minCompliancePercent: Double = 70.0,
) {
    init {
        require(pointSize > 0.0) { "pointSize must be > 0" }
        require(stopPoints > 0.0) { "stopPoints must be > 0" }
        require(contracts >= 1) { "contracts must be >= 1" }
    }
}

/** Where a setup is in the Level -> Zone -> Confirmation -> Execute pipeline. */
enum class SetupStage {
    /** No qualifying level/bias yet. */
    NONE,

    /** A level and directional bias exist, but price has not reached the zone. No trade — no chasing. */
    LEVEL,

    /** Price is at/near the Buy-Hold or Sell-Hold zone; awaiting order-flow confirmation. */
    ZONE,

    /** Zone reached and order flow confirms (imbalance / absorption / acceptance). Ready. */
    CONFIRMATION,

    /** All conditions met — an executable setup with entry, stop and targets. */
    EXECUTE,
}

/**
 * The 3-contract trade-management plan: split the position into thirds. T1 banks at [t1Points],
 * T2 banks at [t2Points], the final third rides as a runner. Two-thirds de-risk early; one-third is
 * pure upside. Stop is not touched until T1 fills; moved to break-even only after T2 fills; only the
 * runner is trailed (behind imbalance clusters — more room on trend days, tighter on range days).
 */
data class TradeProManagementPlan(
    val contracts: Int,
    val stopPoints: Double,
    val t1Points: Double,
    val t2Points: Double,
    val t1Contracts: Int,
    val t2Contracts: Int,
    val runnerContracts: Int,
    val totalRiskPoints: Double,
    /** Win rate needed for T1+T2 alone to break even, given the stop. */
    val breakevenWinRate: Double,
)

/**
 * A fully-qualified TRADEPRO trade setup. Prices are absolute; risk is also reported in points.
 * Only setups at [SetupStage.EXECUTE] are tradable; earlier stages are informational (respect the
 * golden rule: if price hasn't reached the zone, there is no trade).
 */
data class TradeProSetup(
    val symbol: String,
    val direction: Direction,
    val stage: SetupStage,
    val entry: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val runnerTarget: Double,
    val riskPoints: Double,
    val riskReward: Double,
    val confidence: Int,
    val flipZone: FlipZone?,
    val holdZone: HoldZone?,
    val managementPlan: TradeProManagementPlan,
    val confluences: List<String>,
    val note: String,
) {
    val isExecutable: Boolean get() = stage == SetupStage.EXECUTE
}

/**
 * Full TRADEPRO read of the market at the current bar: the day-defining Flip Zone, all Buy/Sell Hold
 * zones, detected imbalances and absorption, and the current setup (if any) with its pipeline stage.
 */
data class TradeProAnalysis(
    val symbol: String,
    val flipZone: FlipZone?,
    val holdZones: List<HoldZone>,
    val imbalances: List<Imbalance>,
    val absorptions: List<AbsorptionEvent>,
    val setup: TradeProSetup?,
    val stage: SetupStage,
    val narrative: String,
) {
    companion object {
        fun empty(symbol: String, reason: String): TradeProAnalysis = TradeProAnalysis(
            symbol = symbol,
            flipZone = null,
            holdZones = emptyList(),
            imbalances = emptyList(),
            absorptions = emptyList(),
            setup = null,
            stage = SetupStage.NONE,
            narrative = reason,
        )
    }
}
