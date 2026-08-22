package com.foxtrader.app.domain.usecase.journal

import com.foxtrader.app.domain.model.JournalEntry
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** RFC-4180-style CSV export with spreadsheet-formula injection protection. */
@Singleton
class JournalCsvExporter @Inject constructor() {
    fun export(entries: List<JournalEntry>): String = buildString {
        appendLine(COLUMNS.joinToString(",") { csv(it) })
        entries.sortedByDescending { it.entryTime }.forEach { e ->
            val row = listOf(
                e.id, e.symbol, e.direction.name, e.timeframe.name,
                format(e.entryPrice), e.exitPrice?.let(::format).orEmpty(),
                format(e.stopLoss), format(e.takeProfit), format(e.volume),
                instant(e.entryTime), e.exitTime?.let(::instant).orEmpty(),
                e.pnl?.let(::format).orEmpty(), e.rMultiple?.let(::format).orEmpty(),
                e.setupType, e.notes, e.rating.toString(), e.emotionTag.name,
                e.screenshot.orEmpty(), e.tags.joinToString("|"),
            )
            appendLine(row.joinToString(",") { csv(it) })
        }
    }

    private fun instant(epochMs: Long): String = runCatching { Instant.ofEpochMilli(epochMs).toString() }.getOrDefault("")
    private fun format(value: Double): String = if (value.isFinite()) String.format(Locale.US, "%.10f", value).trimEnd('0').trimEnd('.') else ""

    private fun csv(raw: String): String {
        // Prevent formula execution when CSV is opened in Excel/Sheets.
        val safe = if (raw.firstOrNull() in FORMULA_PREFIXES) "'$raw" else raw
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    private companion object {
        val FORMULA_PREFIXES = setOf('=', '+', '-', '@')
        val COLUMNS = listOf(
            "id", "symbol", "direction", "timeframe", "entry_price", "exit_price",
            "stop_loss", "take_profit", "volume", "entry_time_utc", "exit_time_utc",
            "pnl", "r_multiple", "setup_type", "notes", "rating", "emotion",
            "screenshot", "tags",
        )
    }
}
