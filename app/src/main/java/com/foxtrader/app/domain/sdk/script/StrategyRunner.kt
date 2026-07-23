package com.foxtrader.app.domain.sdk.script

import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import javax.inject.Inject

/**
 * Runs a [Strategy] (from the scripting DSL) through the [BacktestEngine].
 * Bridges the SDK scripting layer to the institutional backtester.
 */
class StrategyRunner @Inject constructor(
    private val backtestEngine: BacktestEngine,
    private val scriptEngine: ScriptEngine,
) {
    /**
     * Backtest a scripted strategy.
     */
    fun run(
        strategy: Strategy,
        candles: List<Candle>,
        symbol: String = "UNKNOWN",
        timeframe: Timeframe = Timeframe.M15,
    ): BacktestResult {
        val func: StrategyFunction = { c, i -> scriptEngine.evaluate(strategy, c, i) }
        return backtestEngine(candles, func, symbol, timeframe)
    }
}
