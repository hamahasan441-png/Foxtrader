package com.foxtrader.app.feature.journal.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.usecase.journal.JournalEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Journal screen ViewModel.
 *
 * Observes the Room-backed [JournalRepository] (single source of truth) and
 * derives statistics via the pure [JournalEngine.computeStats]. Edits persist
 * through the repository; the Flow pushes updates back automatically.
 */
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val journalRepository: JournalRepository,
    private val journalEngine: JournalEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    init {
        journalRepository.observeEntries()
            .onEach { entries ->
                _uiState.update {
                    it.copy(
                        entries = entries,
                        stats = journalEngine.computeStats(entries),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch { journalRepository.delete(id) }
    }

    fun updateRating(id: String, rating: Int) {
        viewModelScope.launch {
            _uiState.value.entries.firstOrNull { it.id == id }?.let { entry ->
                journalRepository.upsert(entry.copy(rating = rating))
            }
        }
    }

    fun updateEmotion(id: String, emotion: EmotionTag) {
        viewModelScope.launch {
            _uiState.value.entries.firstOrNull { it.id == id }?.let { entry ->
                journalRepository.upsert(entry.copy(emotionTag = emotion))
            }
        }
    }

    fun toggleStats() {
        _uiState.update { it.copy(showStats = !it.showStats) }
    }
}
