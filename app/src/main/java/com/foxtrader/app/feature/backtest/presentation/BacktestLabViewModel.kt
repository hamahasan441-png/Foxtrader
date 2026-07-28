package com.foxtrader.app.feature.backtest.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.backtest.AiScoredBacktestEngine
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsEngine
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backtesting Lab ViewModel.
 *
 * Runs non-repainting strategy simulations on cached market data and optionally
 * scores every trade through the AI Master Decision pipeline.
 */
@HiltViewModel
class BacktestLabViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val backtestEngine: BacktestEngine,
    private val aiScoredBacktestEngine: AiScoredBacktestEngine,
    private val analyticsEngine: BacktestAnalyticsEngine,
    private val instrumentTypeResolver: InstrumentTypeResolver,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BacktestLabUiState())
    val uiState: StateFlow<BacktestLabUiState> = _uiState.asStateFlow()

    init {
        runBacktest()
    }

    fun setSymbol(symbol: String) {
        _uiState.update { it.copy(symbol = symbol, result = null, analyticsReport = null, error = null) }
    }

    fun setTimeframe(timeframe: Timeframe) {
        _uiState.update { it.copy(timeframe = timeframe, result = null, analyticsReport = null, error = null) }
    }

    fun setStrategy(strategy: BacktestStrategyTemplate) {
        _uiState.update { it.copy(strategy = strategy, result = null, analyticsReport = null, error = null) }
    }

    fun setRiskPercent(value: Double) {
        _uiState.update { it.copy(riskPercent = value.coerceIn(0.1, 5.0), result = null, analyticsReport = null) }
    }

    fun setAiScoringEnabled(enabled: Boolean) {
        _uiState.update { it.copy(aiScoringEnabled = enabled, result = null, analyticsReport = null) }
    }

    fun runBacktest() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isRunning = true, error = null) }
            try {
                // Sourced: an AI-gated backtest over generated bars would
                // report a fabricated edge, so provenance reaches the engine.
                val sourced = repository.getSourcedCandles(state.symbol, state.timeframe)
                val candles = sourced.candles
                require(candles.size >= MIN_REQUIRED_BARS) {
                    "Need at least $MIN_REQUIRED_BARS candles for a reliable backtest. Got ${candles.size}."
                }

                val config = BacktestConfig(
                    initialBalance = state.initialBalance,
                    riskPercent = state.riskPercent,
                    // Resolve the contract size for the instrument being tested so
                    // crypto/gold/index P&L is not computed as a forex lot.
                    contractSize = instrumentTypeResolver.resolve(state.symbol).contractSize.toInt(),
                )
                val strategy = buildStrategy(state.strategy)

                val result = withContext(defaultDispatcher) {
                    if (state.aiScoringEnabled) {
                        aiScoredBacktestEngine.updateConfig(config)
                        aiScoredBacktestEngine(
                            candles = candles,
                            strategy = strategy,
                            symbol = state.symbol,
                            timeframe = state.timeframe,
                            dataSource = sourced.source,
                        )
                    } else {
                        backtestEngine.updateConfig(config)
                        backtestEngine(
                            candles = candles,
                            strategy = strategy,
                            symbol = state.symbol,
                            timeframe = state.timeframe,
                        )
                    }
                }

                val analytics = withContext(defaultDispatcher) {
                    analyticsEngine.analyze(result)
                }

                _uiState.update {
                    it.copy(
                        isRunning = false,
                        result = result,
                        analyticsReport = analytics,
                        lastRunTime = System.currentTimeMillis(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        error = e.message ?: "Backtest failed.",
                    )
                }
            }
        }
    }

    private fun buildStrategy(template: BacktestStrategyTemplate): StrategyFunction = when (template) {
        BacktestStrategyTemplate.RSI_MEAN_REVERSION -> ::rsiMeanReversion
        BacktestStrategyTemplate.EMA_TREND_PULLBACK -> ::emaTrendPullback
        BacktestStrategyTemplate.ATR_BREAKOUT -> ::atrBreakout
    }

    private fun rsiMeanReversion(candles: List<Candle>, index: Int): StrategySignal? {
        if (index < 30 || index >= candles.size) return null
        val rsi = TechnicalIndicators.calculateRSI(candles, 14)
        val atr = TechnicalIndicators.calculateATR(candles, 14)
        val atrValue = atr.getOrNull(index)?.takeIf { it > 0.0 } ?: return null
        val close = candles[index].close
        return when {
            rsi[index] <= 30.0 -> StrategySignal(
                index = index,
                timestamp = candles[index].timestamp,
                direction = Direction.BULLISH,
                entry = close,
                stopLoss = close - atrValue * 1.8,
                takeProfit = close + atrValue * 2.7,
                setupType = BacktestStrategyTemplate.RSI_MEAN_REVERSION.displayName,
            )
            rsi[index] >= 70.0 -> StrategySignal(
                index = index,
                timestamp = candles[index].timestamp,
                direction = Direction.BEARISH,
                entry = close,
                stopLoss = close + atrValue * 1.8,
                takeProfit = close - atrValue * 2.7,
                setupType = BacktestStrategyTemplate.RSI_MEAN_REVERSION.displayName,
            )
            else -> null
        }
    }

    private fun emaTrendPullback(candles: List<Candle>, index: Int): StrategySignal? {
        if (index < 60 || index >= candles.size) return null
        val atr = TechnicalIndicators.calculateATR(candles, 14).getOrNull(index)?.takeIf { it > 0.0 } ?: return null
        val fast = sma(candles, index, 20)
        val slow = sma(candles, index, 50)
        val last = candles[index]
        val prior = candles[index - 1]
        return when {
            fast > slow && last.low <= fast && last.close > fast && prior.close <= fast -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BULLISH,
                entry = last.close,
                stopLoss = last.close - atr * 2.0,
                takeProfit = last.close + atr * 3.0,
                setupType = BacktestStrategyTemplate.EMA_TREND_PULLBACK.displayName,
            )
            fast < slow && last.high >= fast && last.close < fast && prior.close >= fast -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BEARISH,
                entry = last.close,
                stopLoss = last.close + atr * 2.0,
                takeProfit = last.close - atr * 3.0,
                setupType = BacktestStrategyTemplate.EMA_TREND_PULLBACK.displayName,
            )
            else -> null
        }
    }

    private fun atrBreakout(candles: List<Candle>, index: Int): StrategySignal? {
        if (index < 40 || index >= candles.size) return null
        val last = candles[index]
        val priorRange = candles.subList(index - BREAKOUT_LOOKBACK, index)
        val rangeHigh = priorRange.maxOf { it.high }
        val rangeLow = priorRange.minOf { it.low }
        val atr = TechnicalIndicators.calculateATR(candles, 14).getOrNull(index)?.takeIf { it > 0.0 } ?: return null
        return when {
            last.close > rangeHigh + atr * 0.15 -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BULLISH,
                entry = last.close,
                stopLoss = last.close - atr * 2.2,
                takeProfit = last.close + atr * 3.3,
                setupType = BacktestStrategyTemplate.ATR_BREAKOUT.displayName,
            )
            last.close < rangeLow - atr * 0.15 -> StrategySignal(
                index = index,
                timestamp = last.timestamp,
                direction = Direction.BEARISH,
                entry = last.close,
                stopLoss = last.close + atr * 2.2,
                takeProfit = last.close - atr * 3.3,
                setupType = BacktestStrategyTemplate.ATR_BREAKOUT.displayName,
            )
            else -> null
        }
    }

    private fun sma(candles: List<Candle>, index: Int, period: Int): Double {
        val start = (index - period + 1).coerceAtLeast(0)
        val slice = candles.subList(start, index + 1)
        return slice.sumOf { it.close } / slice.size
    }

    private companion object {
        const val MIN_REQUIRED_BARS = 100
        const val BREAKOUT_LOOKBACK = 20
    }
}
