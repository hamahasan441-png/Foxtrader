package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.usecase.risk.InstrumentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ExecutionSafetyLayer]. The safety layer must be fail-closed: every
 * control blocks by default and no single unsupported field may let a bad order
 * through.
 */
class ExecutionSafetyLayerTest {

    private val layer = ExecutionSafetyLayer()
    private val now = 1_000_000L

    private val enabledPolicy = ExecutionPolicy(liveModeEnabled = true)
    private val enabledContext = ExecutionContext(spec = eurUsdSpec())

    private fun intent(
        direction: Direction = Direction.BULLISH,
        entry: Double = 1.1000,
        sl: Double? = 1.0950,
        tp: Double? = 1.1050,
        volume: Double = 1.0,
        confirmation: Long = now,
    ) = TradeIntent(
        symbol = "EURUSD",
        direction = direction,
        volume = volume,
        entryPrice = entry,
        stopLoss = sl,
        takeProfit = tp,
        confirmationTimestamp = confirmation,
    )

    private fun eurUsdSpec() = InstrumentSpec(
        symbol = "EURUSD",
        contractSize = 100_000.0,
        tickSize = 0.00001,
        point = 0.00001,
        minVolume = 0.01,
        maxVolume = 50.0,
        volumeStep = 0.01,
        quoteCurrency = "USD",
        baseCurrency = "EUR",
    )

    private fun assertAllowed(decision: ExecutionSafetyDecision) {
        assertTrue("Expected Allowed but was: $decision", decision is ExecutionSafetyDecision.Allowed)
    }

    private fun assertRejected(decision: ExecutionSafetyDecision) {
        assertTrue("Expected Rejected but was: $decision", decision is ExecutionSafetyDecision.Rejected)
    }

    @Test
    fun `default policy rejects because live mode is off`() {
        val decision = layer.evaluate(intent(), ExecutionPolicy(), enabledContext, now)
        assertRejected(decision)
    }

    @Test
    fun `fully valid intent under enabled policy is allowed`() {
        val decision = layer.evaluate(intent(), enabledPolicy, enabledContext, now)
        assertAllowed(decision)
    }

    @Test
    fun `emergency kill switch blocks everything`() {
        val policy = ExecutionPolicy(liveModeEnabled = true, emergencyKillSwitch = true)
        val decision = layer.evaluate(intent(), policy, enabledContext, now)
        assertRejected(decision)
    }

    @Test
    fun `stale confirmation is rejected`() {
        val policy = ExecutionPolicy(
            liveModeEnabled = true,
            requireFreshConfirmation = true,
            confirmationMaxAgeMs = 5_000L,
        )
        val decision = layer.evaluate(intent(confirmation = now - 10_000L), policy, enabledContext, now)
        assertRejected(decision)
    }

    @Test
    fun `stale quote is rejected`() {
        val staleQuote = Mt4Quote("EURUSD", 1.10, 1.1001, now - 60_000L)
        val decision = layer.evaluate(
            intent(),
            enabledPolicy,
            enabledContext.copy(quote = staleQuote),
            now,
        )
        assertRejected(decision)
    }

    @Test
    fun `max daily loss gate rejects`() {
        val policy = ExecutionPolicy(liveModeEnabled = true, maxDailyLossInAccountCurrency = 500.0)
        val context = enabledContext.copy(dailyLossInAccountCurrency = 600.0)
        assertRejected(layer.evaluate(intent(), policy, context, now))
    }

    @Test
    fun `max daily loss gate rejects when loss meets threshold exactly`() {
        val policy = ExecutionPolicy(liveModeEnabled = true, maxDailyLossInAccountCurrency = 500.0)
        val context = enabledContext.copy(dailyLossInAccountCurrency = 500.0)
        val decision = layer.evaluate(intent(), policy, context, now)
        assertRejected(decision)
        // Reason should mention max daily loss
        val reasons = (decision as ExecutionSafetyDecision.Rejected).reasons
        assertTrue(reasons.any { it.contains("Max daily loss") })
    }

    @Test
    fun `max daily loss gate allows when daily loss is below threshold`() {
        val policy = ExecutionPolicy(liveModeEnabled = true, maxDailyLossInAccountCurrency = 500.0)
        val context = enabledContext.copy(dailyLossInAccountCurrency = 499.9)
        assertAllowed(layer.evaluate(intent(), policy, context, now))
    }

    @Test
    fun `max daily loss gate is skipped when dailyLoss is null`() {
        // Null means no P&L source — gate permissive (pre-fix behavior). After fix,
        // buildExecutionContext wires a real source, so null means truly no loss today.
        val policy = ExecutionPolicy(liveModeEnabled = true, maxDailyLossInAccountCurrency = 500.0)
        val context = enabledContext.copy(dailyLossInAccountCurrency = null)
        assertAllowed(layer.evaluate(intent(), policy, context, now))
    }

    @Test
    fun `free margin gate rejects when margin is insufficient`() {
        val policy = ExecutionPolicy(liveModeEnabled = true, minFreeMarginInAccountCurrency = 1000.0)
        val context = enabledContext.copy(freeMargin = 200.0)
        assertRejected(layer.evaluate(intent(), policy, context, now))
    }

    @Test
    fun `out of range volume is rejected`() {
        val decision = layer.evaluate(intent(volume = 999.0), enabledPolicy, enabledContext, now)
        assertRejected(decision)
    }

    @Test
    fun `volume below broker step is rejected`() {
        val decision = layer.evaluate(intent(volume = 0.015), enabledPolicy, enabledContext, now)
        assertRejected(decision)
    }

    @Test
    fun `bullish stop loss above entry is rejected`() {
        val decision = layer.evaluate(intent(sl = 1.1100), enabledPolicy, enabledContext, now)
        assertRejected(decision)
    }

    @Test
    fun `bearish take profit above entry is rejected`() {
        val decision = layer.evaluate(
            intent(direction = Direction.BEARISH, entry = 1.1000, sl = 1.1100, tp = 1.1200),
            enabledPolicy,
            enabledContext,
            now,
        )
        assertRejected(decision)
    }

    @Test
    fun `non positive slippage is rejected`() {
        val bad = intent().copy(maxSlippagePoints = -1.0)
        assertRejected(layer.evaluate(bad, enabledPolicy, enabledContext, now))
    }

    @Test
    fun `rejected decision lists every failing control`() {
        val policy = ExecutionPolicy(emergencyKillSwitch = true) // live off + kill switch
        val decision = layer.evaluate(intent(sl = 1.1100), policy, enabledContext, now) as ExecutionSafetyDecision.Rejected
        assertTrue(decision.reasons.any { "Live execution is not enabled" in it })
        assertTrue(decision.reasons.any { "Emergency kill switch" in it })
        assertTrue(decision.reasons.any { "Bullish stop-loss" in it })
        assertEquals(3, decision.reasons.size)
    }
}
