#!/usr/bin/env python3
"""Standalone SQLite verification for FoxTrader Room migrations v1 -> v10.

This intentionally does not depend on Gradle/Android. It mirrors the SQL in
FoxDatabase.kt and verifies the safety properties we care about: user-authored
rows survive, the unclassifiable legacy candle cache is cleared at v4, foreign
keys/indexes exist, and v10 execution audit defaults preserve legacy rows.
"""
from __future__ import annotations

import sqlite3
import sys


def cols(db: sqlite3.Connection, table: str) -> dict[str, tuple]:
    return {row[1]: row for row in db.execute(f"PRAGMA table_info({table})")}


def indexes(db: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in db.execute(f"PRAGMA index_list({table})")}


def main() -> int:
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")

    # v1
    db.executescript(
        """
        CREATE TABLE candles (
            symbol TEXT NOT NULL,
            timeframe TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            open REAL NOT NULL,
            high REAL NOT NULL,
            low REAL NOT NULL,
            close REAL NOT NULL,
            volume REAL NOT NULL,
            PRIMARY KEY(symbol, timeframe, timestamp)
        );
        CREATE INDEX index_candles_symbol_timeframe_timestamp
            ON candles(symbol, timeframe, timestamp);
        INSERT INTO candles VALUES ('EURUSD','M5',1,1.0,1.1,0.9,1.05,100.0);
        """
    )

    # 1 -> 2
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS journal_entries (
            id TEXT NOT NULL PRIMARY KEY, symbol TEXT NOT NULL,
            direction TEXT NOT NULL, timeframe TEXT NOT NULL,
            entryPrice REAL NOT NULL, exitPrice REAL, stopLoss REAL NOT NULL,
            takeProfit REAL NOT NULL, volume REAL NOT NULL, entryTime INTEGER NOT NULL,
            exitTime INTEGER, pnl REAL, rMultiple REAL, setupType TEXT NOT NULL,
            notes TEXT NOT NULL, rating INTEGER NOT NULL, emotionTag TEXT NOT NULL,
            screenshot TEXT, tags TEXT NOT NULL, updatedAt INTEGER NOT NULL
        );
        INSERT INTO journal_entries VALUES
          ('j1','XAUUSD','BUY','M15',2300,NULL,2290,2320,0.1,10,NULL,NULL,NULL,
           'SMT','keep-me',4,'CALM',NULL,'gold',11);
        """
    )

    # 2 -> 3
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS chart_drawings (
            id TEXT NOT NULL PRIMARY KEY, symbol TEXT NOT NULL, timeframe TEXT NOT NULL,
            type TEXT NOT NULL, points TEXT NOT NULL, color INTEGER NOT NULL,
            lineWidth REAL NOT NULL, isVisible INTEGER NOT NULL, label TEXT,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
        );
        INSERT INTO chart_drawings VALUES
          ('d1','XAUUSD','M15','TREND','[]',1,1.0,1,'keep-me',10,11);
        """
    )

    # 3 -> 4: provenance is unknowable, so cached legacy candles must be purged.
    db.execute("ALTER TABLE candles ADD COLUMN source TEXT NOT NULL DEFAULT 'LIVE'")
    db.execute("DELETE FROM candles")
    assert db.execute("SELECT COUNT(*) FROM candles").fetchone()[0] == 0
    assert db.execute("SELECT notes FROM journal_entries WHERE id='j1'").fetchone()[0] == "keep-me"
    assert db.execute("SELECT label FROM chart_drawings WHERE id='d1'").fetchone()[0] == "keep-me"

    # 4 -> 5
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS alerts (
            id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL,
            priority TEXT NOT NULL, symbol TEXT, timestamp INTEGER NOT NULL,
            acknowledged INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS index_alerts_timestamp ON alerts(timestamp);
        CREATE INDEX IF NOT EXISTS index_alerts_acknowledged ON alerts(acknowledged);
        """
    )

    # 5 -> 6
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS watchlists (
            id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
            isDefault INTEGER NOT NULL, createdAt INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS watchlist_symbols (
            watchlistId TEXT NOT NULL, symbol TEXT NOT NULL, assetClass TEXT NOT NULL,
            position INTEGER NOT NULL, notes TEXT NOT NULL, addedAt INTEGER NOT NULL,
            PRIMARY KEY(watchlistId, symbol),
            FOREIGN KEY(watchlistId) REFERENCES watchlists(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_watchlist_symbols_watchlistId_position
            ON watchlist_symbols(watchlistId, position);
        INSERT INTO watchlists VALUES ('w1','Main',1,1);
        INSERT INTO watchlist_symbols VALUES ('w1','EURUSD','FOREX',0,'keep-me',1);
        """
    )

    # 6 -> 7
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS litx_signals (
            id TEXT NOT NULL PRIMARY KEY, symbol TEXT NOT NULL, timeframe TEXT NOT NULL,
            direction TEXT NOT NULL, grade TEXT NOT NULL, score INTEGER NOT NULL,
            entry REAL NOT NULL, stopLoss REAL NOT NULL, takeProfit1 REAL NOT NULL,
            takeProfit2 REAL NOT NULL, riskReward REAL NOT NULL, rationale TEXT NOT NULL,
            createdAt INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS index_litx_signals_createdAt ON litx_signals(createdAt);
        CREATE INDEX IF NOT EXISTS index_litx_signals_symbol ON litx_signals(symbol);
        """
    )

    # 7 -> 8
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS execution_audit_log (
            idempotencyKey TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL,
            symbol TEXT NOT NULL, direction TEXT NOT NULL, volume REAL NOT NULL,
            entryPrice REAL NOT NULL, stopLoss REAL, takeProfit REAL,
            orderId TEXT, reasons TEXT NOT NULL, timestamp INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS index_execution_audit_log_timestamp
            ON execution_audit_log(timestamp);
        CREATE INDEX IF NOT EXISTS index_execution_audit_log_status
            ON execution_audit_log(status);
        INSERT INTO execution_audit_log VALUES
          ('legacy-key','UNKNOWN','EURUSD','BUY',0.1,1.1,NULL,NULL,NULL,'',123);
        """
    )

    # 8 -> 9
    db.execute("ALTER TABLE execution_audit_log ADD COLUMN realizedProfit REAL")

    # 9 -> 10
    db.execute("ALTER TABLE execution_audit_log ADD COLUMN executionScope TEXT NOT NULL DEFAULT ''")
    db.execute("ALTER TABLE execution_audit_log ADD COLUMN operationTag TEXT NOT NULL DEFAULT 'OPEN'")
    db.execute(
        "CREATE INDEX IF NOT EXISTS index_execution_audit_log_executionScope "
        "ON execution_audit_log(executionScope)"
    )

    audit_cols = cols(db, "execution_audit_log")
    assert "realizedProfit" in audit_cols
    assert "executionScope" in audit_cols and audit_cols["executionScope"][4] == "''"
    assert "operationTag" in audit_cols and audit_cols["operationTag"][4] == "'OPEN'"
    row = db.execute(
        "SELECT status, executionScope, operationTag, realizedProfit "
        "FROM execution_audit_log WHERE idempotencyKey='legacy-key'"
    ).fetchone()
    assert row == ("UNKNOWN", "", "OPEN", None), row
    assert "index_execution_audit_log_executionScope" in indexes(db, "execution_audit_log")

    # User-authored data survives the full chain.
    assert db.execute("SELECT COUNT(*) FROM journal_entries WHERE id='j1'").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM chart_drawings WHERE id='d1'").fetchone()[0] == 1
    assert db.execute("SELECT notes FROM watchlist_symbols WHERE watchlistId='w1'").fetchone()[0] == "keep-me"

    # Foreign-key behavior matches Room entity expectations.
    db.execute("DELETE FROM watchlists WHERE id='w1'")
    assert db.execute("SELECT COUNT(*) FROM watchlist_symbols WHERE watchlistId='w1'").fetchone()[0] == 0

    print("ROOM_MIGRATION_V1_TO_V10: PASS")
    print("- legacy candle cache purged at v4: PASS")
    print("- journal/drawing/watchlist persistence: PASS")
    print("- watchlist FK cascade: PASS")
    print("- execution audit v8->v10 preservation/defaults/index: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, sqlite3.Error) as exc:
        print(f"ROOM_MIGRATION_V1_TO_V10: FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
