package com.foxtrader.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.local.dao.JournalDao
import com.foxtrader.app.data.local.entity.CandleEntity
import com.foxtrader.app.data.local.entity.JournalEntity

/**
 * The FoxTrader local database (Room).
 * Single source of truth for the market data cache and user-authored data
 * (trade journal; drawings/settings to follow).
 *
 * Version history:
 * - v1: candles table only.
 * - v2: adds journal_entries table (user-authored, syncable).
 */
@Database(
    entities = [CandleEntity::class, JournalEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class FoxDatabase : RoomDatabase() {
    abstract fun candleDao(): CandleDao
    abstract fun journalDao(): JournalDao

    companion object {
        const val NAME = "foxtrader.db"

        /**
         * v1 -> v2: create the journal_entries table.
         * A real (non-destructive) migration because journal entries are
         * user-authored data that must survive schema upgrades.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        symbol TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        timeframe TEXT NOT NULL,
                        entryPrice REAL NOT NULL,
                        exitPrice REAL,
                        stopLoss REAL NOT NULL,
                        takeProfit REAL NOT NULL,
                        volume REAL NOT NULL,
                        entryTime INTEGER NOT NULL,
                        exitTime INTEGER,
                        pnl REAL,
                        rMultiple REAL,
                        setupType TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        rating INTEGER NOT NULL,
                        emotionTag TEXT NOT NULL,
                        screenshot TEXT,
                        tags TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
