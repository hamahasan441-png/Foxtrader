package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction

/**
 * Current state of an interactive trade simulation session. The user walks through history bar-by-bar,
 * placing/managing trades in real-time against the full TRADEPRO framework, building muscle memory
 * for zone entries, T1/T2/runner management, and stop discipline — without real capital.
 */
data class SimulationSession(
    val symbol: String,
    val totalBars: Int,
    val currentBarIndex: Int,
    /** All bars up to and including [currentBarIndex]. */
    val visibleCandles: List<Candle>,
    val openTrade: SimulatedTrade?,
    val closedTrades: List<SimulatedTrade>,
    val equity: Double,
    val peakEquity: Double,
    val drawdown: Double,
    val isComplete: Boolean,
    val analysis: TradeProAnalysis?,
    val speed: SimulationSpeed,
) {
    val currentPrice: Double get() = visibleCandles.lastOrNull()?.close ?: 0.0
    val progress: Float get() = if (totalBars > 0) currentBarIndex.toFloat() / totalBars else 0f
    val barsSinceEntry: Int get() = openTrade?.let { currentBarIndex - it.entryBarIndex } ?: 0
}

/**
 * A trade opened within the simulation — tracks lifecycle from entry through managed exits.
 */
data class SimulatedTrade(
    val id: String,
    val direction: Direction,
    val entryPrice: Double,
    val entryBarIndex: Int,
    val contracts: Int,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val runnerTarget: Double,
    val currentPrice: Double,
    val state: SimulatedTradeState,
    val exitPrice: Double?,
    val exitBarIndex: Int?,
    val realizedPoints: Double,
    val unrealizedPoints: Double,
    val t1Hit: Boolean,
    val t2Hit: Boolean,
    val runnerHit: Boolean,
    val exitReason: String?,
) {
    val isOpen: Boolean get() = state != SimulatedTradeState.CLOSED
    val totalPoints: Double get() = realizedPoints + unrealizedPoints
}

enum class SimulatedTradeState {
    ACTIVE,
    T1_HIT,
    T2_HIT,
    RUNNER,
    CLOSED,
}

enum class SimulationSpeed(val label: String, val delayMs: Long) {
    SLOW("Slow", 1500L),
    NORMAL("Normal", 800L),
    FAST("Fast", 300L),
    INSTANT("Instant", 50L),
}

/**
 * Performance summary for the simulation session — mirrors the backtest report structure so the
 * trader sees the same metrics they'd evaluate in a real run. This is the "score" of their practice.
 */
data class SimulationPerformance(
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val netPoints: Double,
    val expectancy: Double,
    val profitFactor: Double,
    val avgR: Double,
    val maxDrawdown: Double,
    val t1HitRate: Double,
    val t2HitRate: Double,
    val runnerHitRate: Double,
    val equityCurve: List<Double>,
    val complianceScore: Int,
    val narrative: String,
) {
    companion object {
        val EMPTY = SimulationPerformance(
            totalTrades = 0, wins = 0, losses = 0, winRate = 0.0,
            netPoints = 0.0, expectancy = 0.0, profitFactor = 0.0, avgR = 0.0,
            maxDrawdown = 0.0, t1HitRate = 0.0, t2HitRate = 0.0, runnerHitRate = 0.0,
            equityCurve = emptyList(), complianceScore = 100, narrative = "No trades yet.",
        )
    }
}

/**
 * Actions the user can perform during a simulation session.
 */
sealed class SimulationAction {
    data object StepForward : SimulationAction()
    data object Play : SimulationAction()
    data object Pause : SimulationAction()
    data class ChangeSpeed(val speed: SimulationSpeed) : SimulationAction()
    data class PlaceTrade(val direction: Direction) : SimulationAction()
    data object CloseTradeManually : SimulationAction()
    data object MoveStopToBreakeven : SimulationAction()
    data class SetStop(val price: Double) : SimulationAction()
}

/**
 * Compliance violations detected during simulation — feedback on whether the trader followed
 * the framework's rules or deviated (chasing, wrong zone, ignoring confirmation, etc.)
 */
data class ComplianceViolation(
    val barIndex: Int,
    val severity: ViolationSeverity,
    val rule: String,
    val description: String,
)

enum class ViolationSeverity { WARNING, CRITICAL }
