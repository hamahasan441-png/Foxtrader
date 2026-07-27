package com.foxtrader.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for [FoxDatabase].
 *
 * These exist because `fallbackToDestructiveMigration()` was removed in Sprint
 * 6. That removal is only safe if every migration path is proven, so this suite
 * is the safety net that replaced it. It asserts the property that actually
 * matters: **user-authored data (journal entries, drawings) survives an upgrade
 * with its values intact.**
 *
 * ## Why this does not use `MigrationTestHelper`
 *
 * `MigrationTestHelper.createDatabase(name, v)` materialises the old database
 * from `schemas/<v>.json`. Those files only exist for versions exported *after*
 * `exportSchema = true` was enabled (v4 onward) — v1..v3 shipped with schema
 * export disabled, and the JSON cannot be back-filled by hand because Room
 * verifies a computed `identityHash` against it.
 *
 * So legacy versions are materialised here from the exact DDL those versions
 * shipped, and the real [androidx.room.migration.Migration] objects are applied
 * to them. This tests the migration logic itself, which is what can destroy
 * data. The final open through Room additionally proves the post-migration
 * schema is one Room accepts — if a migration produced a shape that disagreed
 * with the entities, `build()` would throw here rather than on a user's device.
 *
 * From v4 onward, exported schemas make full `MigrationTestHelper` validation
 * available for every future migration.
 */
@RunWith(AndroidJUnit4::class)
class FoxDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** The `candles` table exactly as v1 shipped it (no `source` column). */
        const val DDL_CANDLES_V1 = """
            CREATE TABLE IF NOT EXISTS candles (
                symbol TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                open REAL NOT NULL,
                high REAL NOT NULL,
                low REAL NOT NULL,
                close REAL NOT NULL,
                volume REAL NOT NULL,
                PRIMARY KEY(symbol, timeframe, timestamp)
            )
        """

        const val DDL_CANDLES_INDEX_V1 =
            "CREATE INDEX IF NOT EXISTS index_candles_symbol_timeframe_timestamp " +
                "ON candles (symbol, timeframe, timestamp)"

        const val INSERT_JOURNAL = """
            INSERT INTO journal_entries (
                id, symbol, direction, timeframe, entryPrice, exitPrice, stopLoss,
                takeProfit, volume, entryTime, exitTime, pnl, rMultiple, setupType,
                notes, rating, emotionTag, screenshot, tags, updatedAt
            ) VALUES (
                'j1', 'GBPUSD', 'BEARISH', '1H', 1.2500, NULL, 1.2600,
                1.2300, 1.0, 1000, NULL, NULL, NULL, 'CHOCH',
                'runner', 5, 'FOCUSED', NULL, '', 3000
            )
        """

        const val INSERT_DRAWING = """
            INSERT INTO chart_drawings (
                id, symbol, timeframe, type, points, color, lineWidth,
                isVisible, label, createdAt, updatedAt
            ) VALUES (
                'd1', 'GBPUSD', '1H', 'TREND_LINE', '0,1.25,1000;5,1.26,2000',
                4294901760, 2.0, 1, 'resistance', 1000, 2000
            )
        """
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Opens a bare SQLite database at [TEST_DB] with no Room involvement. */
    private fun openRaw(version: Int, onCreate: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    /** Materialises a v3 database (candles + journal + drawings) with user data. */
    private fun createV3WithUserData(): SupportSQLiteDatabase = openRaw(3) { db ->
        db.execSQL(DDL_CANDLES_V1)
        db.execSQL(DDL_CANDLES_INDEX_V1)
        // v2 and v3 tables are created by the real migrations under test.
        FoxDatabase.MIGRATION_1_2.migrate(db)
        FoxDatabase.MIGRATION_2_3.migrate(db)
    }

    private fun SupportSQLiteDatabase.countOf(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { c ->
            assertTrue(c.moveToFirst()); c.getInt(0)
        }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun migration1To2_createsJournalTable_andPreservesCandles() {
        val db = openRaw(1) { d ->
            d.execSQL(DDL_CANDLES_V1)
            d.execSQL(DDL_CANDLES_INDEX_V1)
        }
        db.execSQL(
            "INSERT INTO candles (symbol, timeframe, timestamp, open, high, low, close, volume) " +
                "VALUES ('EURUSD', '15m', 1000, 1.1, 1.2, 1.0, 1.15, 500.0)"
        )

        FoxDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(0, db.countOf("journal_entries"))
        assertEquals("candles must survive 1->2", 1, db.countOf("candles"))
        db.close()
    }

    @Test
    fun migration2To3_createsDrawingsTable_andPreservesJournalValues() {
        val db = openRaw(2) { d ->
            d.execSQL(DDL_CANDLES_V1)
            d.execSQL(DDL_CANDLES_INDEX_V1)
            FoxDatabase.MIGRATION_1_2.migrate(d)
        }
        db.execSQL(INSERT_JOURNAL)

        FoxDatabase.MIGRATION_2_3.migrate(db)

        assertEquals(0, db.countOf("chart_drawings"))
        db.query("SELECT symbol, notes, setupType FROM journal_entries WHERE id = 'j1'").use { c ->
            assertTrue("journal entry was lost during 2->3", c.moveToFirst())
            assertEquals("GBPUSD", c.getString(0))
            assertEquals("runner", c.getString(1))
            assertEquals("CHOCH", c.getString(2))
        }
        db.close()
    }

    @Test
    fun migration3To4_preservesUserData_andClearsUnclassifiableCandles() {
        val db = createV3WithUserData()
        db.execSQL(
            "INSERT INTO candles (symbol, timeframe, timestamp, open, high, low, close, volume) " +
                "VALUES ('EURUSD', '15m', 1000, 1.1, 1.2, 1.0, 1.15, 500.0)"
        )
        db.execSQL(INSERT_JOURNAL)
        db.execSQL(INSERT_DRAWING)

        FoxDatabase.MIGRATION_3_4.migrate(db)

        // THE contract: user-authored data survives, values intact.
        db.query("SELECT notes, setupType FROM journal_entries WHERE id = 'j1'").use { c ->
            assertTrue("journal entry was lost during 3->4", c.moveToFirst())
            assertEquals("runner", c.getString(0))
            assertEquals("CHOCH", c.getString(1))
        }
        db.query("SELECT label, points FROM chart_drawings WHERE id = 'd1'").use { c ->
            assertTrue("drawing was lost during 3->4", c.moveToFirst())
            assertEquals("resistance", c.getString(0))
            assertEquals("0,1.25,1000;5,1.26,2000", c.getString(1))
        }

        // Unclassifiable legacy candles are dropped, never laundered into LIVE.
        assertEquals(
            "legacy candles must be dropped, not relabelled as real",
            0,
            db.countOf("candles"),
        )

        // The new column exists and defaults to LIVE.
        db.execSQL(
            "INSERT INTO candles (symbol, timeframe, timestamp, open, high, low, close, volume) " +
                "VALUES ('EURUSD', '15m', 5000, 1.1, 1.2, 1.0, 1.15, 500.0)"
        )
        db.query("SELECT source FROM candles WHERE timestamp = 5000").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("LIVE", c.getString(0))
        }
        db.close()
    }

    @Test
    fun migration4To5_addsAlertsTable_andPreservesUserData() {
        val db = createV3WithUserData()
        db.execSQL(INSERT_JOURNAL)
        db.execSQL(INSERT_DRAWING)
        FoxDatabase.MIGRATION_3_4.migrate(db)

        FoxDatabase.MIGRATION_4_5.migrate(db)

        // New table exists and is empty.
        assertEquals(0, db.countOf("alerts"))

        // Purely additive: user-authored data is untouched.
        db.query("SELECT notes FROM journal_entries WHERE id = 'j1'").use { c ->
            assertTrue("journal entry was lost during 4->5", c.moveToFirst())
            assertEquals("runner", c.getString(0))
        }
        db.query("SELECT label FROM chart_drawings WHERE id = 'd1'").use { c ->
            assertTrue("drawing was lost during 4->5", c.moveToFirst())
            assertEquals("resistance", c.getString(0))
        }

        // The table accepts a write with the expected shape.
        db.execSQL(
            "INSERT INTO alerts (id, title, body, priority, symbol, timestamp, acknowledged) " +
                "VALUES ('a1', 'BUY EURUSD', 'body', 'HIGH', 'EURUSD', 1000, 0)"
        )
        db.query("SELECT priority, acknowledged FROM alerts WHERE id = 'a1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("HIGH", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        db.close()
    }

    @Test
    fun fullChain1To5_thenRoomOpensWithoutFallback() {
        val db = openRaw(1) { d ->
            d.execSQL(DDL_CANDLES_V1)
            d.execSQL(DDL_CANDLES_INDEX_V1)
        }
        db.execSQL(
            "INSERT INTO candles (symbol, timeframe, timestamp, open, high, low, close, volume) " +
                "VALUES ('BTCUSDT', '1H', 1000, 60000.0, 61000.0, 59000.0, 60500.0, 12.0)"
        )
        FoxDatabase.MIGRATION_1_2.migrate(db)
        db.execSQL(INSERT_JOURNAL)
        FoxDatabase.MIGRATION_2_3.migrate(db)
        db.execSQL(INSERT_DRAWING)
        FoxDatabase.MIGRATION_3_4.migrate(db)
        FoxDatabase.MIGRATION_4_5.migrate(db)
        db.version = 5
        db.close()

        // Room must accept the migrated file WITHOUT destructive fallback. If a
        // migration produced a schema that disagrees with the entities, this
        // throws — which is exactly the failure we want in CI, not on a device.
        val room = Room.databaseBuilder(context, FoxDatabase::class.java, TEST_DB)
            .addMigrations(*FoxDatabase.MIGRATIONS)
            .build()

        val journal = room.journalDao()

        // And the user's data is still readable through the DAOs. Reading is
        // also what forces Room to actually open and validate the file.
        kotlinx.coroutines.runBlocking {
            val entries = journal.getAll()
            assertEquals(1, entries.size)
            assertEquals("runner", entries.first().notes)

            // The v5 table is queryable through its DAO, which also proves the
            // migrated shape matches AlertEntity.
            room.alertDao().acknowledgeAll()
        }
        assertTrue("Room could not open the migrated database", room.isOpen)
        room.close()
    }
}
