package com.foxtrader.app.feature.strategies.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.backtest.AiScoredBacktestEngine
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.watchlist.ActiveWatchlistSymbols
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Strategies screen ViewModel. The strategy signal detector remains local to
 * this feature; the market universe comes directly from the persisted watchlist.
 */
@HiltViewModel
class StrategiesViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val activeWatchlistSymbols: ActiveWatchlistSymbols,
    private val signalScanner: StrategySignalScanner,
    private val aiBacktestEngine: AiScoredBacktestEngine,
    private val mtfContextProvider: MtfContextProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    init { scan() }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, error = null) }
            try {
                val signals = mutableListOf<StrategySignalItem>()
                val watchlist = activeWatchlistSymbols(15)

                for (ws in watchlist) {
                    val candles = repository.getSourcedCandles(ws.symbol).candles
                    if (candles.size < 50) continue
                    val htfCandles = mtfContextProvider.getHtfContext(ws.symbol, Timeframe.H1)
                    signals += signalScanner.detect(ws.symbol, candles, htfCandles)
                }

                signals.sortByDescending { it.confidence }
                _uiState.update {
                    it.copy(
                        signals = signals.toPersistentList(),
                        isScanning = false,
                        lastScanTime = System.currentTimeMillis(),
                    )
                }
                runAiBacktest()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, error = e.message) }
            }
        }
    }

    private suspend fun runAiBacktest() {
        try {
            val symbol = activeWatchlistSymbols(5).firstOrNull()?.symbol ?: return
            val sourced = repository.getSourcedCandles(symbol, Timeframe.H1)
            val candles = sourced.candles
            if (candles.size < 100 || !sourced.source.isTrustworthy) return

            val strategy: StrategyFunction = { c, i ->
                if (i < 50) null else {
                    val rsi = TechnicalIndicators.calculateRSI(c, 14)
                    val atr = TechnicalIndicators.calculateATR(c, 14)
                    val atrVal = atr[i]
                    when {
                        rsi[i] < 30.0 -> StrategySignal(
                            index = i,
                            timestamp = c[i].timestamp,
                            direction = Direction.BULLISH,
                            entry = c[i].close,
                            stopLoss = c[i].close - atrVal * 2,
                            takeProfit = c[i].close + atrVal * 3,
                            setupType = "RSI_OVERSOLD",
                        )
                        rsi[i] > 70.0 -> StrategySignal(
                            index = i,
                            timestamp = c[i].timestamp,
                            direction = Direction.BEARISH,
                            entry = c[i].close,
                            stopLoss = c[i].close + atrVal * 2,
                            takeProfit = c[i].close - atrVal * 3,
                            setupType = "RSI_OVERBOUGHT",
                        )
                        else -> null
                    }
                }
            }

            val result = aiBacktestEngine(
                candles = candles,
                strategy = strategy,
                symbol = symbol,
                timeframe = Timeframe.H1,
                dataSource = sourced.source,
            )

            _uiState.update { state ->
                state.copy(
                    aiBacktestEnabled = true,
                    aiApprovalRate = result.aiApprovalRate,
                    allTradesWinRate = result.metrics.winRate,
                    aiFilteredWinRate = result.aiFilteredMetrics?.winRate,
                    allTradesProfitFactor = result.metrics.profitFactor,
                    aiFilteredProfitFactor = result.aiFilteredMetrics?.profitFactor,
                    backtestTradeCount = result.metrics.totalTrades,
                    aiApprovedTradeCount = result.aiFilteredMetrics?.totalTrades,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Supplementary backtest must not take down the strategies screen.
        }
    }
}
