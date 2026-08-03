package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.DeviationSeverity
import com.foxtrader.app.domain.model.tradepro.FlipZone
import com.foxtrader.app.domain.model.tradepro.FlipZoneKind
import com.foxtrader.app.domain.model.tradepro.MarketRegime
import com.foxtrader.app.domain.model.tradepro.RiskPosture
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyPlanEngineTest {

    private val engine = DailyPlanEngine()
    private var idc = 0

    private fun entry(
        pnl: Double,
        r: Double,
        emotion: EmotionTag = EmotionTag.NEUTRAL,
        symbol: String = "EURUSD",
        entryTime: Long = 1_000L,
    ): JournalEntry = JournalEntry(
        id = "e${idc++}",
        symbol = symbol,
        direction = Direction.BULLISH,
        timeframe = Timeframe.H1,
        entryPrice = 100.0,
        exitPrice = 101.0,
        stopLoss = 99.0,
        takeProfit = 104.0,
        volume = 1.0,
        entryTime = entryTime,
        exitTime = entryTime + 3_600_000L,
        pnl = pnl,
        rMultiple = r,
        setupType = "BOS",
        emotionTag = emotion,
    )

    private fun analysis(symbol: String, bias: Bias, stage: SetupStage): TradeProAnalysis = TradeProAnalysis(
        symbol = symbol,
        flipZone = FlipZone(price = 100.0, bias = bias, kind = FlipZoneKind.LAST_HIGHER_LOW, anchorIndex = 0, anchorTimestamp = 0L),
        holdZones = emptyList(),
        imbalances = emptyList(),
        absorptions = emptyList(),
        setup = null,
        stage = stage,
        narrative = "",
    )

    @Test
    fun `no history yields a normal posture`() {
        val plan = engine.generatePlan(emptyList(), emptyList())
        assertEquals(RiskPosture.NORMAL, plan.posture)
        assertTrue(plan.dailyRiskBudgetPoints > 0.0)
        assertTrue(plan.maxTrades >= 1)
    }

    @Test
    fun `a losing streak forces a defensive posture with a smaller budget`() {
        val config = TradeProConfig()
        val losses = List(4) { entry(pnl = -50.0, r = -1.0) }
        val plan = engine.generatePlan(emptyList(), losses, config)
        assertEquals(RiskPosture.DEFENSIVE, plan.posture)
        assertTrue(
            "defensive budget must be below the raw daily max",
            plan.dailyRiskBudgetPoints < config.maxDailyLossPoints,
        )
    }

    @Test
    fun `a clean winning run permits an aggressive posture`() {
        val wins = List(10) { entry(pnl = 100.0, r = 1.0, emotion = EmotionTag.PATIENT) }
        val plan = engine.generatePlan(emptyList(), wins)
        assertEquals(RiskPosture.AGGRESSIVE, plan.posture)
    }

    @Test
    fun `regime is risk-on when the focus list is dominantly bullish`() {
        val analyses = listOf(
            analysis("EURUSD", Bias.BULLISH, SetupStage.ZONE),
            analysis("GBPUSD", Bias.BULLISH, SetupStage.LEVEL),
            analysis("AUDUSD", Bias.BULLISH, SetupStage.CONFIRMATION),
        )
        val plan = engine.generatePlan(analyses, emptyList())
        assertEquals(MarketRegime.RISK_ON, plan.marketRegime)
        assertTrue(plan.focus.isNotEmpty())
    }

    @Test
    fun `focus list is ranked by stage and capped`() {
        val analyses = listOf(
            analysis("A", Bias.BULLISH, SetupStage.LEVEL),
            analysis("B", Bias.BEARISH, SetupStage.EXECUTE),
            analysis("C", Bias.BULLISH, SetupStage.ZONE),
        )
        val plan = engine.generatePlan(analyses, emptyList())
        assertEquals("B", plan.focus.first().symbol) // EXECUTE ranks first
    }

    @Test
    fun `neutral no-setup symbols are excluded from focus`() {
        val analyses = listOf(analysis("FLAT", Bias.NEUTRAL, SetupStage.NONE))
        val plan = engine.generatePlan(analyses, emptyList())
        assertTrue(plan.focus.isEmpty())
    }

    @Test
    fun `plan always ships a rule set`() {
        val plan = engine.generatePlan(emptyList(), emptyList())
        assertTrue(plan.rules.size >= 4)
    }

    @Test
    fun `reviewing without a plan returns an empty review`() {
        val review = engine.reviewSession(DailyPlan_empty(), emptyList())
        assertEquals(0, review.tradesTaken)
    }

    private fun DailyPlan_empty() = com.foxtrader.app.domain.model.tradepro.DailyPlan.empty("none")

    @Test
    fun `overtrading and emotional entries are flagged as deviations`() {
        val plan = engine.generatePlan(
            listOf(analysis("EURUSD", Bias.BULLISH, SetupStage.ZONE)),
            emptyList(),
        )
        // Take far more trades than allowed, several emotional.
        val today = List(plan.maxTrades + 3) {
            entry(pnl = -20.0, r = -0.5, emotion = EmotionTag.REVENGE, symbol = "EURUSD")
        }
        val review = engine.reviewSession(plan, today)
        assertTrue("overtrading flagged", review.deviations.any { it.rule == "Trade count" })
        assertTrue("emotional flagged", review.deviations.any { it.rule == "Emotional control" })
        assertTrue("adherence reduced", review.adherenceScore < 100)
    }

    @Test
    fun `a disciplined session scores high adherence and followedPlan`() {
        val plan = engine.generatePlan(
            listOf(analysis("EURUSD", Bias.BULLISH, SetupStage.ZONE)),
            emptyList(),
        )
        val today = listOf(entry(pnl = 100.0, r = 1.5, emotion = EmotionTag.PATIENT, symbol = "EURUSD"))
        val review = engine.reviewSession(plan, today)
        assertTrue(review.followedPlan)
        assertTrue(review.adherenceScore >= 75)
        assertTrue(review.deviations.none { it.severity == DeviationSeverity.SEVERE })
    }

    @Test
    fun `plan generation is deterministic`() {
        val analyses = listOf(analysis("EURUSD", Bias.BULLISH, SetupStage.ZONE))
        val history = List(5) { entry(pnl = if (it % 2 == 0) 50.0 else -50.0, r = if (it % 2 == 0) 1.0 else -1.0) }
        val first = engine.generatePlan(analyses, history, TradeProConfig(), nowEpochMs = 42L)
        val second = engine.generatePlan(analyses, history, TradeProConfig(), nowEpochMs = 42L)
        assertEquals(first.posture, second.posture)
        assertEquals(first.maxTrades, second.maxTrades)
        assertEquals(first.dailyRiskBudgetPoints, second.dailyRiskBudgetPoints, 1e-9)
        assertEquals(first.focus.map { it.symbol }, second.focus.map { it.symbol })
    }
}
