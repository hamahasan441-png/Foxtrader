package com.foxtrader.app.domain.usecase.risk

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Regression tests for [RiskEngine] under degenerate account state.
 *
 * These cover the states a real account actually reaches — a balance drawn to
 * zero, a negative balance while a blown account is reconciled, and concurrent
 * trade recording from background threads — where the engine previously either
 * threw or silently corrupted the balance it gates every subsequent trade on.
 */
class RiskEngineDegenerateStateTest {

    private fun engine() = RiskEngine(InstrumentTypeResolver())

    private fun candles(n: Int = 120): List<Candle> = (0 until n).map { i ->
        val c = 100.0 + i * 0.1
        Candle(1_700_000_000_000L + i * 60_000L, c - 0.2, c + 0.4, c - 0.4, c, 500.0)
    }

    // ========================================================================
    // NON-POSITIVE BALANCE
    // ========================================================================

    @Test
    fun `sizing does not throw when the account balance is zero`() {
        // riskPercent was computed as riskAmount / 0.0 = Infinity, which trips
        // `require(riskPercent >= 0.0)` inside PositionSizeResult and threw an
        // IllegalArgumentException out of the sizing call instead of returning
        // a result the caller could reject.
        val engine = engine()
        engine.updateConfig(RiskConfig(accountBalance = 0.0))
        engine.updateBalance(0.0)

        val result = engine.calculatePositionSize("EURUSD", 100.0, 99.0, candles())

        assertTrue("riskPercent must be finite", result.riskPercent.isFinite())
        assertTrue("riskPercent must not be negative", result.riskPercent >= 0.0)
        assertTrue("volume must be finite", result.volume.isFinite())
        assertTrue(
            "a non-positive balance should be surfaced as a warning",
            result.warnings.any { it.contains("balance", ignoreCase = true) },
        )
    }

    @Test
    fun `sizing does not throw when the account balance is negative`() {
        val engine = engine()
        engine.updateConfig(RiskConfig(accountBalance = -5_000.0))
        engine.updateBalance(-5_000.0)

        val result = engine.calculatePositionSize("EURUSD", 100.0, 99.0, candles())

        assertTrue("riskPercent must be finite", result.riskPercent.isFinite())
        assertTrue("riskPercent must not be negative", result.riskPercent >= 0.0)
    }

    @Test
    fun `sizing stays finite for every sizing method and instrument class`() {
        val symbols = listOf("EURUSD", "USDJPY", "BTCUSD", "ETHUSD", "XAUUSD", "US30", "AAPL", "")
        for (method in PositionSizingMethod.entries) {
            for (symbol in symbols) {
                val engine = engine()
                engine.updateConfig(RiskConfig(sizingMethod = method))

                val result = engine.calculatePositionSize(symbol, 100.0, 99.0, candles())

                assertTrue("$method/$symbol volume", result.volume.isFinite())
                assertTrue("$method/$symbol riskAmount", result.riskAmount.isFinite())
                assertTrue("$method/$symbol riskPercent", result.riskPercent.isFinite())
                assertTrue("$method/$symbol volume must be positive", result.volume > 0.0)
            }
        }
    }

    @Test
    fun `sizing stays finite when the stop sits exactly on the entry`() {
        // A zero stop distance is the classic division-by-zero path: it must
        // degrade to the minimum size, never to Infinity (which
        // (Infinity * 100).roundToInt() would silently turn into a ~21 million
        // lot order).
        for (method in PositionSizingMethod.entries) {
            val engine = engine()
            engine.updateConfig(RiskConfig(sizingMethod = method))

            val result = engine.calculatePositionSize("EURUSD", 100.0, 100.0, candles())

            assertTrue("$method volume", result.volume.isFinite())
            assertTrue("$method riskPercent", result.riskPercent.isFinite())
            assertTrue("$method volume must stay sane", result.volume < 1_000_000.0)
        }
    }

    // ========================================================================
    // KELLY WITH DEGENERATE HISTORY
    // ========================================================================

    @Test
    fun `kelly stays finite when every losing trade is exactly break-even`() {
        // pnl == 0.0 counts as a loss (win = pnl > 0) but contributes 0.0 to the
        // average loss, so the win/loss ratio divides by zero.
        val engine = engine()
        repeat(10) { engine.recordTrade(100.0, "EURUSD") }
        repeat(5) { engine.recordTrade(0.0, "EURUSD") }

        val kelly = engine.calculateKellyPercent()

        assertTrue("Kelly must be finite, was $kelly", kelly.isFinite())
        assertTrue("Kelly must not be negative", kelly >= 0.0)
    }

    // ========================================================================
    // CONCURRENCY
    // ========================================================================

    @Test
    fun `concurrent trade recording does not lose balance updates`() {
        // recordTrade is called from background analysis/execution threads. The
        // balance is the number every risk gate is evaluated against, so a lost
        // update means the engine under-reports losses and keeps trading.
        val engine = engine()
        engine.updateConfig(
            RiskConfig(
                accountBalance = 1_000_000.0,
                maxDrawdownPercent = 100.0,
                maxConsecutiveLosses = Int.MAX_VALUE,
                maxDailyLossPercent = 1e9,
                maxWeeklyLossPercent = 1e9,
            ),
        )
        engine.updateBalance(1_000_000.0)

        val threads = 8
        val perThread = 1_000
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        repeat(threads) {
            pool.execute {
                try {
                    repeat(perThread) { engine.recordTrade(1.0, "EURUSD") }
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("workers did not finish in time", latch.await(60, TimeUnit.SECONDS))
        pool.shutdown()

        failure.get()?.let { throw AssertionError("recordTrade threw under concurrency", it) }

        val expected = 1_000_000.0 + threads * perThread
        assertEquals("balance lost concurrent updates", expected, engine.getBalance(), 1e-6)
    }

    // ========================================================================
    // HALT SEMANTICS
    // ========================================================================

    @Test
    fun `a catastrophic loss halts trading and blocks the next trade`() {
        val engine = engine()
        engine.updateConfig(RiskConfig(accountBalance = 10_000.0, maxDrawdownPercent = 15.0))
        engine.updateBalance(10_000.0)

        engine.recordTrade(-9_000.0, "EURUSD")

        assertTrue("engine must halt after a 90% drawdown", engine.isTradingHalted())
        val check = engine.canOpenTrade(riskAmount = 10.0)
        assertTrue("a halted engine must not allow a new trade", !check.allowed)
    }

    @Test
    fun `drawdown stays finite when the peak balance is zero`() {
        val engine = engine()
        engine.updateBalance(0.0)
        assertTrue(engine.getCurrentDrawdown().isFinite())
        assertTrue(engine.getRiskStatus().drawdownPercent.isFinite())
    }

    @Test
    fun `reset restores the configured account balance`() {
        val engine = engine()
        engine.updateConfig(RiskConfig(accountBalance = 50_000.0))
        engine.recordTrade(-1_234.0, "EURUSD")

        engine.reset()

        assertEquals(50_000.0, engine.getBalance(), 1e-9)
        assertEquals(0.0, engine.getCurrentDrawdown(), 1e-9)
        assertTrue(!engine.isTradingHalted())
        assertTrue(abs(engine.getDailyLoss()) < 1e-9)
    }
}
