package com.foxtrader.app.feature.journal.presentation

import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats

/**
 * Immutable UI state for the Journal screen.
 */
data class JournalUiState(
    val entries: List<JournalEntry> = emptyList(),
    val stats: JournalStats = JournalStats(),
    val showStats: Boolean = true,
    val isLoading: Boolean = false,
) {
    val hasEntries: Boolean get() = entries.isNotEmpty()
    val openTrades: List<JournalEntry> get() = entries.filter { it.isOpen }
    val closedTrades: List<JournalEntry> get() = entries.filter { !it.isOpen }
}
