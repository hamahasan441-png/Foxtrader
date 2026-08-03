package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.ComplianceViolation
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.SimulatedTrade
import com.foxtrader.app.domain.model.tradepro.SimulatedTradeState
import com.foxtrader.app.domain.model.tradepro.SimulationPerformance
import com.foxtrader.app.domain.model.tradepro.SimulationSession
import com.foxtrader.app.domain.model.tradepro.SimulationSpeed
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.ViolationSeverity
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

/**
 * Interactive trade simulator that walks historical candles bar-by-bar, running the real
 * [TradeProSignalEngine] at each step to provide live analysis feedback while the user practices
 * the framework's discipline: reading zones, waiting for confirmation, managing the 3-contract
 * exit plan, and honouring daily risk limits.
 *
 * Unlike the backtest engine (fully automated), this is human-in-the-loop: the user decides when
 * to enter and how to manage; the engine provides feedback, tracks P&L, and scores compliance.
 *
 * Key invariants:
 * - The session never reveals bars beyond [currentBarIndex] (no look-ahead).
 * - Compliance is measured against the signal engine's concurrent analysis (was the entry inside
 *   a confirmed zone? Was the direction aligned with the Flip Zone? Was structure respected?).
 * - Performance metrics use the same formulas as [TradeProBacktestEngine] for consistency.
 */
class TradeProSimulationEngine @Inject constructor(
    private val signalEngine: TradeProSignalEngine,
) {

    /**
     * Create a new simulation session from the full candle history.
     * Starts at the earliest bar where the signal engine can produce a read.
     */
    fun createSession(
        symbol: String,
        candles: List<Candle>,
        config: TradeProConfig = TradeProConfig(),
        speed: SimulationSpeed = SimulationSpeed.NORMAL,
    ): SimulationSession {
        val startIndex = TradeProSignalEngine.MIN_BARS.coerceAtMost(candles.size)
        val visible = candles.subList(0, startIndex)
        val analysis = if (visible.size >= TradeProSignalEngine.MIN_BARS) {
            signalEngine.analyze(symbol, visible, config)
        } else {
            null
        }
        return SimulationSession(
            symbol = symbol,
            totalBars = candles.size,
            currentBarIndex = startIndex - 1,
            visibleCandles = visible.toList(),
            openTrade = null,
            closedTrades = emptyList(),
            equity = 0.0,
            peakEquity = 0.0,
            drawdown = 0.0,
            isComplete = startIndex >= candles.size,
            analysis = analysis,
            speed = speed,
        )
    }

    /**
     * Advance the simulation by one bar. Updates the open trade (if any) with the new bar's
     * price action — stop outs, target hits, and trail adjustments all happen here.
     */
    fun stepForward(
        session: SimulationSession,
        allCandles: List<Candle>,
        config: TradeProConfig = TradeProConfig(),
    ): SimulationSession {
        if (session.isComplete) return session

        val nextIndex = session.currentBarIndex + 1
        if (nextIndex >= allCandles.size) return session.copy(isComplete = true)

        val bar = allCandles[nextIndex]
        val visible = allCandles.subList(0, nextIndex + 1)
        val windowStart = max(0, nextIndex - ANALYSIS_WINDOW + 1)
        val window = allCandles.subList(windowStart, nextIndex + 1)
        val analysis = if (window.size >= TradeProSignalEngine.MIN_BARS) {
            signalEngine.analyze(session.symbol, window, config)
        } else {
            null
        }

        var openTrade = session.openTrade
        var closedTrades = session.closedTrades
        var equity = session.equity

        if (openTrade != null && openTrade.isOpen) {
            openTrade = updateTrade(openTrade, bar, config)
            if (!openTrade.isOpen) {
                equity += openTrade.realizedPoints
                closedTrades = closedTrades + openTrade
                openTrade = null
            }
        }

        val peakEquity = max(session.peakEquity, equity)
        val drawdown = peakEquity - equity

        return session.copy(
            currentBarIndex = nextIndex,
            visibleCandles = visible.toList(),
            openTrade = openTrade,
            closedTrades = closedTrades,
            equity = equity,
            peakEquity = peakEquity,
            drawdown = drawdown,
            isComplete = nextIndex >= allCandles.size - 1,
            analysis = analysis,
        )
    }

    /**
     * Place a new trade at the current bar's close price. Validates against the TRADEPRO framework
     * and returns the session with either an open trade or a compliance violation.
     */
    fun placeTrade(
        session: SimulationSession,
        direction: Direction,
        config: TradeProConfig = TradeProConfig(),
    ): Pair<SimulationSession, ComplianceViolation?> {
        if (session.openTrade != null) {
            return session to ComplianceViolation(
                barIndex = session.currentBarIndex,
                severity = ViolationSeverity.CRITICAL,
                rule = "ONE_POSITION",
                description = "Framework rule: one position at a time. Close the open trade first.",
            )
        }

        val price = session.currentPrice
        if (price <= 0.0) return session to null

        val violation = checkEntryCompliance(session, direction, config)

        val stopDistance = config.stopPoints * config.pointSize
        val stopLoss = if (direction == Direction.BULLISH) price - stopDistance else price + stopDistance
        val t1Distance = config.target1Points * config.pointSize
        val t2Distance = config.target2Points * config.pointSize
        val runnerDistance = config.runnerPoints * config.pointSize
        val target1 = if (direction == Direction.BULLISH) price + t1Distance else price - t1Distance
        val target2 = if (direction == Direction.BULLISH) price + t2Distance else price - t2Distance
        val runnerTarget = if (direction == Direction.BULLISH) price + runnerDistance else price - runnerDistance

        val trade = SimulatedTrade(
            id = UUID.randomUUID().toString(),
            direction = direction,
            entryPrice = price,
            entryBarIndex = session.currentBarIndex,
            contracts = config.contracts,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            runnerTarget = runnerTarget,
            currentPrice = price,
            state = SimulatedTradeState.ACTIVE,
            exitPrice = null,
            exitBarIndex = null,
            realizedPoints = 0.0,
            unrealizedPoints = 0.0,
            t1Hit = false,
            t2Hit = false,
            runnerHit = false,
            exitReason = null,
        )

        return session.copy(openTrade = trade) to violation
    }

    /**
     * Close the current open trade manually at the current market price.
     */
    fun closeManually(session: SimulationSession): SimulationSession {
        val trade = session.openTrade ?: return session
        val price = session.currentPrice
        val points = calculatePoints(trade.direction, trade.entryPrice, price) * trade.contracts
        val closed = trade.copy(
            currentPrice = price,
            exitPrice = price,
            exitBarIndex = session.currentBarIndex,
            state = SimulatedTradeState.CLOSED,
            realizedPoints = trade.realizedPoints + points - trade.unrealizedPoints * trade.contracts,
            unrealizedPoints = 0.0,
            exitReason = "Manual close",
        )
        val equity = session.equity + closed.realizedPoints - trade.realizedPoints
        val peakEquity = max(session.peakEquity, equity)
        return session.copy(
            openTrade = null,
            closedTrades = session.closedTrades + closed,
            equity = equity,
            peakEquity = peakEquity,
            drawdown = peakEquity - equity,
        )
    }

    /**
     * Move stop to breakeven (entry price). Only effective once T1 has been hit.
     */
    fun moveStopToBreakeven(session: SimulationSession): SimulationSession {
        val trade = session.openTrade ?: return session
        if (!trade.t1Hit) return session
        return session.copy(openTrade = trade.copy(stopLoss = trade.entryPrice))
    }

    /**
     * Compute the performance summary for all closed trades in the session.
     */
    fun computePerformance(session: SimulationSession, violations: List<ComplianceViolation>): SimulationPerformance {
        val trades = session.closedTrades
        if (trades.isEmpty()) return SimulationPerformance.EMPTY

        val total = trades.size
        val wins = trades.count { it.realizedPoints > 0.0 }
        val losses = trades.count { it.realizedPoints < 0.0 }
        val grossProfit = trades.filter { it.realizedPoints > 0.0 }.sumOf { it.realizedPoints }
        val grossLoss = trades.filter { it.realizedPoints < 0.0 }.sumOf { -it.realizedPoints }
        val netPoints = trades.sumOf { it.realizedPoints }
        val winRate = wins.toDouble() / total
        val expectancy = netPoints / total
        val profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else if (grossProfit > 0.0) Double.POSITIVE_INFINITY else 0.0

        val equityCurve = ArrayList<Double>(total)
        var running = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        for (t in trades) {
            running += t.realizedPoints
            equityCurve += running
            if (running > peak) peak = running
            val dd = peak - running
            if (dd > maxDrawdown) maxDrawdown = dd
        }

        val rMultiples = trades.map { t ->
            val riskPerContract = abs(t.entryPrice - t.stopLoss)
            val totalRisk = riskPerContract * t.contracts
            if (totalRisk > 0.0) t.realizedPoints / totalRisk else 0.0
        }
        val avgR = if (rMultiples.isNotEmpty()) rMultiples.sum() / rMultiples.size else 0.0

        val t1HitRate = trades.count { it.t1Hit }.toDouble() / total
        val t2HitRate = trades.count { it.t2Hit }.toDouble() / total
        val runnerHitRate = trades.count { it.runnerHit }.toDouble() / total

        val criticalViolations = violations.count { it.severity == ViolationSeverity.CRITICAL }
        val complianceScore = (100 - criticalViolations * 15).coerceIn(0, 100)

        val narrative = buildString {
            append("$total trades: $wins W / $losses L (${(winRate * 100).toInt()}% win rate). ")
            append("Net ${String.format(Locale.US, "%+.1f", netPoints)} pts, ")
            append("Avg R ${String.format(Locale.US, "%.2f", avgR)}. ")
            append("T1 ${(t1HitRate * 100).toInt()}% / T2 ${(t2HitRate * 100).toInt()}% / Runner ${(runnerHitRate * 100).toInt()}%. ")
            append("Compliance: $complianceScore%.")
            if (criticalViolations > 0) append(" ($criticalViolations framework violations.)")
        }

        return SimulationPerformance(
            totalTrades = total,
            wins = wins,
            losses = losses,
            winRate = winRate,
            netPoints = netPoints,
            expectancy = expectancy,
            profitFactor = if (profitFactor.isFinite()) profitFactor else 0.0,
            avgR = avgR,
            maxDrawdown = maxDrawdown,
            t1HitRate = t1HitRate,
            t2HitRate = t2HitRate,
            runnerHitRate = runnerHitRate,
            equityCurve = equityCurve,
            complianceScore = complianceScore,
            narrative = narrative,
        )
    }

    // --- Private helpers ---

    private fun updateTrade(trade: SimulatedTrade, bar: Candle, config: TradeProConfig): SimulatedTrade {
        val isLong = trade.direction == Direction.BULLISH
        var state = trade.state
        var t1Hit = trade.t1Hit
        var t2Hit = trade.t2Hit
        var runnerHit = trade.runnerHit
        var realizedPoints = trade.realizedPoints
        var stopLoss = trade.stopLoss
        var exitPrice: Double? = null
        var exitReason: String? = null

        // Check stop first (worst case: gap through stop).
        val stoppedOut = if (isLong) bar.low <= stopLoss else bar.high >= stopLoss
        if (stoppedOut) {
            exitPrice = stopLoss
            exitReason = when {
                t2Hit -> "Stopped (runner trailing)"
                t1Hit -> "Stopped (breakeven)"
                else -> "Stopped out"
            }
            state = SimulatedTradeState.CLOSED
        }

        // T1 hit (partial close: 1/3 of contracts).
        if (!stoppedOut && !t1Hit) {
            val hitT1 = if (isLong) bar.high >= trade.target1 else bar.low <= trade.target1
            if (hitT1) {
                t1Hit = true
                val partial = calculatePoints(trade.direction, trade.entryPrice, trade.target1)
                realizedPoints += partial // 1 contract closed at T1
                state = SimulatedTradeState.T1_HIT
                stopLoss = trade.entryPrice // move to breakeven after T1
            }
        }

        // T2 hit (another 1/3 of contracts).
        if (!stoppedOut && t1Hit && !t2Hit) {
            val hitT2 = if (isLong) bar.high >= trade.target2 else bar.low <= trade.target2
            if (hitT2) {
                t2Hit = true
                val partial = calculatePoints(trade.direction, trade.entryPrice, trade.target2)
                realizedPoints += partial // 1 contract closed at T2
                state = SimulatedTradeState.T2_HIT
            }
        }

        // Runner target hit (final 1/3).
        if (!stoppedOut && t2Hit && !runnerHit) {
            val hitRunner = if (isLong) bar.high >= trade.runnerTarget else bar.low <= trade.runnerTarget
            if (hitRunner) {
                runnerHit = true
                val partial = calculatePoints(trade.direction, trade.entryPrice, trade.runnerTarget)
                realizedPoints += partial
                exitPrice = trade.runnerTarget
                exitReason = "Runner target reached"
                state = SimulatedTradeState.CLOSED
            }
        }

        // Trail stop for runner (after T2, trail behind recent swing).
        if (!stoppedOut && t2Hit && !runnerHit && state != SimulatedTradeState.CLOSED) {
            val trailDistance = config.stopPoints * config.pointSize * 1.5
            val newStop = if (isLong) bar.close - trailDistance else bar.close + trailDistance
            val betterStop = if (isLong) newStop > stopLoss else newStop < stopLoss
            if (betterStop) stopLoss = newStop
            state = SimulatedTradeState.RUNNER
        }

        val currentPrice = bar.close
        val unrealizedPerContract = calculatePoints(trade.direction, trade.entryPrice, currentPrice)
        val remainingContracts = when {
            runnerHit || state == SimulatedTradeState.CLOSED -> 0
            t2Hit -> 1
            t1Hit -> 2
            else -> trade.contracts
        }
        val unrealizedPoints = unrealizedPerContract * remainingContracts

        return trade.copy(
            currentPrice = currentPrice,
            state = state,
            stopLoss = stopLoss,
            t1Hit = t1Hit,
            t2Hit = t2Hit,
            runnerHit = runnerHit,
            realizedPoints = realizedPoints,
            unrealizedPoints = unrealizedPoints,
            exitPrice = exitPrice,
            exitBarIndex = if (state == SimulatedTradeState.CLOSED) null else trade.exitBarIndex,
            exitReason = exitReason,
        )
    }

    private fun checkEntryCompliance(
        session: SimulationSession,
        direction: Direction,
        config: TradeProConfig,
    ): ComplianceViolation? {
        val analysis = session.analysis ?: return null

        // Check if entering against the Flip Zone bias.
        val flipZone = analysis.flipZone
        if (flipZone != null) {
            val flipBullish = flipZone.bias == com.foxtrader.app.domain.model.Bias.BULLISH
            val aligned = (direction == Direction.BULLISH && flipBullish) ||
                (direction == Direction.BEARISH && !flipBullish)
            if (!aligned) {
                return ComplianceViolation(
                    barIndex = session.currentBarIndex,
                    severity = ViolationSeverity.CRITICAL,
                    rule = "FLIP_ZONE_ALIGNMENT",
                    description = "Entry opposes the Flip Zone bias. The framework says: trade WITH the bias, not against it.",
                )
            }
        }

        // Check if the setup hasn't reached at least ZONE stage.
        val setup = analysis.setup
        if (setup == null || setup.stage.ordinal < SetupStage.ZONE.ordinal) {
            return ComplianceViolation(
                barIndex = session.currentBarIndex,
                severity = ViolationSeverity.WARNING,
                rule = "ZONE_ENTRY",
                description = "Entering without price being at a confirmed zone. The framework says: no zone, no trade.",
            )
        }

        // Check daily loss limit.
        val dailyLoss = session.closedTrades
            .filter { it.exitBarIndex != null }
            .sumOf { it.realizedPoints }
        if (dailyLoss <= -config.maxDailyLossPoints) {
            return ComplianceViolation(
                barIndex = session.currentBarIndex,
                severity = ViolationSeverity.CRITICAL,
                rule = "DAILY_LIMIT",
                description = "Daily loss limit exceeded (${String.format(Locale.US, "%.1f", -dailyLoss)} pts). The framework says: stop for the day.",
            )
        }

        return null
    }

    private fun calculatePoints(direction: Direction, entry: Double, exit: Double): Double {
        return if (direction == Direction.BULLISH) exit - entry else entry - exit
    }

    companion object {
        private const val ANALYSIS_WINDOW = 250
    }
}
