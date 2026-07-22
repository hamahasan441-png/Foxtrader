package com.foxtrader.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.local.entity.CandleEntity

/**
 * The FoxTrader local database (Room).
 * Single source of truth for market data cache, trades, journal (future).
 */
@Database(
    entities = [CandleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FoxDatabase : RoomDatabase() {
    abstract fun candleDao(): CandleDao

    companion object {
        const val NAME = "foxtrader.db"
    }
}
