package com.foxtrader.app.domain.usecase.alerts

import com.foxtrader.app.domain.model.AlertChannel
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AlertPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AlertEngine] — the framework-free alert lifecycle: priority
 * filtering, cooldown dedup, hourly rate limiting, and acknowledgment. The
 * engine is pure and self-contained (no injected collaborators), so these are
 * deterministic within a single run (elapsed time ≪ the 60s default cooldown).
 */
class AlertEngineTest {

    private lateinit var engine: AlertEngine

    @Before
    fun setup() {
        engine = AlertEngine()
    }

    @Test
    fun `priority below the configured minimum is suppressed`() {
        // Default minPriority is MEDIUM.
        assertNull(engine.send("t", "b", AlertPriority.LOW))
        assertTrue(engine.getAlerts().isEmpty())
    }

    @Test
    fun `an alert meeting the minimum priority is recorded and dispatched to channels`() {
        val alert = engine.send("Setup", "EURUSD executable", AlertPriority.HIGH, symbol = "EURUSD")

        assertNotNull(alert)
        assertEquals(1, engine.getAlerts().size)
        assertEquals(AlertPriority.HIGH, alert!!.priority)
        assertEquals("EURUSD", alert.symbol)
        assertFalse(alert.acknowledged)
        assertTrue(alert.dispatchedTo.contains(AlertChannel.PUSH))
    }

    @Test
    fun `a duplicate within the cooldown window is suppressed`() {
        val first = engine.send("t", "b", AlertPriority.HIGH, cooldownKey = "k")
        val second = engine.send("t", "b", AlertPriority.HIGH, cooldownKey = "k")

        assertNotNull(first)
        assertNull("same cooldown key inside the 60s window must be deduped", second)
        assertEquals(1, engine.getAlerts().size)
    }

    @Test
    fun `distinct cooldown keys are not deduped against each other`() {
        assertNotNull(engine.send("t", "b", AlertPriority.HIGH, cooldownKey = "a"))
        assertNotNull(engine.send("t", "b", AlertPriority.HIGH, cooldownKey = "b"))
        assertEquals(2, engine.getAlerts().size)
    }

    @Test
    fun `the hourly rate limit blocks alerts beyond the configured maximum`() {
        // Remove priority + cooldown as confounders; cap the hour at 2.
        engine.updateConfig(
            AlertConfig(minPriority = AlertPriority.LOW, cooldownMs = 0L, maxAlertsPerHour = 2),
        )

        assertNotNull(engine.send("t", "b", AlertPriority.LOW, cooldownKey = "1"))
        assertNotNull(engine.send("t", "b", AlertPriority.LOW, cooldownKey = "2"))
        assertNull("the third alert this hour must be rate-limited", engine.send("t", "b", AlertPriority.LOW, cooldownKey = "3"))
        assertEquals(2, engine.getAlerts().size)
    }

    @Test
    fun `acknowledge marks an alert and removes it from the unacknowledged set`() {
        val alert = engine.send("t", "b", AlertPriority.HIGH)!!
        assertEquals(1, engine.getUnacknowledged().size)

        engine.acknowledge(alert.id)

        assertTrue(engine.getUnacknowledged().isEmpty())
        assertTrue(engine.getAlerts().first().acknowledged)
    }

    @Test
    fun `acknowledging an unknown id is a no-op`() {
        engine.send("t", "b", AlertPriority.HIGH)
        engine.acknowledge("does-not-exist")
        assertEquals(1, engine.getUnacknowledged().size)
    }

    @Test
    fun `getAlerts honours the requested limit`() {
        engine.updateConfig(AlertConfig(minPriority = AlertPriority.LOW, cooldownMs = 0L))
        repeat(3) { i -> engine.send("t$i", "b", AlertPriority.LOW, cooldownKey = "k$i") }

        assertEquals(3, engine.getAlerts().size)
        assertEquals(2, engine.getAlerts(limit = 2).size)
    }

    @Test
    fun `clearAll removes every stored alert`() {
        engine.send("t", "b", AlertPriority.HIGH)
        engine.clearAll()
        assertTrue(engine.getAlerts().isEmpty())
    }

    @Test
    fun `raising the minimum priority filters out lower-priority alerts`() {
        engine.updateConfig(AlertConfig(minPriority = AlertPriority.CRITICAL))

        assertNull(engine.send("t", "b", AlertPriority.HIGH, cooldownKey = "h"))
        assertNotNull(engine.send("t", "b", AlertPriority.CRITICAL, cooldownKey = "c"))
    }

    @Test
    fun `updateConfig is reflected by getConfig`() {
        val cfg = AlertConfig(minPriority = AlertPriority.HIGH, maxAlertsPerHour = 5)
        engine.updateConfig(cfg)
        assertEquals(cfg, engine.getConfig())
    }
}
