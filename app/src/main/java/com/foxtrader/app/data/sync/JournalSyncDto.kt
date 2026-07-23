package com.foxtrader.app.data.sync

import com.foxtrader.app.domain.model.JournalEntry
import kotlinx.serialization.Serializable

/**
 * Serializable wire representation of a journal entry for cloud sync.
 *
 * Kept separate from the domain [JournalEntry] so the domain model stays free
 * of serialization concerns. Enums are stored as their String name.
 */
@Serializable
data class JournalSyncDto(
    val id: String,
    val symbol: String,
    val direction: String,
    val timeframe: String,
    val entryPrice: Double,
    val exitPrice: Double? = null,
    val stopLoss: Double,
    val takeProfit: Double,
    val volume: Double,
    val entryTime: Long,
    val exitTime: Long? = null,
    val pnl: Double? = null,
    val rMultiple: Double? = null,
    val setupType: String,
    val notes: String = "",
    val rating: Int = 0,
    val emotionTag: String,
    val tags: List<String> = emptyList(),
)

fun JournalEntry.toSyncDto(): JournalSyncDto = JournalSyncDto(
    id = id,
    symbol = symbol,
    direction = direction.name,
    timeframe = timeframe.name,
    entryPrice = entryPrice,
    exitPrice = exitPrice,
    stopLoss = stopLoss,
    takeProfit = takeProfit,
    volume = volume,
    entryTime = entryTime,
    exitTime = exitTime,
    pnl = pnl,
    rMultiple = rMultiple,
    setupType = setupType,
    notes = notes,
    rating = rating,
    emotionTag = emotionTag.name,
    tags = tags,
)
