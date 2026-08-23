package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.TradeOpportunity
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import com.foxtrader.app.domain.usecase.tradepro.OpportunityScorer
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import com.foxtrader.app.domain.usecase.watchlist.ActiveWatchlistSymbols
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Evaluates the persisted watchlist for TRADEPRO setup readiness. */
@HiltViewModel
class OpportunityBoardViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val activeWatchlistSymbols: ActiveWatchlistSymbols,
    private val signalEngine: TradeProSignalEngine,
    private val scorer: OpportunityScorer,
    private val mtfContextProvider: MtfContextProvider,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OpportunityBoardUiState())
    val uiState: StateFlow<OpportunityBoardUiState> = _uiState.asStateFlow()

    init { scan() }

    fun setTimeframe(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        _uiState.update { it.copy(timeframe = timeframe) }
        scan()
    }

    fun setFilter(filter: OpportunityFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun scan() {
        viewModelScope.launch {
            val timeframe = _uiState.value.timeframe
            _uiState.update { it.copy(isScanning = true, error = null) }
            try {
                val config = appPreferences.tradeProConfig.value
                val watchlist = activeWatchlistSymbols(MAX_SYMBOLS)
                var hadSynthetic = false
                val opportunities = ArrayList<TradeOpportunity>(watchlist.size)

                for (entry in watchlist) {
                    val sourced = repository.getSourcedCandles(entry.symbol, timeframe)
                    val now = System.currentTimeMillis()
                    val candles = ConfirmedBarPolicy.confirmedPrefix(sourced.candles, timeframe, now)
                    if (sourced.isSynthetic) hadSynthetic = true
                    if (candles.size < MIN_BARS) {
                        opportunities += TradeOpportunity.noData(entry.symbol, "Insufficient data")
                        continue
                    }
                    val htf = ConfirmedBarPolicy.confirmedMap(
                        mtfContextProvider.getHtfContext(entry.symbol, timeframe),
                        now,
                    )
                    val opportunity = withContext(defaultDispatcher) {
                        val analysis = signalEngine.analyze(entry.symbol, candles, config, htf)
                        scorer.score(analysis, candles.last().close, config)
                    }
                    opportunities += opportunity
                }

                val board = scorer.buildBoard(
                    opportunities = opportunities,
                    scannedSymbols = watchlist.size,
                    hadSyntheticData = hadSynthetic,
                    nowEpochMs = System.currentTimeMillis(),
                )
                _uiState.update { it.copy(isScanning = false, board = board, error = null) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, error = e.message ?: "Evaluation failed.") }
            }
        }
    }

    companion object {
        private const val MAX_SYMBOLS = 20
        private const val MIN_BARS = 30
    }
}
