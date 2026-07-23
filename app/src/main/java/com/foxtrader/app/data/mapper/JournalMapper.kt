package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.JournalEntity
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe

// ============================================================================
// JOURNAL MAPPERS — JournalEntry (domain) <-> JournalEntity (Room).
// Enums are stored as their .name; tags as a newline-delimited string.
// Pure functions — the only place this conversion happens.
// ============================================================================

private const val TAG_DELIMITER = "\n"

fun JournalEntity.toDomain(): JournalEntry = JournalEntry(
    id = id,
    symbol = symbol,
    direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.BULLISH),
    timeframe = Timeframe.entries.firstOrNull { it.name == timeframe } ?: Timeframe.M15,
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
    screenshot = screenshot,
    tags = if (tags.isBlank()) emptyList() else tags.split(TAG_DELIMITER),
)

/**
 * @param updatedAt sync timestamp; defaults to now so every write bumps it.
 */
fun JournalEntry.toEntity(updatedAt: Long = System.currentTimeMillis()): JournalEntity = JournalEntity(
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
    screenshot = screenshot,
    tags = tags.joinToString(TAG_DELIMITER),
    updatedAt = updatedAt,
)
