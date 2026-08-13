package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.ComplianceViolation
import com.foxtrader.app.domain.model.tradepro.SimulationPerformance
import com.foxtrader.app.domain.model.tradepro.SimulationSession
import com.foxtrader.app.domain.model.tradepro.SimulationSpeed
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.tradepro.TradeProSimulationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TradeProSimulatorViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val simulationEngine: TradeProSimulationEngine,
    private val appPreferences: AppPreferences,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TradeProSimulatorUiState())
    val uiState: StateFlow<TradeProSimulatorUiState> = _uiState.asStateFlow()

    private var allCandles: List<com.foxtrader.app.domain.model.Candle> = emptyList()
    private var violations: MutableList<ComplianceViolation> = mutableListOf()
    private var playJob: Job? = null

    fun setSymbol(symbol: String) {
        if (symbol == _uiState.value.symbol) return
        _uiState.update { it.copy(symbol = symbol, session = null, performance = SimulationPerformance.EMPTY) }
        stopPlayback()
    }

    fun setTimeframe(timeframe: Timeframe) {
        if (timeframe == _uiState.value.timeframe) return
        _uiState.update { it.copy(timeframe = timeframe, session = null, performance = SimulationPerformance.EMPTY) }
        stopPlayback()
    }

    fun startSession() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val sourced = repository.getSourcedCandles(state.symbol, state.timeframe)
                allCandles = sourced.candles
                violations = mutableListOf()
                val config = appPreferences.tradeProConfig.value
                val session = withContext(defaultDispatcher) {
                    simulationEngine.createSession(state.symbol, allCandles, config, state.speed)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        session = session,
                        performance = SimulationPerformance.EMPTY,
                        isSynthetic = sourced.isSynthetic,
                        isPlaying = false,
                    )
                }
            } catch (cancel: CancellationException) {
                // Never swallow cancellation: doing so breaks structured
                // concurrency and surfaces a routine screen-close or
                // re-run as a spurious on-screen error.
                throw cancel
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to start session.") }
            }
        }
    }

    fun stepForward() {
        val session = _uiState.value.session ?: return
        if (session.isComplete) return
        val config = appPreferences.tradeProConfig.value
        val updated = simulationEngine.stepForward(session, allCandles, config)
        updateSession(updated)
    }

    fun placeTrade(direction: Direction) {
        val session = _uiState.value.session ?: return
        val config = appPreferences.tradeProConfig.value
        val (updated, violation) = simulationEngine.placeTrade(session, direction, config)
        if (violation != null) violations.add(violation)
        updateSession(updated)
        _uiState.update { it.copy(lastViolation = violation) }
    }

    fun closeManually() {
        val session = _uiState.value.session ?: return
        val updated = simulationEngine.closeManually(session)
        updateSession(updated)
    }

    fun moveStopToBreakeven() {
        val session = _uiState.value.session ?: return
        val updated = simulationEngine.moveStopToBreakeven(session)
        updateSession(updated)
    }

    fun togglePlayback() {
        if (_uiState.value.isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    fun setSpeed(speed: SimulationSpeed) {
        _uiState.update { it.copy(speed = speed) }
        val session = _uiState.value.session ?: return
        updateSession(session.copy(speed = speed))
    }

    private fun startPlayback() {
        _uiState.update { it.copy(isPlaying = true) }
        playJob = viewModelScope.launch {
            while (isActive) {
                val session = _uiState.value.session ?: break
                if (session.isComplete) {
                    _uiState.update { it.copy(isPlaying = false) }
                    break
                }
                stepForward()
                delay(_uiState.value.speed.delayMs)
            }
        }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun updateSession(session: SimulationSession) {
        val performance = simulationEngine.computePerformance(session, violations)
        _uiState.update { it.copy(session = session, performance = performance) }
    }
}
