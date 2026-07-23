package com.foxtrader.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a trade journal entry.
 *
 * Enums (Direction, Timeframe, EmotionTag) are stored as their String name;
 * the tags list is stored as a newline-delimited string. Conversion happens in
 * the mapper (keeps the domain model free of Room concerns and avoids Room
 * TypeConverters).
 *
 * `updatedAt` is added for cloud-sync conflict resolution (last-write-wins).
 */
@Entity(tableName = "journal_entries")
data class JournalEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val direction: String,
    val timeframe: String,
    val entryPrice: Double,
    val exitPrice: Double?,
    val stopLoss: Double,
    val takeProfit: Double,
    val volume: Double,
    val entryTime: Long,
    val exitTime: Long?,
    val pnl: Double?,
    val rMultiple: Double?,
    val setupType: String,
    val notes: String,
    val rating: Int,
    val emotionTag: String,
    val screenshot: String?,
    val tags: String,          // newline-delimited
    val updatedAt: Long,       // for sync conflict resolution
)
