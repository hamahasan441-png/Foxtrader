package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules a windowed backtest must obey. These are what keep a
 * date-limited Lab run, an on-chart research run, and a mode-comparison run
 * measuring the same thing.
 */
class WindowedBacktestTest {

    private val candles = List(200) { index ->
        Candle(index * 3_600_000L, 100.0, 101.0, 99.0, 100.5, 1_000.0)
    }

    @Test
    fun `causal series keeps every warm-up bar before the window`() {
        val window = HistoricalTestWindow(120, 150)
        val series = WindowedBacktest.causalSeries(candles, window)

        // Warm-up is the whole prefix, so indicator state at the window start is
        // identical to a full-history run.
        assertEquals(151, series.size)
        assertEquals(candles[0], series.first())
        assertEquals(candles[150], series.last())
    }

    @Test
    fun `no bar after the window end is visible to the engine`() {
        val window = HistoricalTestWindow(10, 40)
        val series = WindowedBacktest.causalSeries(candles, window)
        assertTrue(series.none { it.timestamp > candles[40].timestamp })
    }

    @Test
    fun `guard admits entries only inside the window`() {
        val always: StrategyFunction = { prefix, index ->
            StrategySignal(
                index = index,
                timestamp = prefix[index].timestamp,
                direction = Direction.BULLISH,
                entry = 100.0,
                stopLoss = 99.0,
                takeProfit = 102.0,
                setupType = "test",
            )
        }
        val guarded = WindowedBacktest.guard(always, HistoricalTestWindow(50, 60))

        assertNull(guarded(candles, 49))
        assertNotNull(guarded(candles, 50))
        assertNotNull(guarded(candles, 60))
        assertNull(guarded(candles, 61))
    }

    /**
     * The report must describe the measured period, not the warm-up prefix —
     * otherwise a "March" backtest claims to have started in January.
     */
    @Test
    fun `finalize clips equity and reports the window dates`() {
        val window = HistoricalTestWindow(100, 120)
        // Build a real engine result rather than a hand-made one, so this test
        // stays honest if BacktestResult's shape changes.
        val raw = BacktestEngine()(
            candles = candles,
            strategy = { _, _ -> null },
            symbol = "EURUSD",
            timeframe = com.foxtrader.app.domain.model.Timeframe.H1,
        ).let { result ->
            result.copy(
                equityCurve = (0..150).map { index ->
                    com.foxtrader.app.domain.model.EquityPoint(
                        index = index,
                        timestamp = candles[index].timestamp,
                        balance = 100_000.0,
                        drawdown = 0.0,
                        drawdownPercent = 0.0,
                    )
                },
            )
        }

        val finalized = WindowedBacktest.finalize(raw, candles, window)

        assertEquals(21, finalized.equityCurve.size)
        assertTrue(finalized.equityCurve.all { it.index in 100..120 })
        assertEquals(candles[100].timestamp, finalized.startDate)
        assertEquals(candles[120].timestamp, finalized.endDate)
    }

    @Test
    fun `a window shorter than two bars is rejected`() {
        val error = runCatching {
            WindowedBacktest.requireUsable(candles, HistoricalTestWindow(5, 5))
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `a window past the end of the series is clamped, not thrown`() {
        val clamped = WindowedBacktest.requireUsable(candles, HistoricalTestWindow(190, 5_000))
        assertEquals(candles.lastIndex, clamped.endIndex)
        assertEquals(190, clamped.startIndex)
    }
}
