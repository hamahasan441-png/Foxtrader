package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.tradepro.AlertSeverity
import com.foxtrader.app.domain.model.tradepro.DailyPerformance
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.PortfolioRiskState
import com.foxtrader.app.domain.model.tradepro.PositionHeat
import com.foxtrader.app.domain.model.tradepro.RiskAlertType
import com.foxtrader.app.domain.model.tradepro.RiskDecision
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.repository.JournalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TradeProRiskManager].
 *
 * Style: JUnit4, backtick test names, direct construction, inline fixtures, NO MockK.
 * A [FakeJournalRepository] satisfies the constructor dependency without any mocking framework.
 */
class TradeProRiskManagerTest {

    // -----------------------------------------------------------------------
    // Fake repository (in-memory, no-op)
    // -----------------------------------------------------------------------

    private class FakeJournalRepository : JournalRepository {
        private val entries = mutableListOf<JournalEntry>()

        override fun observeEntries(): Flow<List<JournalEntry>> = flowOf(entries.toList())
        override suspend fun getAllEntries(): List<JournalEntry> = entries.toList()
        override suspend fun getModifiedSince(since: Long): List<JournalEntry> = emptyList()
        override suspend fun upsert(entry: JournalEntry) { entries.add(entry) }
        override suspend fun upsertAll(entries: List<JournalEntry>) { this.entries.addAll(entries) }
        override suspend fun delete(id: String) { entries.removeAll { it.id == id } }
        override suspend fun clear() { entries.clear() }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private val manager = TradeProRiskManager(FakeJournalRepository())
    private val config = TradeProConfig()

    private fun defaultState(
        dailyPnl: Double = 0.0,
        openRisk: Double = 0.0,
        maxRiskBudget: Double = 30.0,
        correlationExposure: Double = 0.0,
        consecutiveLosses: Int = 0,
        tradesTakenToday: Int = 0,
        currentEquity: Double = 100.0,
        riskUtilizationPercent: Double = 0.0,
    ) = PortfolioRiskState(
        currentEquity = currentEquity,
        dailyPnl = dailyPnl,
        openRisk = openRisk,
        maxRiskBudget = maxRiskBudget,
        correlationExposure = correlationExposure,
        consecutiveLosses = consecutiveLosses,
        tradesTakenToday = tradesTakenToday,
        riskUtilizationPercent = riskUtilizationPercent,
    )

    private fun makeTrade(
        symbol: String = "NAS100",
        direction: Direction = Direction.BULLISH,
        entryPrice: Double = 100.0,
        stopPrice: Double = 97.0,
        contracts: Int = 3,
        state: ManagedTradeState = ManagedTradeState.ACTIVE,
        realizedPoints: Double = 0.0,
    ) = ManagedTrade(
        id = "test-${System.nanoTime()}",
        symbol = symbol,
        direction = direction,
        entryPrice = entryPrice,
        entryTimestamp = System.currentTimeMillis(),
        contracts = contracts,
        stopPrice = stopPrice,
        t1Price = entryPrice + 4.0,
        t2Price = entryPrice + 8.0,
        runnerTarget = entryPrice + 16.0,
        currentPrice = entryPrice,
        state = state,
        realizedPoints = realizedPoints,
    )

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `Kelly sizing never exceeds max budget`() {
        // High win-rate scenario should still be capped.
        val state = defaultState(
            dailyPnl = 0.0,
            openRisk = 0.0,
            maxRiskBudget = 30.0,
        )

        val result = manager.assessPosition(
            symbol = "NAS100",
            direction = Direction.BULLISH,
            config = config,
            currentState = state,
        )

        // Total risk must not exceed maxRiskPoints from config.
        assertTrue(
            "Total risk ${result.totalRiskPoints} should be <= config.maxRiskPoints ${config.maxRiskPoints}",
            result.totalRiskPoints <= config.maxRiskPoints,
        )
        // Also verify it does not exceed the max single-trade budget fraction.
        val maxSingleTrade = config.maxRiskPoints * 0.40
        assertTrue(
            "Total risk ${result.totalRiskPoints} should be <= max single-trade budget $maxSingleTrade",
            result.totalRiskPoints <= maxSingleTrade + 0.01, // small epsilon for rounding
        )
        // Contracts must be positive.
        assertTrue("Contracts should be >= 1", result.recommendedContracts >= 1)
    }

    @Test
    fun `Daily limit triggers STAND_ASIDE after threshold`() {
        // Daily PnL at negative of the daily loss limit.
        val state = defaultState(dailyPnl = -config.maxDailyLossPoints)

        val decision = manager.shouldTrade(
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            state = state,
            config = config,
        )

        assertTrue(
            "Expected StandAside but got $decision",
            decision is RiskDecision.StandAside,
        )
        assertTrue(
            "Reason should mention daily loss limit",
            decision.reason.contains("Daily loss limit", ignoreCase = true),
        )
    }

    @Test
    fun `Correlation alerts fire when exposure exceeds 60 percent`() {
        val state = defaultState(correlationExposure = 0.75)

        val alerts = manager.checkRiskAlerts(state)

        val correlationAlert = alerts.find { it.type == RiskAlertType.CORRELATION }
        assertTrue(
            "Expected a CORRELATION alert but found none in $alerts",
            correlationAlert != null,
        )
        assertEquals(AlertSeverity.WARNING, correlationAlert!!.severity)
        assertTrue(
            "Alert value should reflect the correlation exposure",
            correlationAlert.value >= 0.60,
        )
    }

    @Test
    fun `Consecutive loss streak triggers warning`() {
        val state = defaultState(consecutiveLosses = 2)

        val alerts = manager.checkRiskAlerts(state)

        val streakAlert = alerts.find { it.type == RiskAlertType.STREAK }
        assertTrue(
            "Expected STREAK alert for 2 consecutive losses but found none in $alerts",
            streakAlert != null,
        )
        assertEquals(AlertSeverity.WARNING, streakAlert!!.severity)
    }

    @Test
    fun `Empty state returns safe defaults`() {
        val state = defaultState() // all zeros/defaults

        val decision = manager.shouldTrade(
            symbol = "BTCUSDT",
            direction = Direction.BULLISH,
            state = state,
            config = config,
        )

        assertTrue(
            "Expected Proceed but got $decision",
            decision is RiskDecision.Proceed,
        )

        val alerts = manager.checkRiskAlerts(state)
        val criticalAlerts = alerts.filter { it.severity == AlertSeverity.CRITICAL }
        assertTrue(
            "Expected no CRITICAL alerts for empty state but found $criticalAlerts",
            criticalAlerts.isEmpty(),
        )
    }

    @Test
    fun `Correlation exposure calculation detects correlated pairs`() {
        val positions = listOf(
            makeTrade(symbol = "EURUSD", direction = Direction.BULLISH),
            makeTrade(symbol = "GBPUSD", direction = Direction.BULLISH),
        )

        val groups = manager.calculateCorrelationExposure(positions)

        assertTrue(
            "Expected at least 1 correlation group for EURUSD+GBPUSD but found $groups",
            groups.isNotEmpty(),
        )
        assertTrue(
            "Group should contain both EURUSD and GBPUSD",
            groups.any { "EURUSD" in it.symbols && "GBPUSD" in it.symbols },
        )
    }

    @Test
    fun `Daily performance updates correctly after a winning trade`() {
        val previous = DailyPerformance(
            date = "2024-06-15",
            tradesTaken = 2,
            wins = 1,
            losses = 1,
            netPoints = 1.0,
            cumulativeEquity = 101.0,
            peakEquity = 101.0,
            drawdown = 0.0,
            complianceScore = 50.0,
        )

        val winningTrade = makeTrade(realizedPoints = 4.0, state = ManagedTradeState.CLOSED)
        val updated = manager.updateDailyPerformance(winningTrade, previous)

        assertEquals(3, updated.tradesTaken)
        assertEquals(2, updated.wins)
        assertEquals(1, updated.losses)
        assertEquals(5.0, updated.netPoints, 0.01)
        assertEquals(105.0, updated.cumulativeEquity, 0.01)
        assertEquals(105.0, updated.peakEquity, 0.01)
        assertEquals(0.0, updated.drawdown, 0.01)
    }

    @Test
    fun `shouldTrade returns REDUCE_SIZE when risk utilization is elevated`() {
        val state = defaultState(riskUtilizationPercent = 65.0)

        val decision = manager.shouldTrade(
            symbol = "US500",
            direction = Direction.BEARISH,
            state = state,
            config = config,
        )

        assertTrue(
            "Expected ReduceSize but got $decision",
            decision is RiskDecision.ReduceSize,
        )
    }

    @Test
    fun `Untracked symbol triggers INFO correlation alert`() {
        // USDZAR is not in the hardcoded correlation matrix.
        val state = defaultState().copy(
            positionHeat = listOf(
                PositionHeat(
                    symbol = "USDZAR",
                    direction = Direction.BULLISH,
                    riskPoints = 5.0,
                    heatPercent = 0.17,
                ),
            ),
        )

        val alerts = manager.checkRiskAlerts(state)

        val infoAlert = alerts.find {
            it.type == RiskAlertType.CORRELATION && it.severity == AlertSeverity.INFO
        }
        assertTrue(
            "Expected an INFO CORRELATION alert for untracked USDZAR but found none in $alerts",
            infoAlert != null,
        )
        assertTrue(
            "Alert message should mention untracked correlation",
            infoAlert!!.message.contains("Untracked correlation", ignoreCase = true),
        )
        assertTrue(
            "Alert message should reference USDZAR",
            infoAlert.message.contains("USDZAR"),
        )
    }

    @Test
    fun `Conservative Kelly sizing with 40 percent win rate does not oversize`() {
        // With 0.40 win rate and payoffRatio of 4/3:
        // rawKelly = (0.40 * 1.333 - 0.60) / 1.333 = (0.533 - 0.60) / 1.333 = -0.05
        // This is negative, so halfKelly = MIN_KELLY_FRACTION (0.01).
        // The sizing should produce a minimal position (1 contract).
        val state = defaultState(
            dailyPnl = 0.0,
            openRisk = 0.0,
            maxRiskBudget = 30.0,
        )

        val result = manager.assessPosition(
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            config = config,
            currentState = state,
        )

        // With a negative Kelly (forced to MIN_KELLY_FRACTION = 0.01),
        // the sizing should be very conservative (1 contract).
        assertEquals(
            "Expected 1 contract for conservative Kelly sizing",
            1,
            result.recommendedContracts,
        )
    }
}
