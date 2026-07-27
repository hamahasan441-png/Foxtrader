package com.foxtrader.app.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.alerts.AlertDispatcher
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.repository.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Alerts inbox ViewModel.
 *
 * Activates the alert history that Sprint 7 gave the system: the engine,
 * dispatcher, worker and scheduler all existed, but nothing retained or
 * displayed what they produced.
 */
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
    private val alertDispatcher: AlertDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertsUiState())
    val uiState: StateFlow<AlertsUiState> = _uiState.asStateFlow()

    init {
        alertRepository.observeAlerts()
            .onEach { alerts ->
                _uiState.update { it.copy(alerts = alerts.toPersistentList(), isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    fun setPriorityFilter(priority: AlertPriority?) =
        _uiState.update { it.copy(priorityFilter = priority) }

    fun toggleUnreadOnly() = _uiState.update { it.copy(unreadOnly = !it.unreadOnly) }

    /**
     * Acknowledging also cancels the system notification, so the shade and the
     * inbox cannot disagree about what the user has already dealt with.
     */
    fun acknowledge(id: String) {
        viewModelScope.launch {
            alertRepository.acknowledge(id)
            alertDispatcher.cancel(id)
        }
    }

    fun acknowledgeAll() {
        viewModelScope.launch {
            alertRepository.acknowledgeAll()
            alertDispatcher.cancelAll()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            alertRepository.delete(id)
            alertDispatcher.cancel(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            alertRepository.clear()
            alertDispatcher.cancelAll()
        }
    }
}
