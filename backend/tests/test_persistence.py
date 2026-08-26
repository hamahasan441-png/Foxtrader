"""Tests for the durable persistence backends (SQLite) and the memory fallback.

Focus: durability across "restart" — create a store against a temp DB file,
write users/tokens/sync items, then construct a *fresh* store on the same file
and assert the data survives. This proves the auth/sync state is no longer
ephemeral when FOX_STORE=sqlite.
"""


import pytest
from app.core.auth import AuthService, DuplicateEmailError
from app.core.persistence import SqliteStore, build_stores
from app.core.sync_store import SyncStore


@pytest.fixture()
def db_path(tmp_path) -> str:
    return str(tmp_path / "foxtrader.db")


def _envelope(uid, type_, data, updated_at):
    return {
        "id": uid,
        "type": type_,
        "data": data,
        "version": 1,
        "updated_at": updated_at,
        "device_id": "dev-1",
        "deleted": False,
    }


def test_sqlite_auth_survives_restart(db_path):
    first = AuthService(SqliteStore(db_path))
    user = first.register("a@b.c", "pw", "A")
    tokens = first.issue_tokens(user.id)
    access = tokens["access_token"]

    # Simulate a process restart: a brand-new service on the same file.
    second = AuthService(SqliteStore(db_path))
    assert second.login("a@b.c", "pw").id == user.id
    assert second.authenticate_access(access) is not None
    # Duplicate registration is still rejected after restart.
    with pytest.raises(DuplicateEmailError):
        second.register("a@b.c", "pw", "A")


def test_sqlite_sync_survives_restart(db_path):
    first = SyncStore(SqliteStore(db_path))
    first.push("user-1", [_envelope("j1", "JOURNAL", '{"x":1}', 100)])

    second = SyncStore(SqliteStore(db_path))
    items, _ts = second.pull("user-1", since_ms=0)
    assert len(items) == 1
    assert items[0]["id"] == "j1"
    assert items[0]["data"] == '{"x":1}'


def test_sqlite_pull_windows_and_last_write_wins(db_path):
    store = SyncStore(SqliteStore(db_path))
    store.push("user-1", [_envelope("a", "JOURNAL", "older", 100)])
    store.push("user-1", [_envelope("a", "JOURNAL", "newer", 200)])
    store.push("user-1", [_envelope("b", "DRAWINGS", "{}", 300)])

    items, _ = store.pull("user-1", since_ms=100)
    assert [i["id"] for i in items] == ["a", "b"]
    assert items[0]["data"] == "newer"  # last-write-wins

    only_journal, _ts = store.pull("user-1", since_ms=0, types={"JOURNAL"})
    assert [i["id"] for i in only_journal] == ["a"]


def test_build_stores_unknown_backend_raises():
    with pytest.raises(ValueError):
        build_stores("postgres", None)


def test_memory_store_does_not_persist_across_instances():
    # Sanity check that the memory backend really is ephemeral.
    store = build_stores("memory", None)[0]
    auth = AuthService(store)
    user = auth.register("a@b.c", "pw", "A")
    tokens = auth.issue_tokens(user.id)
    assert auth.authenticate_access(tokens["access_token"]) is not None

    # A brand-new memory store has nothing.
    fresh = AuthService(build_stores("memory", None)[0])
    assert fresh.authenticate_access(tokens["access_token"]) is None


def test_create_app_rejects_unknown_store_backend(monkeypatch):
    import pytest as _pytest

    _pytest.importorskip("fastapi")
    from app.api import create_app
    from app.config import Settings

    with _pytest.raises(ValueError):
        create_app(Settings(provider="sample", store_backend="postgres"))


def test_sqlite_does_not_persist_raw_bearer_tokens(tmp_path):
    """Opaque client tokens are never stored verbatim in the durable DB."""
    import sqlite3

    db_path = str(tmp_path / "token-hash.db")
    service = AuthService(SqliteStore(db_path))
    user = service.register("tokenhash@example.com", "Password123!", "Token Hash")
    tokens = service.issue_tokens(user.id)

    with sqlite3.connect(db_path) as conn:
        access_keys = {row[0] for row in conn.execute("SELECT access_token FROM access_tokens")}
        refresh_keys = {row[0] for row in conn.execute("SELECT refresh_token FROM refresh_tokens")}

    assert tokens["access_token"] not in access_keys
    assert tokens["refresh_token"] not in refresh_keys
    assert len(next(iter(access_keys))) == 64
    assert len(next(iter(refresh_keys))) == 64
    assert service.authenticate_access(tokens["access_token"]).id == user.id


def test_sqlite_refresh_token_is_consumed_atomically(tmp_path):
    """Two concurrent rotations of one refresh token cannot both succeed."""
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    from app.core.auth import InvalidTokenError

    db_path = str(tmp_path / "refresh-race.db")
    service = AuthService(SqliteStore(db_path))
    user = service.register("race@example.com", "Password123!", "Race")
    tokens = service.issue_tokens(user.id)
    refresh = tokens["refresh_token"]
    barrier = Barrier(2)

    def rotate_once() -> str:
        barrier.wait()
        try:
            service.refresh(refresh)
            return "ok"
        except InvalidTokenError:
            return "rejected"

    # Submit both before waiting so neither call can consume the token before
    # the other reaches the barrier.
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(rotate_once) for _ in range(2)]
        outcomes = sorted(f.result() for f in futures)

    assert outcomes == ["ok", "rejected"]


def test_sqlite_concurrent_registration_returns_one_duplicate(tmp_path):
    """Unique-email races are translated to a domain duplicate, never a DB 500."""
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    from app.core.auth import DuplicateEmailError

    service = AuthService(SqliteStore(str(tmp_path / "register-race.db")))
    barrier = Barrier(2)

    def register_once() -> str:
        barrier.wait()
        try:
            service.register("same@example.com", "Password123!", "Same")
            return "ok"
        except DuplicateEmailError:
            return "duplicate"

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(register_once) for _ in range(2)]
        outcomes = sorted(f.result() for f in futures)

    assert outcomes == ["duplicate", "ok"]
