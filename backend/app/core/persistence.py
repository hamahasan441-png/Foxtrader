"""Pluggable persistence backends for auth + cloud sync.

FoxTrader's auth accounts, tokens, and synced items live behind two narrow
interfaces — [AuthStore] and [SyncStore] — so the routers and services never
depend on a concrete storage technology. Two backends ship today:

- [SqliteStore]: durable, zero-dependency (Python stdlib `sqlite3`), WAL mode.
- [MemoryStore]: the previous in-memory behaviour, kept for tests and single
  process stateless deployments.

A third-party backend (PostgreSQL/Redis) can be added by implementing the same
two protocols and selecting it in `build_stores`.

THREAD SAFETY: FastAPI runs sync routes on a threadpool, so each method opens a
fresh `sqlite3` connection (guarded by WAL + `check_same_thread=False`). This is
safe under concurrency and keeps state consistent across workers of one process.
"""

from __future__ import annotations

import os
import sqlite3
import threading
from dataclasses import dataclass
from typing import Protocol

_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    device_id     TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS access_tokens (
    access_token TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    expires_at   INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS refresh_tokens (
    refresh_token TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL,
    expires_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_access_user   ON access_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_user  ON refresh_tokens(user_id);
CREATE TABLE IF NOT EXISTS sync_items (
    user_id    TEXT NOT NULL,
    id         TEXT NOT NULL,
    type       TEXT NOT NULL,
    data       TEXT NOT NULL,
    version    INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    device_id  TEXT NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, id, type)
);
CREATE INDEX IF NOT EXISTS idx_sync_user_updated ON sync_items(user_id, updated_at);
"""


@dataclass(frozen=True)
class StoredUser:
    """A persisted user row."""

    id: str
    email: str
    password_hash: str
    password_salt: str
    display_name: str
    created_at: int
    device_id: str = ""


class AuthStore(Protocol):
    """Persistence for user accounts and tokens."""

    def user_by_email(self, email: str) -> StoredUser | None: ...
    def user_by_id(self, user_id: str) -> StoredUser | None: ...
    def save_user(self, user: StoredUser) -> bool: ...
    def access_entry(self, token: str) -> tuple[str, int] | None: ...
    def save_access(self, token: str, user_id: str, expires_at: int) -> None: ...
    def delete_access(self, token: str) -> None: ...
    def refresh_entry(self, token: str) -> tuple[str, int] | None: ...
    def consume_refresh(self, token: str) -> tuple[str, int] | None: ...
    def save_refresh(self, token: str, user_id: str, expires_at: int) -> None: ...
    def delete_refresh(self, token: str) -> None: ...


class SyncStore(Protocol):
    """Persistence for cloud-sync items."""

    def upsert_items(self, user_id: str, items: list[dict]) -> None: ...
    def pull_items(
        self, user_id: str, since_ms: int, types: set[str] | None
    ) -> list[dict]: ...


class MemoryStore(AuthStore, SyncStore):
    """In-memory backend (previous behaviour). Not durable across restarts."""

    def __init__(self) -> None:
        self._users: dict[str, StoredUser] = {}
        self._users_by_email: dict[str, StoredUser] = {}
        self._access: dict[str, tuple[str, int]] = {}
        self._refresh: dict[str, tuple[str, int]] = {}
        self._sync: dict[str, dict[tuple[str, str], dict]] = {}
        self._lock = threading.RLock()

    # -- AuthStore -----------------------------------------------------------
    def user_by_email(self, email: str) -> StoredUser | None:
        return self._users_by_email.get(email)

    def user_by_id(self, user_id: str) -> StoredUser | None:
        return self._users.get(user_id)

    def save_user(self, user: StoredUser) -> bool:
        with self._lock:
            if user.email in self._users_by_email or user.id in self._users:
                return False
            self._users[user.id] = user
            self._users_by_email[user.email] = user
            return True

    def access_entry(self, token: str) -> tuple[str, int] | None:
        with self._lock:
            return self._access.get(token)

    def save_access(self, token: str, user_id: str, expires_at: int) -> None:
        with self._lock:
            self._access[token] = (user_id, expires_at)

    def delete_access(self, token: str) -> None:
        with self._lock:
            self._access.pop(token, None)

    def refresh_entry(self, token: str) -> tuple[str, int] | None:
        with self._lock:
            return self._refresh.get(token)

    def consume_refresh(self, token: str) -> tuple[str, int] | None:
        """Atomically return-and-delete a single-use refresh token."""
        with self._lock:
            return self._refresh.pop(token, None)

    def save_refresh(self, token: str, user_id: str, expires_at: int) -> None:
        with self._lock:
            self._refresh[token] = (user_id, expires_at)

    def delete_refresh(self, token: str) -> None:
        with self._lock:
            self._refresh.pop(token, None)

    # -- SyncStore -----------------------------------------------------------
    def upsert_items(self, user_id: str, items: list[dict]) -> None:
        user_items = self._sync.setdefault(user_id, {})
        for envelope in items:
            key = (envelope["id"], envelope["type"])
            existing = user_items.get(key)
            if existing is None or envelope["updated_at"] >= existing["updated_at"]:
                user_items[key] = envelope

    def pull_items(
        self, user_id: str, since_ms: int, types: set[str] | None
    ) -> list[dict]:
        user_items = self._sync.get(user_id, {})
        matching = [
            item
            for item in user_items.values()
            if item["updated_at"] > since_ms and (types is None or item["type"] in types)
        ]
        matching.sort(key=lambda e: e["updated_at"])
        return matching


class SqliteStore(AuthStore, SyncStore):
    """Durable SQLite backend. WAL mode; connection-per-operation (thread-safe)."""

    def __init__(self, db_path: str) -> None:
        self._path = os.path.abspath(db_path)
        self._lock = threading.RLock()
        with self._connect() as conn:
            conn.executescript(_SCHEMA)

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(
            self._path, check_same_thread=False, timeout=10
        )
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA synchronous=NORMAL;")
        conn.execute("PRAGMA foreign_keys=ON;")
        return conn

    # -- AuthStore -----------------------------------------------------------
    def user_by_email(self, email: str) -> StoredUser | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM users WHERE email = ?", (email,)
            ).fetchone()
        return self._row_to_user(row) if row else None

    def user_by_id(self, user_id: str) -> StoredUser | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM users WHERE id = ?", (user_id,)
            ).fetchone()
        return self._row_to_user(row) if row else None

    def save_user(self, user: StoredUser) -> bool:
        with self._lock, self._connect() as conn:
            try:
                conn.execute(
                    "INSERT INTO users "
                    "(id, email, password_hash, password_salt, display_name, "
                    "created_at, device_id) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        user.id,
                        user.email,
                        user.password_hash,
                        user.password_salt,
                        user.display_name,
                        user.created_at,
                        user.device_id,
                    ),
                )
                return True
            except sqlite3.IntegrityError:
                # Registration uniqueness is enforced by SQLite as the final
                # authority. Returning False lets AuthService translate a race
                # into a deterministic DuplicateEmailError instead of HTTP 500.
                return False

    def access_entry(self, token: str) -> tuple[str, int] | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT user_id, expires_at FROM access_tokens WHERE access_token = ?",
                (token,),
            ).fetchone()
        return (row["user_id"], row["expires_at"]) if row else None

    def save_access(self, token: str, user_id: str, expires_at: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                "INSERT OR REPLACE INTO access_tokens (access_token, user_id, expires_at) "
                "VALUES (?, ?, ?)",
                (token, user_id, expires_at),
            )

    def delete_access(self, token: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("DELETE FROM access_tokens WHERE access_token = ?", (token,))

    def refresh_entry(self, token: str) -> tuple[str, int] | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT user_id, expires_at FROM refresh_tokens WHERE refresh_token = ?",
                (token,),
            ).fetchone()
        return (row["user_id"], row["expires_at"]) if row else None

    def consume_refresh(self, token: str) -> tuple[str, int] | None:
        """Atomically consume a single-use refresh token across DB clients."""
        with self._lock, self._connect() as conn:
            # Acquire the write lock before reading so two workers cannot both
            # observe the same token and rotate it twice.
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT user_id, expires_at FROM refresh_tokens WHERE refresh_token = ?",
                (token,),
            ).fetchone()
            if row is None:
                return None
            conn.execute("DELETE FROM refresh_tokens WHERE refresh_token = ?", (token,))
            return (row["user_id"], row["expires_at"])

    def save_refresh(self, token: str, user_id: str, expires_at: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                "INSERT OR REPLACE INTO refresh_tokens (refresh_token, user_id, expires_at) "
                "VALUES (?, ?, ?)",
                (token, user_id, expires_at),
            )

    def delete_refresh(self, token: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("DELETE FROM refresh_tokens WHERE refresh_token = ?", (token,))

    # -- SyncStore -----------------------------------------------------------
    def upsert_items(self, user_id: str, items: list[dict]) -> None:
        with self._lock, self._connect() as conn:
            for envelope in items:
                conn.execute(
                    "INSERT INTO sync_items "
                    "(user_id, id, type, data, version, updated_at, device_id, deleted) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(user_id, id, type) DO UPDATE SET "
                    "data = excluded.data, version = excluded.version, "
                    "updated_at = excluded.updated_at, device_id = excluded.device_id, "
                    "deleted = excluded.deleted "
                    "WHERE excluded.updated_at >= sync_items.updated_at",
                    (
                        user_id, envelope["id"], envelope["type"], envelope["data"],
                        envelope["version"], envelope["updated_at"],
                        envelope["device_id"], int(envelope.get("deleted", False)),
                    ),
                )

    def pull_items(
        self, user_id: str, since_ms: int, types: set[str] | None
    ) -> list[dict]:
        placeholders = []
        params: list[object] = [user_id, since_ms]
        sql = (
            "SELECT id, type, data, version, updated_at, device_id, deleted "
            "FROM sync_items WHERE user_id = ? AND updated_at > ?"
        )
        if types:
            placeholders = ",".join("?" for _ in types)
            sql += f" AND type IN ({placeholders})"
            params.extend(sorted(types))
        sql += " ORDER BY updated_at ASC"
        with self._lock, self._connect() as conn:
            rows = conn.execute(sql, params).fetchall()
        return [
            {
                "id": row["id"],
                "type": row["type"],
                "data": row["data"],
                "version": row["version"],
                "updated_at": row["updated_at"],
                "device_id": row["device_id"],
                "deleted": bool(row["deleted"]),
            }
            for row in rows
        ]

    @staticmethod
    def _row_to_user(row: sqlite3.Row) -> StoredUser:
        return StoredUser(
            id=row["id"],
            email=row["email"],
            password_hash=row["password_hash"],
            password_salt=row["password_salt"],
            display_name=row["display_name"],
            created_at=row["created_at"],
            device_id=row["device_id"],
        )


def build_stores(backend: str, db_path: str | None) -> tuple[AuthStore, SyncStore]:
    """Build (auth_store, sync_store) from a backend name.

    - "memory": in-memory (stateless, not durable).
    - "sqlite": durable on-disk store at [db_path] (default `./foxtrader.db`).
    Unknown backends raise [ValueError].
    """
    backend = (backend or "memory").strip().lower()
    if backend == "memory":
        store = MemoryStore()
        return store, store
    if backend == "sqlite":
        path = db_path or os.environ.get("FOX_DB_PATH", "foxtrader.db")
        store = SqliteStore(path)
        return store, store
    raise ValueError(f"Unknown store backend '{backend}'. Available: memory, sqlite")
