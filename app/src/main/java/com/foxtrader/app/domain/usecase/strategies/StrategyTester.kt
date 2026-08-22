package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-grade strategy tester. Runs any or all strategies from the
 * [StrategyLibrary] through the [BacktestEngine] and produces a ranked
 * comparative report.
 *
 * Usage:
 * ```
 * val results = strategyTester.testAll(candles, "EURUSD", Timeframe.H1)
 * val best = results.ranked.first()
 * ```
 */
@Singleton
class StrategyTester @Inject constructor(
    private val library: StrategyLibrary,
    private val backtestEngine: BacktestEngine,
    private val instrumentTypeResolver: InstrumentTypeResolver,
) {

    /**
     * Backtest a single strategy on the given data.
     */
    fun test(
        type: StrategyType,
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe = Timeframe.H1,
        config: BacktestConfig? = null,
    ): StrategyTestResult {
        val definition = library.get(type, symbol, timeframe)
        val effectiveConfig = config ?: defaultConfig(symbol)
        backtestEngine.updateConfig(effectiveConfig)
        val result = backtestEngine(candles, definition.function, symbol, timeframe)
        return StrategyTestResult(definition = definition, backtest = result)
    }


    /**
     * Backtest ALL strategies and produce a ranked comparative report.
     * Strategies that require more bars than available are skipped gracefully.
     */
    fun testAll(
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe = Timeframe.H1,
        config: BacktestConfig? = null,
    ): StrategyTestReport {
        val effectiveConfig = config ?: defaultConfig(symbol)
        backtestEngine.updateConfig(effectiveConfig)
        val results = mutableListOf<StrategyTestResult>()

        for ((type, definition) in library.all(symbol, timeframe)) {
            if (candles.size < definition.minimumBars) continue
            val backtest = backtestEngine(candles, definition.function, symbol, timeframe)
            results += StrategyTestResult(definition = definition, backtest = backtest)
        }

        val ranked = results.sortedByDescending { it.score }
        return StrategyTestReport(
            symbol = symbol,
            timeframe = timeframe,
            config = effectiveConfig,
            barCount = candles.size,
            results = ranked,
            bestStrategy = ranked.firstOrNull(),
            worstStrategy = ranked.lastOrNull(),
        )
    }

    private fun defaultConfig(symbol: String): BacktestConfig {
        val contractSize = instrumentTypeResolver.resolve(symbol).contractSize.toInt()
        return BacktestConfig(contractSize = contractSize)
    }
}

/**
 * Result of backtesting a single strategy. Includes a composite [score] that
 * balances profitability, risk-adjusted return, and consistency.
 */
data class StrategyTestResult(
    val definition: StrategyDefinition,
    val backtest: BacktestResult,
) {
    /** Composite score for ranking (higher = better). Weights:
     *  40% profit factor, 30% Sharpe, 20% win rate, 10% recovery factor. */
    val score: Double get() {
        val m = backtest.metrics
        if (m.totalTrades < 3) return 0.0
        val pfScore = m.profitFactor.coerceIn(0.0, 5.0) / 5.0 * 40.0
        val sharpeScore = m.sharpeRatio.coerceIn(-2.0, 4.0).let { (it + 2.0) / 6.0 } * 30.0
        val wrScore = m.winRate * 20.0
        val rfScore = m.recoveryFactor.coerceIn(0.0, 10.0) / 10.0 * 10.0
        return pfScore + sharpeScore + wrScore + rfScore
    }

    val profitable: Boolean get() = backtest.metrics.netProfit > 0
    val tradeCount: Int get() = backtest.metrics.totalTrades
}

/**
 * Comparative report of all tested strategies, ranked by composite score.
 */
data class StrategyTestReport(
    val symbol: String,
    val timeframe: Timeframe,
    val config: BacktestConfig,
    val barCount: Int,
    val results: List<StrategyTestResult>,
    val bestStrategy: StrategyTestResult?,
    val worstStrategy: StrategyTestResult?,
) {
    val profitableCount: Int get() = results.count { it.profitable }
    val totalStrategies: Int get() = results.size
}
