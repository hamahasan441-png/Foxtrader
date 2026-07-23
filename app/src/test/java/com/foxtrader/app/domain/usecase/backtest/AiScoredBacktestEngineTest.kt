package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.agents.MarketStructureAgent
import com.foxtrader.app.domain.usecase.ai.agents.TrendAgent
import com.foxtrader.app.domain.usecase.ai.agents.VolumeAgent
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.ai.agents.SmartMoneyAgent
import com.foxtrader.app.domain.usecase.ai.agents.IctAgent
import com.foxtrader.app.domain.usecase.ai.agents.RiskAgent
import com.foxtrader.app.domain.usecase.ai.agents.PsychologyAgent
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AiScoredBacktestEngine.
 * Validates that AI scoring is applied to each trade and metrics are computed.
 */
class AiScoredBacktestEngineTest {

    private lateinit var engine: AiScoredBacktestEngine
    private lateinit var backtestEngine: BacktestEngine

    @Before
    fun setup() {
        backtestEngine = BacktestEngine()
        val analyzeStructure = AnalyzeMarketStructureUseCase()
        val smcDetector = SmcDetector()
        val riskEngine = RiskEngine()

        val orchestrator = AgentOrchestrator().apply {
            registerAgent(MarketStructureAgent(analyzeStructure))
            registerAgent(TrendAgent())
            registerAgent(VolumeAgent())
            registerAgent(SmartMoneyAgent(smcDetector))
            registerAgent(IctAgent(smcDetector))
            registerAgent(RiskAgent(riskEngine))
            registerAgent(PsychologyAgent())
        }
        val decisionEngine = MasterDecisionEngine()

        engine = AiScoredBacktestEngine(backtestEngine, orchestrator, decisionEngine)
    }

    /** Generate trending candles that produce structure breaks + indicators. */
    private fun trendingCandles(n: Int, direction: Direction = Direction.BULLISH): List<Candle> {
        val step = if (direction == Direction.BULLISH) 0.2 else -0.2
        return (0 until n).map { i ->
            val base = 100.0 + step * i
            Candle(
                timestamp = i * 3_600_000L, // 1h bars
                open = base,
                high = base + 1.5,
                low = base - 0.5,
                close = base + 1.0,
                volume = 1000.0 + i * 10.0,
            )
        }
    }

    /** A simple strategy that enters every 30 bars. */
    private val simpleStrategy: StrategyFunction = { candles, i ->
        if (i >= 60 && i % 30 == 0) {
            val c = candles[i]
            val atr = (c.high - c.low)
            StrategySignal(
                index = i,
                timestamp = c.timestamp,
                direction = Direction.BULLISH,
                entry = c.close,
                stopLoss = c.close - atr * 2,
                takeProfit = c.close + atr * 3,
                setupType = "TEST",
            )
        } else null
    }

    @Test
    fun `AI scoring annotates all trades`() {
        val candles = trendingCandles(200)
        val result = engine(candles, simpleStrategy, "BTCUSDT", Timeframe.H1)

        assertTrue(result.aiScoringEnabled)
        assertTrue("Should have trades", result.trades.isNotEmpty())
        // Every trade should have aiApproved set (not null) since we have >=50 bars at entry
        for (trade in result.trades) {
            if (trade.entryIndex >= 50) {
                assertNotNull("AI should score trade at index ${trade.entryIndex}", trade.aiApproved)
                assertNotNull(trade.aiGrade)
            }
        }
    }

    @Test
    fun `AI approval rate is computed`() {
        val candles = trendingCandles(200)
        val result = engine(candles, simpleStrategy, "BTCUSDT", Timeframe.H1)

        assertNotNull(result.aiApprovalRate)
        assertTrue(
            "Approval rate should be in [0, 100]",
            result.aiApprovalRate!! in 0.0..100.0,
        )
    }

    @Test
    fun `empty trades produce no AI metrics`() {
        val candles = trendingCandles(200)
        val noSignals: StrategyFunction = { _, _ -> null }
        val result = engine(candles, noSignals, "BTCUSDT", Timeframe.H1)

        assertTrue(result.aiScoringEnabled)
        assertEquals(0, result.trades.size)
        assertEquals(null, result.aiApprovalRate)
        assertEquals(null, result.aiFilteredMetrics)
    }

    @Test
    fun `base metrics unchanged by AI scoring`() {
        val candles = trendingCandles(200)
        val baseResult = backtestEngine(candles, simpleStrategy, "BTCUSDT", Timeframe.H1)
        val aiResult = engine(candles, simpleStrategy, "BTCUSDT", Timeframe.H1)

        // Core trade count and net profit should be identical (AI doesn't filter execution).
        assertEquals(baseResult.trades.size, aiResult.trades.size)
        assertEquals(baseResult.metrics.netProfit, aiResult.metrics.netProfit, 0.001)
    }
}
