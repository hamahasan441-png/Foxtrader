package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Mt4ClosedPositionDetails
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.JournalRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles broker-authoritative MT4/MT5 positions with the local trade journal.
 *
 * This is intentionally a synchronization layer, not an execution layer:
 * - it never places/modifies/closes a trade;
 * - account identity is represented only by the already-hashed execution scope;
 * - currently-open broker positions are upserted idempotently;
 * - an open journal entry is closed only when the broker position is absent AND
 *   authoritative history deals provide a close price/time/P&L.
 *
 * User-authored metadata (notes/rating/emotion/screenshot/custom tags) is
 * preserved across broker refreshes.
 */
@Singleton
class BrokerJournalSynchronizer @Inject constructor(
    private val journalRepository: JournalRepository,
) {

    suspend fun synchronize(
        executionScope: String,
        positions: List<Mt4Position>,
        loadCloseDetails: suspend (Long) -> Mt4ClosedPositionDetails?,
    ): BrokerJournalSyncResult {
        require(executionScope.isNotBlank()) { "Execution scope is required" }
        val scopeTag = scopeTag(executionScope)
        val entries = journalRepository.getAllEntries()
        val existingById = entries.associateBy { it.id }
        var openedOrUpdated = 0
        var closed = 0
        var unresolved = 0

        val currentIds = HashSet<String>(positions.size)
        for (position in positions) {
            if (position.ticket <= 0L || position.symbol.isBlank() || !position.lots.isFinite() || position.lots <= 0.0) continue
            val id = journalId(executionScope, position.ticket, position.openTime)
            currentIds += id
            val existing = existingById[id]
            val brokerTags = listOf(BROKER_TAG, scopeTag, ticketTag(position.ticket), AUTO_SYNC_TAG)
            val next = if (existing == null) {
                JournalEntry(
                    id = id,
                    symbol = position.symbol.uppercase(),
                    direction = position.type.toDirection(),
                    // MT terminal position state does not encode the originating
                    // chart timeframe. Keep a deterministic journal default and
                    // say so explicitly instead of inventing one from market data.
                    timeframe = Timeframe.M15,
                    entryPrice = position.openPrice,
                    exitPrice = null,
                    stopLoss = position.sl.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                    takeProfit = position.tp.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                    volume = position.lots,
                    entryTime = position.openTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    exitTime = null,
                    pnl = null,
                    rMultiple = null,
                    setupType = "BROKER MT4/MT5",
                    notes = "Auto-synced from broker. Originating chart timeframe is unavailable from terminal position state; M15 is a journal display default only.",
                    rating = 0,
                    emotionTag = EmotionTag.NEUTRAL,
                    screenshot = null,
                    tags = brokerTags,
                )
            } else if (existing.isOpen) {
                existing.copy(
                    symbol = position.symbol.uppercase(),
                    direction = position.type.toDirection(),
                    entryPrice = position.openPrice.takeIf { it.isFinite() && it > 0.0 } ?: existing.entryPrice,
                    stopLoss = position.sl.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                    takeProfit = position.tp.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                    volume = position.lots,
                    tags = mergeBrokerTags(existing.tags, brokerTags),
                )
            } else {
                // A closed journal row must never be silently reopened. MT ticket
                // reuse is rare but possible after migrations/imports; leave the
                // historical row intact and surface the conflict.
                unresolved++
                continue
            }
            if (existing != next) {
                journalRepository.upsert(next)
                openedOrUpdated++
            }
        }

        // Close only rows owned by this account scope. Rows from other MetaApi
        // accounts and user-authored journal entries are never touched.
        val candidateClosures = entries.filter { entry ->
            entry.isOpen && BROKER_TAG in entry.tags && scopeTag in entry.tags && entry.id !in currentIds
        }
        for (entry in candidateClosures) {
            val ticket = entry.tags.firstNotNullOfOrNull { tag ->
                tag.takeIf { it.startsWith(TICKET_PREFIX) }?.removePrefix(TICKET_PREFIX)?.toLongOrNull()
            }
            if (ticket == null || ticket <= 0L) {
                unresolved++
                continue
            }
            val details = try {
                loadCloseDetails(ticket)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (details == null) {
                // Broker history can lag terminal state. Keep the row open until
                // a later refresh can prove the close; never fabricate an exit.
                unresolved++
                continue
            }
            val closedEntry = entry.copy(
                exitPrice = details.exitPrice,
                exitTime = details.exitTime,
                pnl = details.realizedProfit,
                // Currency P/L cannot be divided by raw price distance without
                // contract-size/FX conversion. Keep null rather than publish a
                // mathematically invalid R-multiple.
                rMultiple = null,
                tags = mergeBrokerTags(entry.tags, listOf(BROKER_CLOSED_TAG)),
                notes = appendSystemNote(entry.notes, "Broker close confirmed from MetaApi history deals."),
            )
            journalRepository.upsert(closedEntry)
            closed++
        }

        return BrokerJournalSyncResult(openedOrUpdated, closed, unresolved)
    }

    private fun Mt4OrderType.toDirection() = when (this) {
        Mt4OrderType.BUY, Mt4OrderType.BUY_LIMIT, Mt4OrderType.BUY_STOP -> com.foxtrader.app.domain.model.Direction.BULLISH
        Mt4OrderType.SELL, Mt4OrderType.SELL_LIMIT, Mt4OrderType.SELL_STOP -> com.foxtrader.app.domain.model.Direction.BEARISH
    }

    private fun mergeBrokerTags(existing: List<String>, required: List<String>): List<String> =
        (existing + required).distinct().take(MAX_TAGS)

    private fun appendSystemNote(notes: String, note: String): String = when {
        notes.contains(note) -> notes
        notes.isBlank() -> note
        else -> "$notes\n$note"
    }

    private fun journalId(scope: String, ticket: Long, openTime: Long): String =
        "broker-${scope.take(SCOPE_ID_CHARS)}-$ticket-${openTime.coerceAtLeast(0L)}"

    private fun scopeTag(scope: String) = "$SCOPE_PREFIX${scope.take(SCOPE_TAG_CHARS)}"
    private fun ticketTag(ticket: Long) = "$TICKET_PREFIX$ticket"

    private companion object {
        const val BROKER_TAG = "broker:metaapi"
        const val BROKER_CLOSED_TAG = "broker:closed"
        const val AUTO_SYNC_TAG = "auto-synced"
        const val SCOPE_PREFIX = "account-scope:"
        const val TICKET_PREFIX = "ticket:"
        const val SCOPE_ID_CHARS = 16
        const val SCOPE_TAG_CHARS = 24
        const val MAX_TAGS = 32
    }
}

data class BrokerJournalSyncResult(
    val openedOrUpdated: Int,
    val closed: Int,
    val unresolved: Int,
) {
    val changed: Int get() = openedOrUpdated + closed
}
