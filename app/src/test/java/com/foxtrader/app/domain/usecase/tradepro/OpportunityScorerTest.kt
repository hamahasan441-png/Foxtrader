package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.FlipZone
import com.foxtrader.app.domain.model.tradepro.FlipZoneKind
import com.foxtrader.app.domain.model.tradepro.HoldZone
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.OpportunityGrade
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeOpportunity
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProManagementPlan
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunityScorerTest {

    private val scorer = OpportunityScorer()

    private fun plan() = TradeProManagementPlan(
        contracts = 3, stopPoints = 3.0, t1Points = 4.0, t2Points = 8.0,
        t1Contracts = 1, t2Contracts = 1, runnerContracts = 1,
        totalRiskPoints = 9.0, breakevenWinRate = 0.429,
    )

    private fun holdZone(low: Double, high: Double) = HoldZone(
        type = HoldZoneType.BUY_HOLD,
        high = high,
        low = low,
        startIndex = 0,
        endIndex = 10,
        startTimestamp = 0L,
        endTimestamp = 1L,
        stackedCount = 1,
        strength = 0.5,
        defended = true,
    )

    private fun setup(
        stage: SetupStage,
        confidence: Int,
        confluences: List<String> = emptyList(),
        zone: HoldZone? = null,
    ) = TradeProSetup(
        symbol = "EURUSD",
        direction = Direction.BULLISH,
        stage = stage,
        entry = 100.0,
        stopLoss = 97.0,
        target1 = 104.0,
        target2 = 108.0,
        runnerTarget = 116.0,
        riskPoints = 3.0,
        riskReward = 2.5,
        confidence = confidence,
        flipZone = null,
        holdZone = zone,
        managementPlan = plan(),
        confluences = confluences,
        note = "",
    )

    private fun analysis(
        stage: SetupStage,
        setup: TradeProSetup?,
        bias: Bias = Bias.BULLISH,
        zones: List<HoldZone> = emptyList(),
    ) = TradeProAnalysis(
        symbol = "EURUSD",
        flipZone = FlipZone(price = 100.0, bias = bias, kind = FlipZoneKind.LAST_HIGHER_LOW, anchorIndex = 0, anchorTimestamp = 0L),
        holdZones = zones,
        imbalances = emptyList(),
        absorptions = emptyList(),
        setup = setup,
        stage = stage,
        narrative = "",
    )

    @Test
    fun `executable setup scores higher than a mere level`() {
        val zone = holdZone(99.0, 101.0)
        val execute = scorer.score(analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80, listOf("HTF_ALIGNED_4H", "Imbalance"), zone), zones = listOf(zone)), 100.0)
        val level = scorer.score(analysis(SetupStage.LEVEL, setup(SetupStage.LEVEL, 40)), 100.0)
        assertTrue("execute must outscore level", execute.readinessScore > level.readinessScore)
        assertTrue(execute.isActionable)
    }

    @Test
    fun `score is always within 0 to 100 and grade matches`() {
        val zone = holdZone(99.0, 101.0)
        val op = scorer.score(analysis(SetupStage.CONFIRMATION, setup(SetupStage.CONFIRMATION, 70, zone = zone), zones = listOf(zone)), 100.0)
        assertTrue(op.readinessScore in 0..100)
        assertEquals(OpportunityGrade.fromScore(op.readinessScore), op.grade)
    }

    @Test
    fun `price inside the zone has zero distance`() {
        val zone = holdZone(99.0, 101.0)
        val op = scorer.score(analysis(SetupStage.ZONE, setup(SetupStage.ZONE, 60, zone = zone), zones = listOf(zone)), 100.0)
        assertEquals(0.0, op.distanceToZonePoints, 1e-9)
    }

    @Test
    fun `price above the zone has positive distance in points`() {
        val zone = holdZone(99.0, 101.0)
        // pointSize 1.0 by default; price 105 is 4 points above the zone high of 101.
        val op = scorer.score(analysis(SetupStage.ZONE, setup(SetupStage.ZONE, 60, zone = zone), zones = listOf(zone)), 105.0)
        assertEquals(4.0, op.distanceToZonePoints, 1e-9)
    }

    @Test
    fun `htf alignment increases the score`() {
        val zone = holdZone(99.0, 101.0)
        val aligned = scorer.score(analysis(SetupStage.CONFIRMATION, setup(SetupStage.CONFIRMATION, 70, listOf("HTF_ALIGNED_4H"), zone), zones = listOf(zone)), 100.0)
        val notAligned = scorer.score(analysis(SetupStage.CONFIRMATION, setup(SetupStage.CONFIRMATION, 70, emptyList(), zone), zones = listOf(zone)), 100.0)
        assertTrue(aligned.readinessScore >= notAligned.readinessScore)
        assertTrue(aligned.htfAligned)
    }

    @Test
    fun `no setup yields a NONE stage opportunity with zero score`() {
        val op = scorer.score(analysis(SetupStage.NONE, null, Bias.NEUTRAL), 100.0)
        assertEquals(SetupStage.NONE, op.stage)
        assertEquals(0, op.confidence)
    }

    @Test
    fun `board ranks actionable setups above lower stages`() {
        val zone = holdZone(99.0, 101.0)
        val execute = scorer.score(analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80, zone = zone), zones = listOf(zone)), 100.0)
        val zoneOp = scorer.score(analysis(SetupStage.ZONE, setup(SetupStage.ZONE, 90, zone = zone), zones = listOf(zone)), 100.0)
        val board = scorer.buildBoard(listOf(zoneOp, execute), scannedSymbols = 2, hadSyntheticData = false, nowEpochMs = 0L)
        assertEquals(SetupStage.EXECUTE, board.opportunities.first().stage)
        assertEquals(1, board.actionableCount)
    }

    @Test
    fun `board counts bias split and no-data entries rank last`() {
        val zone = holdZone(99.0, 101.0)
        val bull = scorer.score(analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80, zone = zone).copy(direction = Direction.BULLISH), Bias.BULLISH, listOf(zone)), 100.0)
        val bear = scorer.score(analysis(SetupStage.ZONE, setup(SetupStage.ZONE, 60, zone = zone).copy(direction = Direction.BEARISH), Bias.BEARISH, listOf(zone)), 100.0)
        val noData = TradeOpportunity.noData("XAUUSD", "Insufficient data")
        val board = scorer.buildBoard(listOf(noData, bear, bull), scannedSymbols = 3, hadSyntheticData = false, nowEpochMs = 0L)
        assertEquals(1, board.bullishCount)
        assertEquals(1, board.bearishCount)
        assertTrue("no-data entry must rank last", !board.opportunities.last().hasData)
    }

    @Test
    fun `board building is deterministic`() {
        val zone = holdZone(99.0, 101.0)
        val ops = (1..5).map {
            scorer.score(analysis(SetupStage.ZONE, setup(SetupStage.ZONE, it * 15, zone = zone), zones = listOf(zone)), 100.0)
                .copy(symbol = "SYM$it")
        }
        val first = scorer.buildBoard(ops, 5, false, 0L)
        val second = scorer.buildBoard(ops.reversed(), 5, false, 0L)
        assertEquals(first.opportunities.map { it.symbol }, second.opportunities.map { it.symbol })
    }
}
