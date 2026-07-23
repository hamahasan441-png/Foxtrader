package com.foxtrader.app.feature.journal.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
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
import java.util.UUID
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

    // ========================================================================
    // LOG TRADE FORM
    // ========================================================================

    fun openLogSheet() = _uiState.update { it.copy(showLogSheet = true, form = LogTradeForm()) }

    fun dismissLogSheet() = _uiState.update { it.copy(showLogSheet = false) }

    fun updateForm(transform: (LogTradeForm) -> LogTradeForm) =
        _uiState.update { it.copy(form = transform(it.form)) }

    /**
     * Build a [JournalEntry] from the form and persist it. If an exit price is
     * provided, the trade is logged as closed (PnL + R computed via the engine);
     * otherwise it's an open trade.
     */
    fun submitLogTrade() {
        val form = _uiState.value.form
        if (!form.isValid) return

        val now = System.currentTimeMillis()
        val base = JournalEntry(
            id = UUID.randomUUID().toString(),
            symbol = form.symbol.trim().uppercase(),
            direction = form.direction,
            timeframe = form.timeframe,
            entryPrice = form.entryPrice.toDouble(),
            exitPrice = null,
            stopLoss = form.stopLoss.toDouble(),
            takeProfit = form.takeProfit.toDouble(),
            volume = form.volume.toDouble(),
            entryTime = now,
            exitTime = null,
            pnl = null,
            rMultiple = null,
            setupType = form.setupType.ifBlank { "Manual" },
            notes = form.notes.trim(),
            rating = form.rating,
            emotionTag = form.emotionTag,
        )

        // If an exit price was entered, close the trade immediately (compute PnL/R).
        val exit = form.exitPrice.toDoubleOrNull()
        val entry = if (exit != null) journalEngine.closeTrade(base, exit, now) else base

        viewModelScope.launch {
            journalRepository.upsert(entry)
            _uiState.update { it.copy(showLogSheet = false, form = LogTradeForm()) }
        }
    }
}
