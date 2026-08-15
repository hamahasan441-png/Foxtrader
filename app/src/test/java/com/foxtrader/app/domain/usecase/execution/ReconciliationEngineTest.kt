package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ReconciliationEngine]. The central invariant: an UNKNOWN receipt
 * is never retried automatically — it is either resolved against authoritative
 * broker data or left for an operator.
 */
class ReconciliationEngineTest {

    private val engine = ReconciliationEngine()
    private val ts = 1_000_000L

    private fun intent() = TradeIntent(
        symbol = "EURUSD",
        direction = Direction.BULLISH,
        volume = 1.0,
        entryPrice = 1.1000,
        stopLoss = 1.0950,
        confirmationTimestamp = ts,
    )

    private fun unknown() = ExecutionReceipt.Unknown(intent(), timestamp = ts)

    @Test
    fun `unknown receipts are never retryable`() {
        val report = engine.classify(listOf(unknown()))
        assertFalse(report.allResolved)
        assertEquals(1, report.unknown.size)
        assertTrue("UNKNOWN receipts must never be retried", report.retryable().isEmpty())
    }

    @Test
    fun `classify buckets each receipt kind`() {
        val accepted = ExecutionReceipt.Accepted(intent(), "ORD-1", timestamp = ts)
        val rejected = ExecutionReceipt.Rejected(intent(), listOf("no"), timestamp = ts)
        val report = engine.classify(listOf(accepted, rejected, unknown()))

        assertEquals(1, report.accepted.size)
        assertEquals(1, report.rejected.size)
        assertEquals(1, report.unknown.size)
        assertFalse(report.allResolved)
    }

    @Test
    fun `unknown resolved against broker order becomes accepted`() {
        val receipt = unknown()
        val report = engine.resolve(
            unknowns = listOf(receipt),
            brokerOrders = listOf(
                BrokerOrderSnapshot(
                    orderId = "ORD-9",
                    symbol = "EURUSD",
                    volume = 1.0,
                    direction = Direction.BULLISH,
                    timestamp = ts,
                ),
            ),
        )

        assertEquals(1, report.accepted.size)
        assertEquals("ORD-9", report.accepted.single().orderId)
        assertEquals(0, report.unknown.size)
        assertTrue(report.allResolved)
    }

    @Test
    fun `no broker view leaves the unknown unresolved`() {
        val report = engine.resolve(unknowns = listOf(unknown()), brokerOrders = emptyList())
        assertTrue(report.unknown.isNotEmpty())
        assertFalse(report.allResolved)
    }

    @Test
    fun `non matching broker order leaves the unknown unresolved`() {
        val report = engine.resolve(
            unknowns = listOf(unknown()),
            brokerOrders = listOf(
                BrokerOrderSnapshot("ORD-X", "EURUSD", 5.0, Direction.BULLISH, ts), // wrong volume
            ),
        )
        assertTrue(report.unknown.isNotEmpty())
        assertFalse(report.allResolved)
    }
}
