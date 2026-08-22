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
    private val enabledContext: ExecutionContext
        get() = ExecutionContext(
        quote = com.foxtrader.app.domain.model.Mt4Quote(
            symbol = "EURUSD",
            bid = 1.0999,
            ask = 1.1000,
            timestamp = System.currentTimeMillis(),
        ),
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
    fun `prior safety rejection can be re-evaluated after policy is fixed`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        val sameIntent = intent()
        val submitted = AtomicInteger(0)

        val rejected = coordinator.execute(sameIntent, ExecutionPolicy(), enabledContext) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "SHOULD-NOT")
        }
        assertTrue(rejected is ExecutionReceipt.Rejected)
        assertEquals(0, submitted.get())

        val accepted = coordinator.execute(sameIntent, enabledPolicy, enabledContext) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "ORD-RETRY")
        }
        assertTrue(accepted is ExecutionReceipt.Accepted)
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
    @Test
    fun `identical intents on different broker accounts do not collide`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        val submitted = AtomicInteger(0)
        val base = intent()
        val accountA = base.copy(executionScope = "scope-A", idempotencyKey = TradeIntent.computeIdempotencyKey(
            base.symbol, base.direction, base.volume, base.entryPrice, base.stopLoss, base.takeProfit, "scope-A"
        ))
        val accountB = base.copy(executionScope = "scope-B", idempotencyKey = TradeIntent.computeIdempotencyKey(
            base.symbol, base.direction, base.volume, base.entryPrice, base.stopLoss, base.takeProfit, "scope-B"
        ))

        val submit: suspend (TradeIntent) -> ExecutionReceipt = {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "ORD-${submitted.get()}")
        }
        val first = coordinator.execute(accountA, enabledPolicy, enabledContext, submit)
        val second = coordinator.execute(accountB, enabledPolicy, enabledContext, submit)

        assertTrue(first is ExecutionReceipt.Accepted)
        assertTrue(second is ExecutionReceipt.Accepted)
        assertEquals(2, submitted.get())
    }

    @Test
    fun `scoped intent still honors accepted legacy audit key after upgrade`() = runBlocking {
        val coordinator = ExecutionCoordinator(safety, audit)
        val submitted = AtomicInteger(0)
        val legacy = intent()
        audit.record(ExecutionReceipt.Accepted(legacy, "LEGACY-ORDER"))
        val scoped = legacy.copy(
            executionScope = "scope-A",
            idempotencyKey = TradeIntent.computeIdempotencyKey(
                legacy.symbol, legacy.direction, legacy.volume, legacy.entryPrice,
                legacy.stopLoss, legacy.takeProfit, "scope-A"
            ),
        )

        val receipt = coordinator.execute(scoped, enabledPolicy, enabledContext) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "SHOULD-NOT-SUBMIT")
        }

        assertTrue(receipt is ExecutionReceipt.Accepted)
        assertEquals(0, submitted.get())
    }

    @Test
    fun `durable reservation failure prevents broker submission`() = runBlocking {
        val submitted = AtomicInteger(0)
        val failingAudit = object : ExecutionAuditLog {
            override suspend fun record(receipt: ExecutionReceipt) {
                throw IllegalStateException("database unavailable")
            }
            override suspend fun findByIdempotencyKey(idempotencyKey: String): ExecutionReceipt? = null
            override suspend fun all(): List<ExecutionReceipt> = emptyList()
        }
        val coordinator = ExecutionCoordinator(safety, failingAudit)

        var failed = false
        try {
            coordinator.execute(intent(), enabledPolicy, enabledContext) {
                submitted.incrementAndGet()
                ExecutionReceipt.Accepted(it, "MUST-NOT-SUBMIT")
            }
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(0, submitted.get())
    }

    @Test
    fun `final audit failure leaves unknown and blocks blind retry`() = runBlocking {
        val submitted = AtomicInteger(0)
        val stored = linkedMapOf<String, ExecutionReceipt>()
        var records = 0
        val flakyAudit = object : ExecutionAuditLog {
            override suspend fun record(receipt: ExecutionReceipt) {
                records += 1
                // First write is the durable UNKNOWN reservation. Simulate a
                // database failure while trying to finalize ACCEPTED.
                if (records == 2) throw IllegalStateException("final write failed")
                stored[receipt.intent.idempotencyKey] = receipt
            }
            override suspend fun findByIdempotencyKey(idempotencyKey: String): ExecutionReceipt? = stored[idempotencyKey]
            override suspend fun all(): List<ExecutionReceipt> = stored.values.toList()
        }
        val coordinator = ExecutionCoordinator(safety, flakyAudit)
        val sameIntent = intent()

        val first = coordinator.execute(sameIntent, enabledPolicy, enabledContext) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "BROKER-ACCEPTED")
        }
        assertTrue(first is ExecutionReceipt.Unknown)
        assertEquals(1, submitted.get())

        val second = coordinator.execute(sameIntent, enabledPolicy, enabledContext) {
            submitted.incrementAndGet()
            ExecutionReceipt.Accepted(it, "MUST-NOT-RETRY")
        }
        assertTrue(second is ExecutionReceipt.Unknown)
        assertEquals(1, submitted.get())
    }

}
