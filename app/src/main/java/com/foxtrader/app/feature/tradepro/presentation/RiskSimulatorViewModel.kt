package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.tradepro.RiskSimulationInput
import com.foxtrader.app.domain.usecase.tradepro.MonteCarloRiskEngine
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
 * Drives the Monte Carlo risk simulator: edits the input parameters and runs the (CPU-bound) engine
 * off the main thread.
 */
@HiltViewModel
class RiskSimulatorViewModel @Inject constructor(
    private val engine: MonteCarloRiskEngine,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RiskSimulatorUiState())
    val uiState: StateFlow<RiskSimulatorUiState> = _uiState.asStateFlow()

    init {
        run()
    }

    fun updateInput(transform: (RiskSimulationInput) -> RiskSimulationInput) {
        _uiState.update { it.copy(input = transform(it.input)) }
    }

    fun run() {
        viewModelScope.launch {
            val input = _uiState.value.input
            _uiState.update { it.copy(isRunning = true, error = null) }
            val result = withContext(defaultDispatcher) { engine.simulate(input) }
            _uiState.update { it.copy(isRunning = false, result = result) }
        }
    }
}
