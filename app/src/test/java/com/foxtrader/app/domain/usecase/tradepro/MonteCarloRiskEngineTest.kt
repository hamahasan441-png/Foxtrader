package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.tradepro.RiskSimulationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonteCarloRiskEngineTest {

    private val engine = MonteCarloRiskEngine()

    private fun input(
        winRate: Double = 0.5,
        avgWinR: Double = 2.0,
        avgLossR: Double = 1.0,
        risk: Double = 0.01,
        trades: Int = 100,
        runs: Int = 500,
        ruin: Double = 0.5,
    ) = RiskSimulationInput(winRate, avgWinR, avgLossR, risk, trades, runs, ruin)

    @Test
    fun `invalid input returns empty result`() {
        val result = engine.simulate(input(winRate = 1.5))
        assertEquals(0, result.runsSimulated)
    }

    @Test
    fun `deterministic for the same seed`() {
        val a = engine.simulate(input(), seed = 42L)
        val b = engine.simulate(input(), seed = 42L)
        assertEquals(a.riskOfRuinFraction, b.riskOfRuinFraction, 0.0)
        assertEquals(a.medianEndMultiple, b.medianEndMultiple, 0.0)
        assertEquals(a.p95MaxDrawdownFraction, b.p95MaxDrawdownFraction, 0.0)
    }

    @Test
    fun `a pure winning edge never ruins`() {
        val result = engine.simulate(input(winRate = 1.0, avgWinR = 1.0, avgLossR = 1.0))
        assertEquals(0.0, result.riskOfRuinFraction, 0.0)
        assertTrue(result.medianEndMultiple > 1.0)
        assertTrue(result.profitableRunFraction > 0.99)
    }

    @Test
    fun `a pure losing edge ruins in every run`() {
        // Always loses; risking 5% compounding for 200 trades will breach the -50% ruin line.
        val result = engine.simulate(input(winRate = 0.0, risk = 0.05, trades = 200, ruin = 0.5))
        assertTrue("expected near-certain ruin", result.riskOfRuinFraction > 0.99)
    }

    @Test
    fun `expectancy is computed from win rate and payoff`() {
        val result = engine.simulate(input(winRate = 0.5, avgWinR = 2.0, avgLossR = 1.0))
        // 0.5*2 - 0.5*1 = 0.5R
        assertEquals(0.5, result.expectancyR, 1e-9)
    }

    @Test
    fun `percentile bands are monotonically ordered`() {
        val result = engine.simulate(input(winRate = 0.45, avgWinR = 2.0, avgLossR = 1.0, runs = 1000))
        assertTrue(result.p5EndMultiple <= result.p25EndMultiple)
        assertTrue(result.p25EndMultiple <= result.medianEndMultiple)
        assertTrue(result.medianEndMultiple <= result.p75EndMultiple)
        assertTrue(result.p75EndMultiple <= result.p95EndMultiple)
    }

    @Test
    fun `higher risk-per-trade increases risk of ruin for the same edge`() {
        val low = engine.simulate(input(winRate = 0.45, risk = 0.005, runs = 1500), seed = 7L)
        val high = engine.simulate(input(winRate = 0.45, risk = 0.05, runs = 1500), seed = 7L)
        assertTrue(
            "more risk per trade should not reduce ruin",
            high.riskOfRuinFraction >= low.riskOfRuinFraction,
        )
    }

    @Test
    fun `drawdown fractions are within 0 to 1`() {
        val result = engine.simulate(input(runs = 800))
        assertTrue(result.medianMaxDrawdownFraction in 0.0..1.0)
        assertTrue(result.p95MaxDrawdownFraction in 0.0..1.0)
        assertTrue(result.p95MaxDrawdownFraction >= result.medianMaxDrawdownFraction)
    }

    @Test
    fun `sample equity curves are captured for plotting`() {
        val result = engine.simulate(input(runs = 500, trades = 50))
        assertTrue(result.sampleEquityCurves.isNotEmpty())
        // Each curve has trades + 1 points (starting equity + one per trade).
        result.sampleEquityCurves.forEach { assertEquals(51, it.size) }
    }

    @Test
    fun `risk of ruin is a valid probability`() {
        val result = engine.simulate(input())
        assertTrue(result.riskOfRuinFraction in 0.0..1.0)
        assertTrue(result.profitableRunFraction in 0.0..1.0)
    }

    // -------------------------------------------------------------------------
    // EDGE CASES
    // -------------------------------------------------------------------------

    @Test
    fun `zero risk per trade keeps equity flat`() {
        val result = engine.simulate(input(winRate = 0.5, risk = 0.0, trades = 100, runs = 100))
        assertEquals(1.0, result.medianEndMultiple, 1e-9)
        assertEquals(0.0, result.riskOfRuinFraction, 0.0)
        assertEquals(0.0, result.medianMaxDrawdownFraction, 1e-9)
    }

    @Test
    fun `single trade per run with a winning edge`() {
        // 100% win rate, 1 trade per run: equity = 1 + risk * avgWinR
        val risk = 0.02
        val avgWin = 2.0
        val result = engine.simulate(input(winRate = 1.0, avgWinR = avgWin, avgLossR = 1.0, risk = risk, trades = 1, runs = 500))
        val expectedEnd = 1.0 + risk * avgWin
        assertEquals(expectedEnd, result.medianEndMultiple, 1e-9)
        assertEquals(0.0, result.riskOfRuinFraction, 0.0)
    }

    @Test
    fun `boundary input with winRate exactly 0 and avgLossR 0 keeps equity flat`() {
        // winRate=0 means always losing, but avgLossR=0 means loss magnitude is zero.
        val result = engine.simulate(input(winRate = 0.0, avgWinR = 2.0, avgLossR = 0.0, risk = 0.05, trades = 50, runs = 200))
        assertEquals(1.0, result.medianEndMultiple, 1e-9)
        assertEquals(0.0, result.riskOfRuinFraction, 0.0)
    }

    @Test
    fun `single run returns consistent percentiles`() {
        val result = engine.simulate(input(winRate = 0.6, avgWinR = 1.5, avgLossR = 1.0, runs = 1), seed = 99L)
        assertEquals(1, result.runsSimulated)
        // With a single run, all percentile bands collapse to the same value.
        assertEquals(result.medianEndMultiple, result.p5EndMultiple, 1e-9)
        assertEquals(result.medianEndMultiple, result.p95EndMultiple, 1e-9)
    }
}
