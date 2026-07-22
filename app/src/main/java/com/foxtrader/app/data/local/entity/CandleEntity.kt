package com.foxtrader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity for a cached candle.
 * Composite primary key (symbol, timeframe, timestamp) makes upserts idempotent
 * and prevents duplicate bars. Indexed for fast range queries.
 */
@Entity(
    tableName = "candles",
    primaryKeys = ["symbol", "timeframe", "timestamp"],
    indices = [Index(value = ["symbol", "timeframe", "timestamp"])],
)
data class CandleEntity(
    val symbol: String,
    val timeframe: String,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)
