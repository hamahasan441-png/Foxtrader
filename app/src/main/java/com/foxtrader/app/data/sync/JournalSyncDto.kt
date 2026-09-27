package com.foxtrader.app.data.sync

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
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

/**
 * Rebuild a journal entry received from sync, or null when the payload names a
 * direction or timeframe this build does not know (a newer client's data must
 * not be half-imported). [localScreenshot] is kept: a screenshot path from
 * another device is meaningless here, so the wire format does not carry one.
 */
fun JournalSyncDto.toJournalEntry(localScreenshot: String? = null): JournalEntry? {
    val direction = runCatching { Direction.valueOf(direction) }.getOrNull() ?: return null
    val timeframe = runCatching { Timeframe.valueOf(timeframe) }.getOrNull() ?: return null
    return JournalEntry(
        id = id,
        symbol = symbol,
        direction = direction,
        timeframe = timeframe,
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
        emotionTag = runCatching { EmotionTag.valueOf(emotionTag) }.getOrDefault(EmotionTag.NEUTRAL),
        screenshot = localScreenshot,
        tags = tags,
    )
}
