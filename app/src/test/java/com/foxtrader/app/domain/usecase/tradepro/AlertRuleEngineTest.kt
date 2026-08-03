package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AlertRule
import com.foxtrader.app.domain.model.tradepro.AlertStage
import com.foxtrader.app.domain.model.tradepro.AlertTriggerType
import com.foxtrader.app.domain.model.tradepro.FlipZone
import com.foxtrader.app.domain.model.tradepro.FlipZoneKind
import com.foxtrader.app.domain.model.tradepro.HoldZone
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProManagementPlan
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRuleEngineTest {

    private val engine = AlertRuleEngine()
    private var idc = 0

    private fun rule(
        trigger: AlertTriggerType,
        symbol: String = "",
        threshold: Double = 0.0,
        minStage: AlertStage = AlertStage.CONFIRMATION,
        enabled: Boolean = true,
        cooldownMinutes: Int = 15,
    ) = AlertRule(
        id = "r${idc++}",
        name = "Rule",
        symbol = symbol,
        trigger = trigger,
        threshold = threshold,
        minStage = minStage,
        enabled = enabled,
        cooldownMinutes = cooldownMinutes,
    )

    private fun plan() = TradeProManagementPlan(
        contracts = 3, stopPoints = 3.0, t1Points = 4.0, t2Points = 8.0,
        t1Contracts = 1, t2Contracts = 1, runnerContracts = 1,
        totalRiskPoints = 9.0, breakevenWinRate = 0.429,
    )

    private fun setup(stage: SetupStage, confidence: Int, rr: Double = 2.0, confluences: List<String> = emptyList()) = TradeProSetup(
        symbol = "EURUSD", direction = Direction.BULLISH, stage = stage,
        entry = 100.0, stopLoss = 97.0, target1 = 104.0, target2 = 108.0, runnerTarget = 116.0,
        riskPoints = 3.0, riskReward = rr, confidence = confidence,
        flipZone = null, holdZone = null, managementPlan = plan(),
        confluences = confluences, note = "",
    )

    private fun zone(low: Double, high: Double) = HoldZone(
        type = HoldZoneType.BUY_HOLD, high = high, low = low,
        startIndex = 0, endIndex = 10, startTimestamp = 0L, endTimestamp = 1L,
        stackedCount = 1, strength = 0.5, defended = true,
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
    fun `disabled rule never fires`() {
        val r = rule(AlertTriggerType.EXECUTABLE_SETUP, enabled = false)
        val a = analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80))
        assertNull(engine.evaluate(r, "EURUSD", a, 100.0))
    }

    @Test
    fun `symbol-specific rule ignores other symbols`() {
        val r = rule(AlertTriggerType.EXECUTABLE_SETUP, symbol = "GBPUSD")
        val a = analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80))
        assertNull(engine.evaluate(r, "EURUSD", a, 100.0))
    }

    @Test
    fun `executable setup rule fires on EXECUTE`() {
        val r = rule(AlertTriggerType.EXECUTABLE_SETUP)
        val a = analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80))
        val alert = engine.evaluate(r, "EURUSD", a, 100.0)
        assertNotNull(alert)
        assertEquals("EURUSD", alert!!.symbol)
    }

    @Test
    fun `stage reached respects the minimum stage`() {
        val r = rule(AlertTriggerType.STAGE_REACHED, minStage = AlertStage.CONFIRMATION)
        assertNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.ZONE, setup(SetupStage.ZONE, 50)), 100.0))
        assertNotNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.CONFIRMATION, setup(SetupStage.CONFIRMATION, 60)), 100.0))
        assertNotNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80)), 100.0))
    }

    @Test
    fun `zone entered fires only when price is inside a hold zone`() {
        val r = rule(AlertTriggerType.ZONE_ENTERED)
        val z = listOf(zone(99.0, 101.0))
        assertNotNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.ZONE, null, zones = z), 100.0))
        assertNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.ZONE, null, zones = z), 105.0))
    }

    @Test
    fun `confidence above threshold fires correctly`() {
        val r = rule(AlertTriggerType.CONFIDENCE_ABOVE, threshold = 70.0)
        assertNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.CONFIRMATION, setup(SetupStage.CONFIRMATION, 65)), 100.0))
        assertNotNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.CONFIRMATION, setup(SetupStage.CONFIRMATION, 80)), 100.0))
    }

    @Test
    fun `htf aligned requires the confluence tag`() {
        val r = rule(AlertTriggerType.HTF_ALIGNED)
        assertNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80)), 100.0))
        assertNotNull(engine.evaluate(r, "EURUSD", analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80, confluences = listOf("HTF_ALIGNED_4H"))), 100.0))
    }

    @Test
    fun `bias flip fires only on an actual directional change`() {
        val r = rule(AlertTriggerType.BIAS_FLIP)
        val a = analysis(SetupStage.LEVEL, null, bias = Bias.BEARISH)
        assertNotNull(engine.evaluate(r, "EURUSD", a, 100.0, previousBias = Bias.BULLISH))
        assertNull(engine.evaluate(r, "EURUSD", a, 100.0, previousBias = Bias.BEARISH))
        assertNull(engine.evaluate(r, "EURUSD", a, 100.0, previousBias = null))
    }

    @Test
    fun `batch honours cooldown`() {
        val r = rule(AlertTriggerType.EXECUTABLE_SETUP, cooldownMinutes = 15)
        val analyses = mapOf("EURUSD" to analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80)))
        val prices = mapOf("EURUSD" to 100.0)

        val first = engine.evaluateBatch(listOf(r), analyses, prices, nowEpochMs = 0L)
        assertEquals(1, first.alerts.size)

        // 5 minutes later -> still in cooldown.
        val second = engine.evaluateBatch(listOf(r), analyses, prices, lastFiredByKey = first.lastFiredByKey, nowEpochMs = 5 * 60_000L)
        assertTrue(second.alerts.isEmpty())

        // 20 minutes later -> cooldown elapsed.
        val third = engine.evaluateBatch(listOf(r), analyses, prices, lastFiredByKey = first.lastFiredByKey, nowEpochMs = 20 * 60_000L)
        assertEquals(1, third.alerts.size)
    }

    @Test
    fun `all-symbols rule fans out across every analysis`() {
        val r = rule(AlertTriggerType.EXECUTABLE_SETUP, symbol = "")
        val analyses = mapOf(
            "EURUSD" to analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 80)),
            "GBPUSD" to analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 70)),
            "AUDUSD" to analysis(SetupStage.ZONE, setup(SetupStage.ZONE, 50)),
        )
        val prices = mapOf("EURUSD" to 100.0, "GBPUSD" to 100.0, "AUDUSD" to 100.0)
        val result = engine.evaluateBatch(listOf(r), analyses, prices, nowEpochMs = 0L)
        assertEquals(2, result.alerts.size) // only the two EXECUTE symbols
    }

    @Test
    fun `batch is deterministic and priority-sorted`() {
        val r = rule(AlertTriggerType.STAGE_REACHED, minStage = AlertStage.ZONE)
        val analyses = mapOf(
            "AAA" to analysis(SetupStage.ZONE, setup(SetupStage.ZONE, 50)),
            "BBB" to analysis(SetupStage.EXECUTE, setup(SetupStage.EXECUTE, 90)),
        )
        val prices = mapOf("AAA" to 100.0, "BBB" to 100.0)
        val first = engine.evaluateBatch(listOf(r), analyses, prices, nowEpochMs = 0L)
        val second = engine.evaluateBatch(listOf(r), analyses, prices, nowEpochMs = 0L)
        assertEquals(first.alerts.map { it.symbol }, second.alerts.map { it.symbol })
        // Highest priority (executable BBB) comes first.
        assertEquals("BBB", first.alerts.first().symbol)
    }
}
