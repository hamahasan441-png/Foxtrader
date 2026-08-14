package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies slippage is applied symmetrically on entry fill AND TP exits.
 * Before the P1 fix, slippage was only deducted on SL exits.
 */
class BacktestEngineSlippageTest {

    private val SLIP   = 0.0001
    private val SPREAD = 0.00002
    private val engine = BacktestEngine()

    @Before fun setup() = engine.updateConfig(BacktestConfig(
        initialBalance = 10_000.0, slippage = SLIP, spread = SPREAD,
        variableSpread = false, commissionPerLot = 0.0,
        riskPercent = 1.0, contractSize = 100_000))

    private fun flat(t: Long, p: Double) =
        Candle(t, p, p + 0.0001, p - 0.0001, p, 500.0)
    // TP-sweep candles must NOT touch the stop level: the engine checks SL
    // before TP on the same bar, so a candle whose low reaches the SL would
    // correctly exit at SL and the TP fill could never be observed.
    private fun tpBull(t: Long, tp: Double) =
        Candle(t, tp - 0.01, tp + 0.01, tp - 0.015, tp, 1_000.0)
    private fun tpBear(t: Long, tp: Double) =
        Candle(t, tp + 0.01, tp + 0.015, tp - 0.01, tp, 1_000.0)
    private fun slBull(t: Long, sl: Double) =
        Candle(t, sl + 0.01, sl + 0.02, sl - 0.01, sl, 1_000.0)

    private fun strategy(fireAt: Int, entry: Double, sl: Double, tp: Double,
                          dir: Direction = Direction.BULLISH): StrategyFunction =
        { bars, i -> if (i == fireAt) StrategySignal(i, bars[i].timestamp, dir, entry, sl, tp,
            setupType = "SLIP_TEST") else null }

    private fun warmup(n: Int, price: Double) = (0 until n).map { flat(it * 3_600_000L, price) }

    @Test fun `bullish TP exit price is reduced by slippage`() {
        val candles = warmup(60, 1.1) +
            flat(60 * 3_600_000L, 1.1) + tpBull(61 * 3_600_000L, 1.11)
        val t = engine(candles, strategy(60, 1.1, 1.09, 1.11), "EURUSD", Timeframe.H1).trades[0]
        assertEquals(ExitReason.TP, t.exitReason)
        assertEquals(1.11 - SLIP,  t.exitPrice, 1e-9)   // TP slip applied
        assertEquals(1.1  + SLIP,  t.entryPrice, 1e-9)  // entry slip applied
    }

    @Test fun `bullish SL exit price is reduced by slippage`() {
        val candles = warmup(60, 1.1) +
            flat(60 * 3_600_000L, 1.1) + slBull(61 * 3_600_000L, 1.09)
        val t = engine(candles, strategy(60, 1.1, 1.09, 1.12), "EURUSD", Timeframe.H1).trades[0]
        assertEquals(ExitReason.SL, t.exitReason)
        assertEquals(1.09 - SLIP, t.exitPrice, 1e-9)
    }

    @Test fun `bearish TP exit price is increased by slippage`() {
        val candles = warmup(60, 1.1) +
            flat(60 * 3_600_000L, 1.1) + tpBear(61 * 3_600_000L, 1.09)
        val t = engine(candles, strategy(60, 1.1, 1.11, 1.09, Direction.BEARISH),
            "EURUSD", Timeframe.H1).trades[0]
        assertEquals(ExitReason.TP, t.exitReason)
        assertEquals(1.09 + SLIP, t.exitPrice, 1e-9)   // TP slip applied
        assertEquals(1.1  - SLIP, t.entryPrice, 1e-9)  // entry slip applied
    }

    @Test fun `net PnL is lower with slippage than without on winning trade`() {
        val candles = warmup(60, 1.1) +
            flat(60 * 3_600_000L, 1.1) + tpBull(61 * 3_600_000L, 1.11)
        val withSlip = engine(candles, strategy(60, 1.1, 1.09, 1.11), "EURUSD", Timeframe.H1)
        engine.updateConfig(BacktestConfig(slippage = 0.0, spread = SPREAD, variableSpread = false,
            commissionPerLot = 0.0, riskPercent = 1.0, contractSize = 100_000))
        val noSlip = engine(candles, strategy(60, 1.1, 1.09, 1.11), "EURUSD", Timeframe.H1)
        assertTrue(withSlip.metrics.netProfit < noSlip.metrics.netProfit)
    }
}
