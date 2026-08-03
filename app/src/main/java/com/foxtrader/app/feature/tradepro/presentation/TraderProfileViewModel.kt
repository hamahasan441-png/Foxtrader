package com.foxtrader.app.feature.tradepro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.di.DefaultDispatcher
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.usecase.tradepro.TraderCoachingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Observes the journal and rebuilds the coaching profile whenever entries change, so the analytics
 * stay live as the trader logs new trades. Heavy aggregation runs on the default dispatcher.
 */
@HiltViewModel
class TraderProfileViewModel @Inject constructor(
    private val journalRepository: JournalRepository,
    private val coachingEngine: TraderCoachingEngine,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TraderProfileUiState())
    val uiState: StateFlow<TraderProfileUiState> = _uiState.asStateFlow()

    init {
        journalRepository.observeEntries()
            .onEach { entries -> rebuild(entries) }
            .catch { e -> _uiState.value = TraderProfileUiState(isLoading = false, error = e.message) }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val entries = journalRepository.getAllEntries()
            rebuild(entries)
        }
    }

    private suspend fun rebuild(entries: List<JournalEntry>) {
        val profile = withContext(defaultDispatcher) { coachingEngine.buildProfile(entries) }
        _uiState.value = TraderProfileUiState(isLoading = false, profile = profile, error = null)
    }
}
