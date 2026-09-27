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
        """Return items the server stored strictly after `since_ms`.

        `since_ms` is a cursor previously returned by this method. It is
        compared with the server's own write stamp, never with the
        client-authored `updated_at`: an edit made offline carries an old
        `updated_at` but is stored late, and must still reach every device
        that pulled in between.

        Returns (envelopes in storage order, cursor for the next pull).
        """
        return self._store.pull_items(user_id, since_ms, types)
