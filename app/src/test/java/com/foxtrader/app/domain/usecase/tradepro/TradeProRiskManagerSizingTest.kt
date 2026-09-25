package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.DailyPerformance
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.PortfolioRiskState
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.repository.JournalRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hard risk limits in [TradeProRiskManager.assessPosition] are never exceeded. */
class TradeProRiskManagerSizingTest {

    private val manager = TradeProRiskManager(mockk<JournalRepository>(relaxed = true))

    private fun state(dailyPnl: Double = 0.0, openRisk: Double = 0.0, maxRiskBudget: Double = 30.0) =
        PortfolioRiskState(
            currentEquity = 100.0,
            dailyPnl = dailyPnl,
            openRisk = openRisk,
            maxRiskBudget = maxRiskBudget,
            correlationExposure = 0.0,
            consecutiveLosses = 0,
            tradesTakenToday = 0,
            riskUtilizationPercent = 0.0,
        )

    @Test
    fun `no contract is recommended when one stop would breach the daily loss limit`() {
        // 27-point daily limit, already down 25: 2 points left, one contract's
        // stop is 3. Forcing a 1-contract minimum risked 3 of the remaining 2.
        val config = TradeProConfig()
        val result = manager.assessPosition("NAS100", Direction.BULLISH, config, state(dailyPnl = -25.0))
        assertEquals(0, result.recommendedContracts)
        assertEquals(0.0, result.totalRiskPoints, 1e-9)
    }

    @Test
    fun `total risk never exceeds the remaining open-risk budget`() {
        for (openRisk in listOf(0.0, 10.0, 22.0, 25.0, 27.5, 29.0)) {
            val s = state(openRisk = openRisk)
            val result = manager.assessPosition("NAS100", Direction.BULLISH, TradeProConfig(), s)
            assertTrue(
                "openRisk=$openRisk: ${result.totalRiskPoints} > ${s.maxRiskBudget - openRisk}",
                result.totalRiskPoints <= s.maxRiskBudget - openRisk + 1e-9,
            )
        }
    }

    @Test
    fun `a scratch trade is not counted as a loss`() {
        val scratch = ManagedTrade(
            id = "scratch",
            symbol = "NAS100",
            direction = Direction.BULLISH,
            entryPrice = 100.0,
            entryTimestamp = 0L,
            contracts = 1,
            stopPrice = 97.0,
            t1Price = 104.0,
            t2Price = 108.0,
            runnerTarget = 116.0,
            currentPrice = 100.0,
            state = ManagedTradeState.CLOSED,
            realizedPoints = 0.0,
        )
        val updated = manager.updateDailyPerformance(scratch, DailyPerformance())
        assertEquals(1, updated.tradesTaken)
        assertEquals(0, updated.wins)
        assertEquals(0, updated.losses)
    }
}
