"""Cloud-sync store: last-write-wins merge + pull window.

Framework-free (no FastAPI/pydantic) so it can be unit-tested offline. The store
keeps per-user sync items keyed by (id, type) and merges pushes with
last-write-wins on `updated_at`, which matches the client's `CloudSyncEngine`
merge model (union + last-write-wins).

Persistence is delegated to the [SyncStore] protocol in `app.core.persistence`:
the default SQLite backend is durable across restarts; the in-memory backend is
used for tests and stateless deployments.
"""

from __future__ import annotations

import time

from app.core.persistence import MemoryStore
from app.core.persistence import SyncStore as PersistStore


class SyncStore:
    """Per-user last-write-wins item store."""

    def __init__(self, store: PersistStore | None = None) -> None:
        self._store: PersistStore = store or MemoryStore()

    def push(self, user_id: str, items: list[dict]) -> None:
        """Merge a push batch, keeping the newest version per (id, type)."""
        self._store.upsert_items(user_id, items)

    def pull(
        self,
        user_id: str,
        since_ms: int,
        types: set[str] | None = None,
    ) -> tuple[list[dict], int]:
        """Return items updated strictly after `since_ms` (optional type filter).

        Returns (envelopes sorted by updated_at, server_timestamp_ms).
        """
        items = self._store.pull_items(user_id, since_ms, types)
        return items, int(time.time() * 1000)
