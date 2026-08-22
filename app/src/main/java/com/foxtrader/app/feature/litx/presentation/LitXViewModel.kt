package com.foxtrader.app.feature.litx.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.LitXSignalRecord
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.LitXSignalRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the standalone LIT X analysis screen.
 *
 * Self-contained: fetches candles + higher-timeframe context via existing
 * services, computes HTF bias/alignment with the existing [ConfluenceEngine],
 * and runs the additive [LitXEngine]. It does not touch the chart pipeline.
 */
@HiltViewModel
class LitXViewModel @Inject constructor(
    private val litXEngine: LitXEngine,
    private val marketRepository: MarketRepository,
    private val mtfContextProvider: MtfContextProvider,
    private val confluenceEngine: ConfluenceEngine,
    private val appPreferences: AppPreferences,
    private val litXSignalRepository: LitXSignalRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LitXUiState())
    val uiState: StateFlow<LitXUiState> = _uiState.asStateFlow()

    /** Persisted history of validated LIT X signals, newest first. */
    val recentSignals: StateFlow<List<LitXSignalRecord>> =
        litXSignalRepository.observeRecent(HISTORY_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var analyzeJob: Job? = null

    /** Analyze the given symbol/timeframe. Called by the screen on entry. */
    fun analyze(symbol: String, timeframe: Timeframe) {
        // Cancel any in-flight analysis so a superseded symbol/timeframe can
        // never apply a stale result over the newer request.
        analyzeJob?.cancel()
        _uiState.update {
            it.copy(symbol = symbol, timeframe = timeframe, isLoading = true, error = null)
        }
        analyzeJob = viewModelScope.launch {
            try {
                val sourced = marketRepository.getSourcedCandles(symbol, timeframe)
                val now = System.currentTimeMillis()
                val candles = ConfirmedBarPolicy.confirmedPrefix(sourced.candles, timeframe, now)
                val config = appPreferences.litXConfig.value
                if (candles.size < MIN_BARS) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            analysis = null,
                            isSynthetic = sourced.isSynthetic,
                            config = config,
                            error = "Not enough data to analyze $symbol.",
                        )
                    }
                    return@launch
                }
                val htfMap = ConfirmedBarPolicy.confirmedMap(
                    mtfContextProvider.getHtfContext(symbol, timeframe),
                    now,
                )
                val analysis = withContext(defaultDispatcher) {
                    val htfBias: Bias
                    val htfScore: Int
                    if (htfMap.isEmpty()) {
                        htfBias = Bias.NEUTRAL
                        htfScore = 50
                    } else {
                        val conf = confluenceEngine.analyze(htfMap + (timeframe to candles))
                        htfBias = conf.overallBias
                        htfScore = conf.confluenceScore
                    }
                    litXEngine.analyze(
                        symbol = symbol,
                        timeframe = timeframe,
                        candles = candles,
                        config = config,
                        htfBias = htfBias,
                        htfAlignmentScore = htfScore,
                    )
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        analysis = analysis,
                        isSynthetic = sourced.isSynthetic,
                        config = config,
                        error = null,
                    )
                }
                // Persist a validated setup to the reviewable history. Idempotent
                // by id (symbol:timeframe:barTime), and never for simulated data.
                analysis.signal?.let { sig ->
                    if (!sourced.isSynthetic) {
                        litXSignalRepository.save(LitXSignalRecord.from(sig))
                    }
                }
            } catch (e: CancellationException) {
                throw e // never swallow cancellation (superseded by a newer request)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "LIT X analysis failed.")
                }
            }
        }
    }

    private companion object {
        const val MIN_BARS = 50
        const val HISTORY_LIMIT = 50
    }
}
