package com.foxtrader.app.feature.alerts.presentation

import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.FoxAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inbox filtering.
 *
 * The important rule: the priority filter is "this level **and above**". An
 * exact-match filter would hide CRITICAL alerts from a trader who filtered to
 * HIGH — the worst possible failure mode for this screen.
 */
class AlertsUiStateTest {

    private fun alert(
        id: String,
        priority: AlertPriority,
        acknowledged: Boolean = false,
    ) = FoxAlert(
        id = id,
        title = "t-$id",
        body = "b-$id",
        priority = priority,
        symbol = "EURUSD",
        timestamp = 1_000L,
        acknowledged = acknowledged,
    )

    private val all = listOf(
        alert("low", AlertPriority.LOW),
        alert("med", AlertPriority.MEDIUM),
        alert("high", AlertPriority.HIGH),
        alert("crit", AlertPriority.CRITICAL, acknowledged = true),
    )

    @Test
    fun `no filter shows everything`() {
        val state = AlertsUiState(alerts = all)
        assertEquals(4, state.visibleAlerts.size)
    }

    @Test
    fun `priority filter includes higher priorities`() {
        val state = AlertsUiState(alerts = all, priorityFilter = AlertPriority.HIGH)
        val ids = state.visibleAlerts.map { it.id }
        assertTrue("CRITICAL must survive a HIGH filter", ids.contains("crit"))
        assertTrue(ids.contains("high"))
        assertFalse(ids.contains("med"))
        assertFalse(ids.contains("low"))
    }

    @Test
    fun `lowest priority filter shows all`() {
        val state = AlertsUiState(alerts = all, priorityFilter = AlertPriority.LOW)
        assertEquals(4, state.visibleAlerts.size)
    }

    @Test
    fun `critical filter shows only critical`() {
        val state = AlertsUiState(alerts = all, priorityFilter = AlertPriority.CRITICAL)
        assertEquals(listOf("crit"), state.visibleAlerts.map { it.id })
    }

    @Test
    fun `unread filter hides acknowledged alerts`() {
        val state = AlertsUiState(alerts = all, unreadOnly = true)
        assertFalse(state.visibleAlerts.any { it.acknowledged })
        assertEquals(3, state.visibleAlerts.size)
    }

    @Test
    fun `filters compose`() {
        // HIGH+ and unread: 'crit' is acknowledged, so only 'high' remains.
        val state = AlertsUiState(
            alerts = all,
            priorityFilter = AlertPriority.HIGH,
            unreadOnly = true,
        )
        assertEquals(listOf("high"), state.visibleAlerts.map { it.id })
    }

    @Test
    fun `unread count ignores filters`() {
        // The badge must reflect the inbox, not the current view.
        val state = AlertsUiState(alerts = all, priorityFilter = AlertPriority.CRITICAL)
        assertEquals(3, state.unreadCount)
    }

    @Test
    fun `empty inbox reports no alerts`() {
        val state = AlertsUiState(alerts = emptyList())
        assertFalse(state.hasAlerts)
        assertFalse(state.hasVisibleAlerts)
    }

    @Test
    fun `hasVisibleAlerts distinguishes empty inbox from empty filter`() {
        val state = AlertsUiState(
            alerts = all,
            priorityFilter = AlertPriority.CRITICAL,
            unreadOnly = true,
        )
        assertTrue("inbox is not empty", state.hasAlerts)
        assertFalse("but nothing matches the filter", state.hasVisibleAlerts)
    }
}
