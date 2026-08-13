"""Pure, offline tests for the cloud-sync store (no FastAPI required)."""

from app.core.sync_store import SyncStore


def _envelope(uid, type_, data, updated_at, version=1, deleted=False):
    return {
        "id": uid,
        "type": type_,
        "data": data,
        "version": version,
        "updated_at": updated_at,
        "device_id": "dev-1",
        "deleted": deleted,
    }


def test_push_then_pull_echoes_items_per_user():
    store = SyncStore()
    store.push("user-1", [_envelope("j1", "JOURNAL", "{}", 100)])
    store.push("user-2", [_envelope("d1", "DRAWINGS", "{}", 100)])

    items, _ts = store.pull("user-1", since_ms=0)
    assert len(items) == 1
    assert items[0]["id"] == "j1"
    assert items[0]["type"] == "JOURNAL"

    # user-2's data is isolated.
    items2, _ = store.pull("user-2", since_ms=0)
    assert len(items2) == 1
    assert items2[0]["type"] == "DRAWINGS"


def test_last_write_wins_on_conflict():
    store = SyncStore()
    store.push("user-1", [_envelope("j1", "JOURNAL", "older", 100)])
    store.push("user-1", [_envelope("j1", "JOURNAL", "newer", 200)])

    items, _ = store.pull("user-1", since_ms=0)
    assert len(items) == 1
    assert items[0]["data"] == "newer"


def test_stale_write_does_not_overwrite_newer():
    store = SyncStore()
    store.push("user-1", [_envelope("j1", "JOURNAL", "newer", 200)])
    store.push("user-1", [_envelope("j1", "JOURNAL", "older", 100)])

    items, _ = store.pull("user-1", since_ms=0)
    assert items[0]["data"] == "newer"


def test_pull_respects_since_window_and_type_filter():
    store = SyncStore()
    store.push("user-1", [_envelope("a", "JOURNAL", "{}", 100)])
    store.push("user-1", [_envelope("b", "DRAWINGS", "{}", 200)])

    # Only items strictly newer than since are returned.
    items, _ = store.pull("user-1", since_ms=100)
    assert [i["id"] for i in items] == ["b"]

    # Type filter narrows the result.
    items, _ = store.pull("user-1", since_ms=0, types={"JOURNAL"})
    assert [i["id"] for i in items] == ["a"]
