package com.foxtrader.app.feature.journal.presentation

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats
import com.foxtrader.app.domain.model.Timeframe

/**
 * Draft state for the "Log Trade" form.
 * Prices are held as strings so the text fields can be edited freely; they are
 * parsed on submit.
 */
data class LogTradeForm(
    val symbol: String = "",
    val direction: Direction = Direction.BULLISH,
    val timeframe: Timeframe = Timeframe.M15,
    val entryPrice: String = "",
    val stopLoss: String = "",
    val takeProfit: String = "",
    val exitPrice: String = "",          // optional — blank = open trade
    val volume: String = "0.1",
    val setupType: String = "",
    val notes: String = "",
    val rating: Int = 0,
    val emotionTag: EmotionTag = EmotionTag.NEUTRAL,
) {
    /** Minimum validation: symbol + valid entry/stop/target numbers. */
    val isValid: Boolean
        get() = symbol.isNotBlank() &&
            entryPrice.toDoubleOrNull() != null &&
            stopLoss.toDoubleOrNull() != null &&
            takeProfit.toDoubleOrNull() != null &&
            (volume.toDoubleOrNull() ?: 0.0) > 0.0
}

/**
 * Immutable UI state for the Journal screen.
 */
data class JournalUiState(
    val entries: List<JournalEntry> = emptyList(),
    val stats: JournalStats = JournalStats(),
    val showStats: Boolean = true,
    val isLoading: Boolean = false,
    // --- Log Trade form ---
    val showLogSheet: Boolean = false,
    val form: LogTradeForm = LogTradeForm(),
) {
    val hasEntries: Boolean get() = entries.isNotEmpty()
    val openTrades: List<JournalEntry> get() = entries.filter { it.isOpen }
    val closedTrades: List<JournalEntry> get() = entries.filter { !it.isOpen }
}
