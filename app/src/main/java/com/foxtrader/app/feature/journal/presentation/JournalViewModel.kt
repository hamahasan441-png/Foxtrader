package com.foxtrader.app.feature.journal.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.usecase.journal.JournalAnalytics
import com.foxtrader.app.domain.usecase.journal.JournalCsvExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class JournalViewModel @Inject constructor(
    journalRepository: JournalRepository,
    private val analytics: JournalAnalytics,
    private val csvExporter: JournalCsvExporter,
) : ViewModel() {
    val state: StateFlow<JournalUiState> = journalRepository.observeEntries()
        .map { entries ->
            JournalUiState(
                entries = entries.sortedByDescending { it.entryTime },
                stats = analytics.calculate(entries),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalUiState())

    fun csvSnapshot(): String = csvExporter.export(state.value.entries)
}

data class JournalUiState(
    val entries: List<JournalEntry> = emptyList(),
    val stats: JournalStats = JournalStats(),
)
