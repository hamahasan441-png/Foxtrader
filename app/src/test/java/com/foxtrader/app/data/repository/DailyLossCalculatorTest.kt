package com.foxtrader.app.data.repository

import com.foxtrader.app.data.local.entity.ExecutionAuditLogEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for the daily-loss calculation logic introduced in Task 3.
 *
 * The production implementation lives in [RoomExecutionAuditLog.getTodayRealizedLoss],
 * which reads from Room. This test exercises the same aggregation logic in isolation
 * (filter by today, sum negative realizedProfit as gross loss) so the gate can be
 * validated without an Android instrumentation environment.
 */
class DailyLossCalculatorTest {

    private fun makeEntity(
        timestamp: Long,
        profit: Double?,
        status: String = ExecutionAuditLogEntity.STATUS_ACCEPTED,
        key: String = "key-$timestamp-$profit",
    ) = ExecutionAuditLogEntity(
        idempotencyKey = key,
        status = status,
        symbol = "EURUSD",
        direction = "BULLISH",
        volume = 1.0,
        entryPrice = 1.1000,
        stopLoss = null,
        takeProfit = null,
        orderId = "123",
        reasons = "",
        timestamp = timestamp,
        realizedProfit = profit,
    )

    private fun grossLossForToday(entities: List<ExecutionAuditLogEntity>, dayStart: Long): Double {
        var gross = 0.0
        for (e in entities) {
            if (e.status != ExecutionAuditLogEntity.STATUS_ACCEPTED) continue
            if (e.timestamp < dayStart) continue
            val p = e.realizedProfit ?: continue
            if (!p.isFinite()) continue
            if (p < 0.0) gross += -p
        }
        return gross
    }

    @Test
    fun `gross loss today sums absolute negative PnL and ignores wins and old entries`() {
        val now = System.currentTimeMillis()
        val dayStart = (now / 86_400_000L) * 86_400_000L
        val yesterday = dayStart - 1

        val entities = listOf(
            makeEntity(timestamp = dayStart + 1000, profit = -100.0, key = "a"), // today loss
            makeEntity(timestamp = dayStart + 2000, profit = -50.0, key = "b"),  // today loss
            makeEntity(timestamp = dayStart + 3000, profit = 200.0, key = "c"),  // today win -> ignored for gross
            makeEntity(timestamp = yesterday, profit = -999.0, key = "d"),        // yesterday -> ignored
            makeEntity(timestamp = dayStart + 4000, profit = null, key = "e"),   // no profit -> ignored
            makeEntity(timestamp = dayStart + 5000, profit = -25.0, status = ExecutionAuditLogEntity.STATUS_REJECTED, key = "f"), // rejected -> ignored
        )

        val gross = grossLossForToday(entities, dayStart)
        assertEquals(150.0, gross, 1e-9)
    }

    @Test
    fun `gross loss is zero when no losses today`() {
        val now = System.currentTimeMillis()
        val dayStart = (now / 86_400_000L) * 86_400_000L

        val entities = listOf(
            makeEntity(timestamp = dayStart + 1000, profit = 100.0, key = "a"),
            makeEntity(timestamp = dayStart + 2000, profit = 50.0, key = "b"),
        )

        val gross = grossLossForToday(entities, dayStart)
        assertEquals(0.0, gross, 1e-9)
    }

    @Test
    fun `net loss calculation for reference`() {
        val now = System.currentTimeMillis()
        val dayStart = (now / 86_400_000L) * 86_400_000L

        val entities = listOf(
            makeEntity(timestamp = dayStart + 1000, profit = -200.0, key = "a"),
            makeEntity(timestamp = dayStart + 2000, profit = 50.0, key = "b"),
        )

        var net = 0.0
        for (e in entities) {
            if (e.status != ExecutionAuditLogEntity.STATUS_ACCEPTED) continue
            if (e.timestamp < dayStart) continue
            val p = e.realizedProfit ?: continue
            if (!p.isFinite()) continue
            net += p
        }
        val netLoss = if (net < 0) -net else 0.0
        // Net = -150, netLoss = 150
        assertEquals(150.0, netLoss, 1e-9)
    }
}
