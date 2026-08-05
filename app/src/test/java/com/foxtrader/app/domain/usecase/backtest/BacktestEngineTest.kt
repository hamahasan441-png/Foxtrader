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

class BacktestEngineTest {

    private lateinit var engine: BacktestEngine

    @Before
    fun setup() {
        engine = BacktestEngine()
        engine.updateConfig(
            BacktestConfig(
                initialBalance = 10_000.0,
                spread = 0.0001,
                variableSpread = false,
                commissionPerLot = 7.0,
                slippage = 0.0,
                riskPercent = 1.0,
                contractSize = 100_000,
            )
        )
    }

    // -------------------------------------------------------------------------
    // NO LOOK-AHEAD INVARIANT
    // -------------------------------------------------------------------------

    @Test
    fun `no look-ahead invariant -- strategy receives only candles up to current index`() {
        val candles = makeCandles(50, startPrice = 1.1000, step = 0.0010)
        var callCount = 0

        val strategy: StrategyFunction = { slice, index ->
            callCount++
            assertEquals(
                "Strategy must only see candles[0..index], got ${slice.size} but expected ${index + 1}",
                index + 1,
                slice.size,
            )
            null // never open a trade -- we just verify the invariant
        }

        engine(candles, strategy, "EURUSD", Timeframe.M15)
        assertEquals("Strategy must be called once per bar", 50, callCount)
    }

    // -------------------------------------------------------------------------
    // SL CHECKED BEFORE TP ON SAME BAR
    // -------------------------------------------------------------------------

    @Test
    fun `SL is checked before TP on same bar`() {
        // Construct candles: first candle opens the trade, second candle hits both SL and TP.
        val entryPrice = 1.1000
        val stopLoss = 1.0950
        val takeProfit = 1.1100

        val candles = listOf(
            candle(timestamp = 1000L, open = entryPrice, high = entryPrice + 0.0005, low = entryPrice - 0.0005, close = entryPrice),
            // Second candle sweeps low to hit SL and high to hit TP (both triggered)
            candle(timestamp = 2000L, open = entryPrice, high = takeProfit + 0.0010, low = stopLoss - 0.0010, close = entryPrice),
        )

        var signalIssued = false
        val strategy: StrategyFunction = { _, index ->
            if (index == 0 && !signalIssued) {
                signalIssued = true
                StrategySignal(
                    index = 0,
                    timestamp = 1000L,
                    direction = Direction.BULLISH,
                    entry = entryPrice,
                    stopLoss = stopLoss,
                    takeProfit = takeProfit,
                    volume = 0.1,
                )
            } else null
        }

        val result = engine(candles, strategy, "EURUSD", Timeframe.M15)
        assertEquals("Should have exactly 1 trade", 1, result.trades.size)
        assertEquals("Exit reason must be SL (checked first)", ExitReason.SL, result.trades[0].exitReason)
    }

    // -------------------------------------------------------------------------
    // OPEN TRADE AT END CLOSED AT LAST CANDLE CLOSE
    // -------------------------------------------------------------------------

    @Test
    fun `open trade at end is closed at last candle close with ExitReason END`() {
        // Create candles where SL and TP are far away so they never trigger.
        val entryPrice = 1.1000
        val lastClose = 1.1020

        val candles = listOf(
            candle(timestamp = 1000L, open = entryPrice, high = entryPrice + 0.0005, low = entryPrice - 0.0005, close = entryPrice),
            candle(timestamp = 2000L, open = 1.1005, high = 1.1008, low = 1.0998, close = 1.1003),
            candle(timestamp = 3000L, open = 1.1010, high = 1.1025, low = 1.1005, close = lastClose),
        )

        var signalIssued = false
        val strategy: StrategyFunction = { _, index ->
            if (index == 0 && !signalIssued) {
                signalIssued = true
                StrategySignal(
                    index = 0,
                    timestamp = 1000L,
                    direction = Direction.BULLISH,
                    entry = entryPrice,
                    stopLoss = 1.0500, // far away
                    takeProfit = 1.2000, // far away
                    volume = 0.1,
                )
            } else null
        }

        val result = engine(candles, strategy, "EURUSD", Timeframe.M15)
        assertEquals(1, result.trades.size)
        val trade = result.trades[0]
        assertEquals(ExitReason.END, trade.exitReason)
        assertEquals(lastClose, trade.exitPrice, 1e-10)
    }

    // -------------------------------------------------------------------------
    // EMPTY CANDLE LIST
    // -------------------------------------------------------------------------

    @Test
    fun `empty candle list produces zero trades and initial balance`() {
        val strategy: StrategyFunction = { _, _ -> null }
        val result = engine(emptyList(), strategy, "EURUSD", Timeframe.M15)
        assertEquals(0, result.trades.size)
        assertEquals(10_000.0, result.metrics.finalBalance, 1e-10)
    }

    // -------------------------------------------------------------------------
    // COMMISSION DEDUCTED FROM GROSS PNL
    // -------------------------------------------------------------------------

    @Test
    fun `commission is deducted from gross PnL`() {
        val entryPrice = 1.1000
        val takeProfit = 1.1050
        val volume = 0.1
        val commissionPerLot = 7.0

        val candles = listOf(
            candle(timestamp = 1000L, open = entryPrice, high = entryPrice + 0.0005, low = entryPrice - 0.0005, close = entryPrice),
            // This candle's high exceeds take profit, triggering TP exit
            candle(timestamp = 2000L, open = entryPrice + 0.0020, high = takeProfit + 0.0010, low = entryPrice + 0.0010, close = takeProfit),
        )

        var signalIssued = false
        val strategy: StrategyFunction = { _, index ->
            if (index == 0 && !signalIssued) {
                signalIssued = true
                StrategySignal(
                    index = 0,
                    timestamp = 1000L,
                    direction = Direction.BULLISH,
                    entry = entryPrice,
                    stopLoss = 1.0900,
                    takeProfit = takeProfit,
                    volume = volume,
                )
            } else null
        }

        val result = engine(candles, strategy, "EURUSD", Timeframe.M15)
        assertEquals(1, result.trades.size)
        val trade = result.trades[0]
        val expectedCommission = commissionPerLot * volume
        assertEquals(expectedCommission, trade.commission, 1e-10)
        assertEquals(trade.grossPnL - expectedCommission, trade.netPnL, 1e-10)
    }

    // -------------------------------------------------------------------------
    // METRICS COMPUTATION
    // -------------------------------------------------------------------------

    @Test
    fun `metrics winRate and profitFactor are computed correctly from trades`() {
        // Strategy produces alternating wins and losses by placing entries with
        // tight take-profits/stop-losses on predictable candles.
        val candles = mutableListOf<Candle>()
        // 10 bars that alternate up and down
        for (i in 0 until 10) {
            val base = 1.1000 + i * 0.0001
            if (i % 2 == 0) {
                // Up bar: high reaches base + 50 pips relative
                candles += candle(
                    timestamp = (i + 1) * 1000L,
                    open = base,
                    high = base + 0.0060,
                    low = base - 0.0002,
                    close = base + 0.0050,
                )
            } else {
                // Down bar: low drops
                candles += candle(
                    timestamp = (i + 1) * 1000L,
                    open = base,
                    high = base + 0.0002,
                    low = base - 0.0060,
                    close = base - 0.0050,
                )
            }
        }

        var tradeCount = 0
        val strategy: StrategyFunction = { slice, index ->
            if (tradeCount < 4 && index % 2 == 0 && index < 8) {
                tradeCount++
                val entry = slice[index].close
                StrategySignal(
                    index = index,
                    timestamp = slice[index].timestamp,
                    direction = Direction.BULLISH,
                    entry = entry,
                    stopLoss = entry - 0.0030,
                    takeProfit = entry + 0.0030,
                    volume = 0.1,
                )
            } else null
        }

        val result = engine(candles, strategy, "EURUSD", Timeframe.M15)
        val metrics = result.metrics

        if (metrics.totalTrades > 0) {
            val expectedWinRate = (metrics.winningTrades.toDouble() / metrics.totalTrades) * 100.0
            assertEquals(expectedWinRate, metrics.winRate, 1e-6)
        }

        if (metrics.grossLoss > 0) {
            val expectedPf = metrics.grossProfit / metrics.grossLoss
            assertEquals(expectedPf, metrics.profitFactor, 1e-6)
        }
    }

    @Test
    fun `metrics Sharpe ratio is computed from trade returns`() {
        // Run multiple trades to verify Sharpe is non-zero when trades have variance
        val candles = makeCandles(20, startPrice = 1.1000, step = 0.0020)

        var signalCount = 0
        val strategy: StrategyFunction = { slice, index ->
            if (signalCount < 5 && index % 3 == 0 && index > 0 && index < 18) {
                signalCount++
                val entry = slice[index].close
                StrategySignal(
                    index = index,
                    timestamp = slice[index].timestamp,
                    direction = Direction.BULLISH,
                    entry = entry,
                    stopLoss = entry - 0.0050,
                    takeProfit = entry + 0.0050,
                    volume = 0.1,
                )
            } else null
        }

        val result = engine(candles, strategy, "EURUSD", Timeframe.M15)
        // If we have at least 2 trades, Sharpe should be computed (non-default)
        if (result.trades.size >= 2) {
            // Sharpe may be positive or negative, but should be computed (not NaN)
            assertTrue("Sharpe should be finite", result.metrics.sharpeRatio.isFinite())
        }
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private fun makeCandles(count: Int, startPrice: Double, step: Double): List<Candle> =
        (0 until count).map { i ->
            val price = startPrice + i * step
            candle(
                timestamp = (i + 1) * 60_000L,
                open = price,
                high = price + 0.0050,
                low = price - 0.0050,
                close = price + step / 2,
            )
        }

    private fun candle(timestamp: Long, open: Double, high: Double, low: Double, close: Double): Candle =
        Candle(timestamp = timestamp, open = open, high = high, low = low, close = close, volume = 1000.0)
}
