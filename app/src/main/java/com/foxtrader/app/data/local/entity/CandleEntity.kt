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
    /**
     * Provenance ([com.foxtrader.app.domain.model.CandleSource] name).
     *
     * Stored per row rather than per series because a single symbol/timeframe
     * cache can legitimately mix synthetic seed bars with real bars that
     * arrived later: the refresh path upserts over the seed by timestamp, and
     * only the rows it actually overwrites become real.
     *
     * Legacy rows (schema < 4) cannot be classified — before v4 synthetic and
     * real bars were written indistinguishably — so MIGRATION_3_4 clears the
     * cache rather than guessing. See FoxDatabase.MIGRATION_3_4.
     */
    val source: String = "LIVE",
)
