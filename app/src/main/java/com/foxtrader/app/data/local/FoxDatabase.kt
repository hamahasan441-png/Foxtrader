package com.foxtrader.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.local.dao.DrawingDao
import com.foxtrader.app.data.local.dao.JournalDao
import com.foxtrader.app.data.local.entity.CandleEntity
import com.foxtrader.app.data.local.entity.DrawingEntity
import com.foxtrader.app.data.local.entity.JournalEntity

/**
 * The FoxTrader local database (Room).
 * Single source of truth for the market data cache and user-authored data
 * (trade journal; drawings; settings to follow).
 *
 * Version history:
 * - v1: candles table only.
 * - v2: adds journal_entries table (user-authored, syncable).
 * - v3: adds chart_drawings table (user-authored, syncable).
 * - v4: adds candles.source (provenance — real vs synthetic bars).
 *
 * `exportSchema = true` writes the schema JSON to app/schemas/, which is what
 * makes MigrationTestHelper possible. Destructive fallback is deliberately NOT
 * enabled: journal entries and drawings are user-authored data, and a missing
 * migration must fail loudly in CI rather than silently wipe them on a user's
 * device.
 */
@Database(
    entities = [CandleEntity::class, JournalEntity::class, DrawingEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class FoxDatabase : RoomDatabase() {
    abstract fun candleDao(): CandleDao
    abstract fun journalDao(): JournalDao
    abstract fun drawingDao(): DrawingDao

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

        /** v2 -> v3: create the chart_drawings table. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chart_drawings (
                        id TEXT NOT NULL PRIMARY KEY,
                        symbol TEXT NOT NULL,
                        timeframe TEXT NOT NULL,
                        type TEXT NOT NULL,
                        points TEXT NOT NULL,
                        color INTEGER NOT NULL,
                        lineWidth REAL NOT NULL,
                        isVisible INTEGER NOT NULL,
                        label TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 -> v4: add `candles.source` provenance.
         *
         * The cached candles are DELETED rather than backfilled. Before v4,
         * synthetic seed bars and real provider bars were written to the same
         * table with nothing to tell them apart, so any backfill value would be
         * a guess — and guessing "LIVE" would launder fabricated prices into
         * the trustworthy set, which is precisely the bug this column exists to
         * fix. Candles are a re-fetchable derived cache (the next refresh
         * repopulates them); journal entries and drawings, which are NOT
         * re-derivable, are untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE candles ADD COLUMN source TEXT NOT NULL DEFAULT 'LIVE'"
                )
                // Unclassifiable legacy rows — drop rather than mislabel.
                db.execSQL("DELETE FROM candles")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
