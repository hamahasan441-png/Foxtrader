package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.AlertEntity
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.FoxAlert
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Alert persistence mapping.
 *
 * Priority is stored as a String, so an unparseable value must not crash the
 * inbox — a single corrupt row should degrade, not take the screen down.
 */
class AlertMapperTest {

    private fun domain(
        id: String = "a1",
        priority: AlertPriority = AlertPriority.HIGH,
        symbol: String? = "EURUSD",
        acknowledged: Boolean = false,
    ) = FoxAlert(
        id = id,
        title = "BUY EURUSD",
        body = "Institutional setup",
        priority = priority,
        symbol = symbol,
        timestamp = 1_700_000_000_000L,
        acknowledged = acknowledged,
    )

    @Test
    fun `round-trips every priority`() {
        AlertPriority.entries.forEach { priority ->
            val original = domain(priority = priority)
            val restored = original.toEntity().toDomain()
            assertEquals(priority, restored.priority)
        }
    }

    @Test
    fun `round-trips all persisted fields`() {
        val original = domain(acknowledged = true)
        val restored = original.toEntity().toDomain()
        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.body, restored.body)
        assertEquals(original.symbol, restored.symbol)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.acknowledged, restored.acknowledged)
    }

    @Test
    fun `null symbol survives the round-trip`() {
        val restored = domain(symbol = null).toEntity().toDomain()
        assertEquals(null, restored.symbol)
    }

    @Test
    fun `unparseable priority degrades to medium instead of throwing`() {
        val corrupt = AlertEntity(
            id = "a1",
            title = "t",
            body = "b",
            priority = "NOT_A_PRIORITY",
            symbol = null,
            timestamp = 1L,
            acknowledged = false,
        )
        assertEquals(AlertPriority.MEDIUM, corrupt.toDomain().priority)
    }

    @Test
    fun `acknowledged flag is persisted, not defaulted`() {
        assertEquals(true, domain(acknowledged = true).toEntity().acknowledged)
        assertEquals(false, domain(acknowledged = false).toEntity().acknowledged)
    }
}
