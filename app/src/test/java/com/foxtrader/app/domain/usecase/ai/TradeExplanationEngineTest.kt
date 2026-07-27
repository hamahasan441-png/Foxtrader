package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeExplanationEngineTest {

    private val engine = TradeExplanationEngine()

    @Test
    fun `approved decision produces setup story invalidation checklist and tags`() {
        val explanation = engine.explain(
            decision = approvedDecision(),
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            entryPrice = 1.1000,
            stopLoss = 1.0950,
            takeProfit = 1.1150,
        )

        assertTrue(explanation.title.contains("Bullish"))
        assertTrue(explanation.summary.contains("Approved"))
        assertTrue(explanation.setupStory.contains("Liquidity Sweep"))
        assertTrue(explanation.invalidation.contains("below"))
        assertTrue(explanation.targetNarrative?.contains("R:R 3.00") == true)
        assertTrue(explanation.actionChecklist.any { it.contains("RiskEngine") })
        assertTrue(explanation.tags.contains("EURUSD"))
        assertTrue(explanation.tags.contains("BULLISH"))
        assertFalse(explanation.tags.contains("NO_TRADE"))
    }

    @Test
    fun `rejected decision produces no trade checklist and rejection summary`() {
        val explanation = engine.explain(
            decision = rejectedDecision(),
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
        )

        assertEquals("No-trade verdict for BTCUSDT", explanation.title)
        assertTrue(explanation.summary.contains("Only 2/5"))
        assertTrue(explanation.invalidation.contains("not applicable"))
        assertTrue(explanation.actionChecklist.first().contains("Do not execute"))
        assertTrue(explanation.tags.contains("NO_TRADE"))
    }

    @Test
    fun `compact explanation is suitable for alerts`() {
        val approved = engine.compact(approvedDecision(), "XAUUSD")
        val rejected = engine.compact(rejectedDecision(), "XAUUSD")

        assertTrue(approved.contains("XAUUSD Bullish"))
        assertTrue(approved.contains("confluences"))
        assertTrue(rejected.contains("no trade"))
    }

    private fun approvedDecision(): DecisionResult = DecisionResult(
        approved = true,
        direction = Direction.BULLISH,
        confidence = 78.0,
        grade = SignalGrade.STRONG,
        confluencePresent = listOf(
            RequiredConfluence.LIQUIDITY_SWEEP,
            RequiredConfluence.BOS_OR_CHOCH,
            RequiredConfluence.FVG,
            RequiredConfluence.ORDER_BLOCK,
            RequiredConfluence.HTF_BIAS,
            RequiredConfluence.TREND,
        ),
        confluenceMissing = listOf(
            RequiredConfluence.SMT,
            RequiredConfluence.SESSION,
            RequiredConfluence.VOLUME,
        ),
        blockReasons = emptyList(),
        vetoedBy = null,
        explanation = "Approved",
        timestamp = 1_000L,
    )

    private fun rejectedDecision(): DecisionResult = DecisionResult(
        approved = false,
        direction = null,
        confidence = 42.0,
        grade = SignalGrade.NO_SIGNAL,
        confluencePresent = listOf(RequiredConfluence.TREND, RequiredConfluence.VOLUME),
        confluenceMissing = RequiredConfluence.all().filterNot {
            it == RequiredConfluence.TREND || it == RequiredConfluence.VOLUME
        },
        blockReasons = listOf("Only 2/5 required confluences present"),
        vetoedBy = AgentName.RISK,
        explanation = "Rejected",
        timestamp = 1_000L,
    )
}
