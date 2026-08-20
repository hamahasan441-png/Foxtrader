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
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
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
 * Strategies screen ViewModel — scans watchlist symbols and surfaces
 * actionable setups detected by [StrategySignalScanner]. Signal-generation logic
 * lives in that scanner; this ViewModel only orchestrates scanning, the
 * supplementary AI-scored backtest, and UI state.
 */
@HiltViewModel
class StrategiesViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val scannerUseCase: ScannerUseCase,
    private val signalScanner: StrategySignalScanner,
    private val aiBacktestEngine: AiScoredBacktestEngine,
    private val mtfContextProvider: MtfContextProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, error = null) }
            try {
                val signals = mutableListOf<StrategySignalItem>()
                val watchlist = scannerUseCase.getWatchlist().filter { it.enabled }.take(15)

                for (ws in watchlist) {
                    val candles = repository.getSourcedCandles(ws.symbol).candles
                    if (candles.size < 50) continue
                    // Validate the scanner's TRADEPRO read against real higher-timeframe bias, the
                    // same source the chart and alert worker use, so a watchlist signal is consistent
                    // with what the trader sees elsewhere. Scans default to H1 (getSourcedCandles).
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
                // Run an AI-scored backtest on the first scanned symbol (representative).
                runAiBacktest()
            } catch (cancel: CancellationException) {
                // Never swallow cancellation: doing so breaks structured
                // concurrency and surfaces a routine screen-close or
                // re-run as a spurious on-screen error.
                throw cancel
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, error = e.message) }
            }
        }
    }

    /**
     * Run AI-scored backtest on the first available symbol with sufficient data.
     * Uses a simple RSI mean-reversion strategy as the demonstration strategy.
     */
    private suspend fun runAiBacktest() {
        try {
            val watchlist = scannerUseCase.getWatchlist().filter { it.enabled }.take(5)
            val symbol = watchlist.firstOrNull()?.symbol ?: return
            val sourced = repository.getSourcedCandles(symbol, Timeframe.H1)
            val candles = sourced.candles
            if (candles.size < 100) return

            // Generated bars must never be presented as trustworthy performance.
            if (!sourced.source.isTrustworthy) return

            val strategy: StrategyFunction = { c, i ->
                if (i < 50) null else {
                    val rsi = TechnicalIndicators.calculateRSI(c, 14)
                    val atr = TechnicalIndicators.calculateATR(c, 14)
                    val atrVal = atr[i]
                    when {
                        rsi[i] < 30.0 -> StrategySignal(
                            index = i, timestamp = c[i].timestamp,
                            direction = Direction.BULLISH,
                            entry = c[i].close,
                            stopLoss = c[i].close - atrVal * 2,
                            takeProfit = c[i].close + atrVal * 3,
                            setupType = "RSI_OVERSOLD",
                        )
                        rsi[i] > 70.0 -> StrategySignal(
                            index = i, timestamp = c[i].timestamp,
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
            // AI backtest is supplementary — don't crash the strategies screen.
        }
    }
}
