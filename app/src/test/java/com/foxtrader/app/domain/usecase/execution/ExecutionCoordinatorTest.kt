package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.Direction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [ExecutionCoordinator]: idempotency reservation, duplicate-order
 * blocking, and never submitting when the safety layer rejects.
 */
class ExecutionCoordinatorTest {

    private val safety = ExecutionSafetyLayer()
    private val audit = InMemoryExecutionAuditLog()

    // The coordinator evaluates the safety layer with the real wall-clock time
    // (System.currentTimeMillis), so a confirmation must be stamped with the
    // current time — otherwise the fresh-confirmation gate always rejects.
    private fun intent() = TradeIntent(
        symbol = "EURUSD",
        direction = Direction.BULLISH,
        volume = 1.0,
        entryPrice = 1.1000,
        stopLoss = 1.0950,
        takeProfit = 1.1050,
        confirmationTimestamp = System.currentTimeMillis(),
    )

    private val enabledPolicy = ExecutionPolicy(liveModeEnabled = true)
    private val enabledContext = ExecutionContext(
        spec = com.foxtrader.app.domain.usecase.risk.InstrumentSpec(
            symbol = "EURUSD",
            contractSize = 100_000.0,
            tickSize = 0.00001,
            point = 0.00001,
            minVolume = 0.01,
            maxVolume = 50.0,
            volumeStep = 0.01,
            quoteCurrency = "USD",
            baseCurrency = "EUR",
        ),
    )

    @Test
    fun `safety rejection means the transport is never called`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        val submitted = AtomicInteger(0)

        val receipt = coordinator.execute(
            intent = intent(),
            policy = ExecutionPolicy(), // live off -> rejected
            context = enabledContext,
        ) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "ORD-1")
        }

        assertTrue(receipt is ExecutionReceipt.Rejected)
        assertEquals(0, submitted.get())
        assertEquals(1, audit.all().size)
    }

    @Test
    fun `allowed intent submits exactly once and records the receipt`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        val submitted = AtomicInteger(0)

        val receipt = coordinator.execute(
            intent = intent(),
            policy = enabledPolicy,
            context = enabledContext,
        ) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "ORD-1", fillPrice = 1.0999)
        }

        assertTrue(receipt is ExecutionReceipt.Accepted)
        assertEquals(1, submitted.get())
        assertEquals(1, audit.all().size)
    }

    @Test
    fun `duplicate order is blocked after the first submission`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        val submitted = AtomicInteger(0)
        val submit: suspend (TradeIntent) -> ExecutionReceipt = {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "ORD-1")
        }

        val first = coordinator.execute(intent(), enabledPolicy, enabledContext, submit)
        val second = coordinator.execute(intent(), enabledPolicy, enabledContext, submit)

        assertTrue(first is ExecutionReceipt.Accepted)
        // Second call with the same idempotency key returns the recorded receipt
        // without invoking the transport again.
        assertTrue(second is ExecutionReceipt.Accepted)
        assertEquals(1, submitted.get())
    }

    @Test
    fun `unknown receipt is recorded and never retried`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        val submitted = AtomicInteger(0)

        val receipt = coordinator.execute(
            intent = intent(),
            policy = enabledPolicy,
            context = enabledContext,
        ) {
            submitted.incrementAndGet()
            ExecutionReceipt.Unknown(it)
        }

        assertTrue(receipt is ExecutionReceipt.Unknown)
        assertEquals(1, submitted.get())
        // A follow-up attempt is blocked as a duplicate, never re-submitted.
        val second = coordinator.execute(intent(), enabledPolicy, enabledContext) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "ORD-2")
        }
        assertTrue(second is ExecutionReceipt.Unknown)
        assertEquals(1, submitted.get())
    }

    @Test
    fun `rejected intent is recorded to the audit log`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        coordinator.execute(intent(), ExecutionPolicy(), enabledContext) {
            ExecutionReceipt.Accepted(it, "SHOULD-NOT")
        }

        val recorded = audit.all().single() as ExecutionReceipt.Rejected
        assertTrue(recorded.reasons.isNotEmpty())
    }
}
