package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.DailyPlan
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import com.foxtrader.app.domain.usecase.tradepro.DailyPlanEngine
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
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/** Builds the pre-market plan from the persisted watchlist and recent journal history. */
@HiltViewModel
class DailyPlanViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val activeWatchlistSymbols: ActiveWatchlistSymbols,
    private val signalEngine: TradeProSignalEngine,
    private val mtfContextProvider: MtfContextProvider,
    private val planEngine: DailyPlanEngine,
    private val journalRepository: JournalRepository,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyPlanUiState())
    val uiState: StateFlow<DailyPlanUiState> = _uiState.asStateFlow()

    fun setTimeframe(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        _uiState.update { it.copy(timeframe = timeframe) }
    }

    fun generatePlan() {
        viewModelScope.launch {
            val timeframe = _uiState.value.timeframe
            _uiState.update { it.copy(isGenerating = true, error = null) }
            try {
                val config = appPreferences.tradeProConfig.value
                val allEntries = journalRepository.getAllEntries()
                val recentClosed = allEntries
                    .filter { !it.isOpen && it.pnl != null }
                    .sortedBy { it.exitTime ?: it.entryTime }

                val watchlist = activeWatchlistSymbols(MAX_SYMBOLS)
                val analyses = ArrayList<TradeProAnalysis>(watchlist.size)
                for (symbol in watchlist) {
                    val now = System.currentTimeMillis()
                    val candles = ConfirmedBarPolicy.confirmedPrefix(
                        repository.getSourcedCandles(symbol.symbol, timeframe).candles,
                        timeframe,
                        now,
                    )
                    if (candles.size < MIN_BARS) continue
                    val htf = ConfirmedBarPolicy.confirmedMap(
                        mtfContextProvider.getHtfContext(symbol.symbol, timeframe),
                        now,
                    )
                    val analysis = withContext(defaultDispatcher) {
                        signalEngine.analyze(symbol.symbol, candles, config, htf)
                    }
                    analyses += analysis
                }

                val plan = withContext(defaultDispatcher) {
                    planEngine.generatePlan(analyses, recentClosed, config)
                }
                val review = planEngine.reviewSession(plan, todaysEntries(allEntries, plan))
                _uiState.update { it.copy(isGenerating = false, plan = plan, review = review, error = null) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _uiState.update { it.copy(isGenerating = false, error = e.message ?: "Failed to build the plan.") }
            }
        }
    }

    fun refreshReview() {
        val plan = _uiState.value.plan ?: return
        viewModelScope.launch {
            val allEntries = journalRepository.getAllEntries()
            val review = planEngine.reviewSession(plan, todaysEntries(allEntries, plan))
            _uiState.update { it.copy(review = review) }
        }
    }

    private fun todaysEntries(entries: List<JournalEntry>, plan: DailyPlan): List<JournalEntry> {
        val day = plan.generatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val start = startOfDayUtc(day)
        val end = start + DAY_MS
        return entries.filter { it.entryTime in start until end }
    }

    private fun startOfDayUtc(epochMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    companion object {
        private const val MAX_SYMBOLS = 20
        private const val MIN_BARS = 30
        private const val DAY_MS = 86_400_000L
    }
}
