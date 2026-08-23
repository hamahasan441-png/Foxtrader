package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalBacktestRunnerTest {
    private val candles = List(30) { i ->
        val p = 100.0 + i
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = p,
            high = p + 1.0,
            low = p - 1.0,
            close = p + 0.25,
            volume = 100.0 + i,
        )
    }

    @Test
    fun `selected window keeps full causal warmup but calls strategy only inside range`() {
        val engine = BacktestEngine()
        val runner = HistoricalBacktestRunner(engine)
        val observed = mutableListOf<Pair<Int, Int>>()
        val strategy: StrategyFunction = { prefix, index ->
            observed += index to prefix.size
            null
        }

        val result = runner(
            candles = candles,
            strategy = strategy,
            window = HistoricalTestWindow(startIndex = 10, endIndex = 14),
            symbol = "EURUSD",
            timeframe = Timeframe.M1,
        )

        assertEquals((10..14).toList(), observed.map { it.first })
        assertEquals((11..15).toList(), observed.map { it.second })
        assertEquals(candles[10].timestamp, result.startDate)
        assertEquals(candles[14].timestamp, result.endDate)
        assertEquals((10..14).toList(), result.equityCurve.map { it.index })
        assertTrue(result.trades.isEmpty())
    }

    @Test
    fun `trade opened in selected range is force closed at selected end`() {
        val runner = HistoricalBacktestRunner(BacktestEngine())
        val window = HistoricalTestWindow(startIndex = 10, endIndex = 14)
        val strategy: StrategyFunction = { _, index ->
            if (index != 10) null else StrategySignal(
                index = index,
                timestamp = candles[index].timestamp,
                direction = Direction.BULLISH,
                entry = candles[index].close,
                stopLoss = candles[index].close - 100.0,
                takeProfit = candles[index].close + 100.0,
                volume = 0.01,
                setupType = "RANGE_BOUNDARY_FIXTURE",
            )
        }

        val result = runner(
            candles = candles,
            strategy = strategy,
            window = window,
            symbol = "EURUSD",
            timeframe = Timeframe.M1,
        )

        assertEquals(1, result.trades.size)
        val trade = result.trades.single()
        assertEquals(10, trade.entryIndex)
        assertEquals(14, trade.exitIndex)
        assertEquals(candles[14].timestamp, trade.exitTime)
        assertEquals(ExitReason.END, trade.exitReason)
    }

    @Test
    fun `bars after selected end cannot mutate historical result`() {
        val runnerA = HistoricalBacktestRunner(BacktestEngine())
        val runnerB = HistoricalBacktestRunner(BacktestEngine())
        val window = HistoricalTestWindow(8, 16)
        val strategy: StrategyFunction = { prefix, index ->
            // Deliberately consume the whole visible prefix; future candles must
            // still be irrelevant because the runner truncates at window end.
            prefix.take(index + 1).sumOf { it.close }
            null
        }

        val mutated = candles.mapIndexed { index, candle ->
            if (index <= window.endIndex) candle else candle.copy(
                open = candle.open * 10.0,
                high = candle.high * 10.0,
                low = candle.low * 10.0,
                close = candle.close * 10.0,
                volume = candle.volume * 100.0,
            )
        }

        val a = runnerA(candles, strategy, window, "EURUSD", Timeframe.M1)
        val b = runnerB(mutated, strategy, window, "EURUSD", Timeframe.M1)

        assertEquals(a.startDate, b.startDate)
        assertEquals(a.endDate, b.endDate)
        assertEquals(a.trades, b.trades)
        assertEquals(a.equityCurve, b.equityCurve)
        assertEquals(a.metrics, b.metrics)
    }

    @Test
    fun `visible window conversion is deterministic and clamped`() {
        val window = HistoricalTestWindow.visible(
            startIndex = 12.7f,
            visibleBars = 7.2f,
            lastIndex = 19,
        )
        assertEquals(12, window.startIndex)
        assertEquals(19, window.endIndex)
        assertEquals(8, window.barCount)
    }
}
