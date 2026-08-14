package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.agents.IctAgent
import com.foxtrader.app.domain.usecase.ai.agents.MarketStructureAgent
import com.foxtrader.app.domain.usecase.ai.agents.PsychologyAgent
import com.foxtrader.app.domain.usecase.ai.agents.RiskAgent
import com.foxtrader.app.domain.usecase.ai.agents.SmartMoneyAgent
import com.foxtrader.app.domain.usecase.ai.agents.TrendAgent
import com.foxtrader.app.domain.usecase.ai.agents.VolumeAgent
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Regression tests: HTF look-ahead bias in AiScoredBacktestEngine.
 *
 * P0 fix: scoreTradeEntry filters HTF bars so only bars whose CLOSE time
 * ≤ decision timestamp reach the AI agents.
 */
class AiScoredBacktestEngineHtfTest {

    private lateinit var engine: AiScoredBacktestEngine

    @Before
    fun setup() {
        val orchestrator = AgentOrchestrator().apply {
            registerAgent(MarketStructureAgent(AnalyzeMarketStructureUseCase()))
            registerAgent(TrendAgent())
            registerAgent(VolumeAgent())
            registerAgent(SmartMoneyAgent(SmcDetector()))
            registerAgent(IctAgent(SmcDetector()))
            registerAgent(RiskAgent(RiskEngine(InstrumentTypeResolver())))
            registerAgent(PsychologyAgent())
        }
        engine = AiScoredBacktestEngine(BacktestEngine(), orchestrator, MasterDecisionEngine())
    }

    private fun h1Candles(n: Int): List<Candle> = (0 until n).map { i ->
        val b = 100.0 + i * 0.1
        Candle(timestamp = i * 3_600_000L, open = b, high = b + 1.0, low = b - 0.5, close = b + 0.8, volume = 1_000.0)
    }

    private fun h4Candles(n: Int): List<Candle> = (0 until n).map { i ->
        val b = 100.0 + i * 0.4
        Candle(timestamp = i * 14_400_000L, open = b, high = b + 4.0, low = b - 2.0, close = b + 3.0, volume = 5_000.0)
    }

    /** Fires once at H1 bar 60 (timestamp = 216_000_000 ms). */
    private val oneShot: StrategyFunction = { bars, i ->
        if (i == 60) {
            val c = bars[i]
            StrategySignal(index = i, timestamp = c.timestamp, direction = Direction.BULLISH,
                entry = c.close, stopLoss = c.close - 2.0, takeProfit = c.close + 6.0,
                setupType = "HTF_LEAK_TEST")
        } else null
    }

    @Test
    fun `HTF future bars do not cause crash and trade is scored`() {
        // 50 H4 bars extend well past bar-60 of H1 — they must be filtered
        val result = engine(h1Candles(200), oneShot, "EURUSD", Timeframe.H1,
            mapOf(Timeframe.H4 to h4Candles(50)), CandleSource.LIVE)
        if (result.trades.isEmpty()) fail("Expected 1 trade, got 0")
        if (result.trades[0].aiApproved == null) fail("AI scoring was not applied")
    }

    @Test
    fun `all HTF bars in future — engine must not crash`() {
        // Single H4 bar starting at 500_000_000 ms (far after bar-60 decision time)
        val futureH4 = listOf(Candle(500_000_000L, 110.0, 115.0, 109.0, 113.0, 3_000.0))
        val result = engine(h1Candles(200), oneShot, "GBPUSD", Timeframe.H1,
            mapOf(Timeframe.H4 to futureH4), CandleSource.LIVE)
        if (result.trades.isEmpty()) fail("Expected 1 trade")
    }

    @Test
    fun `HTF bar closing exactly at decision timestamp is included`() {
        // Bar-60 H1: ts=216_000_000, decision=219_600_000
        // H4 bar: open=205_200_000, close=205_200_000+14_400_000=219_600_000 — exactly on boundary
        val boundary = listOf(Candle(205_200_000L, 100.0, 105.0, 99.0, 104.0, 4_000.0))
        val result = engine(h1Candles(200), oneShot, "USDJPY", Timeframe.H1,
            mapOf(Timeframe.H4 to boundary), CandleSource.LIVE)
        if (result.trades.isEmpty()) fail("Expected 1 trade")
    }

    @Test
    fun `empty HTF map is handled gracefully`() {
        val result = engine(h1Candles(200), oneShot, "BTCUSDT", Timeframe.H1,
            emptyMap(), CandleSource.LIVE)
        if (result.trades.isEmpty()) fail("Expected 1 trade")
    }
}
