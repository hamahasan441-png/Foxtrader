package com.foxtrader.app.feature.journal.presentation

import androidx.lifecycle.ViewModel
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.usecase.journal.JournalEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Journal screen ViewModel.
 * Provides journal entries and statistics to the UI.
 */
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val journalEngine: JournalEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    init {
        refreshJournal()
    }

    fun refreshJournal() {
        _uiState.value = JournalUiState(
            entries = journalEngine.getAllEntries(),
            stats = journalEngine.getStats(),
        )
    }

    fun deleteEntry(id: String) {
        journalEngine.deleteEntry(id)
        refreshJournal()
    }

    fun updateRating(id: String, rating: Int) {
        journalEngine.updateEntry(id, rating = rating)
        refreshJournal()
    }

    fun updateEmotion(id: String, emotion: EmotionTag) {
        journalEngine.updateEntry(id, emotionTag = emotion)
        refreshJournal()
    }

    fun toggleStats() {
        _uiState.value = _uiState.value.copy(showStats = !_uiState.value.showStats)
    }
}
