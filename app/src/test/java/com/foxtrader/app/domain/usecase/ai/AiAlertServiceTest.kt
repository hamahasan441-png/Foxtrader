package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AiAlertService — cooldown dedup + grade filter.
 */
class AiAlertServiceTest {

    private lateinit var service: AiAlertService

    @Before
    fun setup() {
        service = AiAlertService()
        service.cooldownMs = 5 * 60_000L
    }

    private fun decision(
        approved: Boolean,
        direction: Direction? = Direction.BULLISH,
        grade: SignalGrade = SignalGrade.STRONG,
        confidence: Double = 75.0,
    ) = DecisionResult(
        approved = approved,
        direction = direction,
        confidence = confidence,
        grade = grade,
        confluencePresent = RequiredConfluence.entries.take(7),
        confluenceMissing = RequiredConfluence.entries.drop(7),
        blockReasons = emptyList(),
        vetoedBy = null,
        explanation = "",
        timestamp = System.currentTimeMillis(),
    )

    @Test
    fun `fires alert for approved strong signal`() {
        val alert = service.evaluate(decision(approved = true), "BTCUSDT")
        assertNotNull(alert)
    }

    @Test
    fun `does not fire for unapproved signal`() {
        val alert = service.evaluate(decision(approved = false), "BTCUSDT")
        assertNull(alert)
    }

    @Test
    fun `does not fire for grade below minimum`() {
        service.minGrade = SignalGrade.STRONG
        val alert = service.evaluate(decision(approved = true, grade = SignalGrade.WEAK), "BTCUSDT")
        assertNull(alert)
    }

    @Test
    fun `cooldown prevents duplicate alerts`() {
        val first = service.evaluate(decision(approved = true), "EURUSD")
        assertNotNull(first)
        val second = service.evaluate(decision(approved = true), "EURUSD")
        assertNull(second) // within cooldown
    }

    @Test
    fun `different symbols are not cooldown-blocked`() {
        service.evaluate(decision(approved = true), "EURUSD")
        val alert = service.evaluate(decision(approved = true), "GBPUSD")
        assertNotNull(alert) // different symbol — no cooldown
    }

    @Test
    fun `resetCooldowns clears the dedup state`() {
        service.evaluate(decision(approved = true), "EURUSD")
        service.resetCooldowns()
        val alert = service.evaluate(decision(approved = true), "EURUSD")
        assertNotNull(alert) // cooldown cleared
    }
}
