"""Pure cloud-sync store: last-write-wins merge + pull window.

Framework-free (no FastAPI/pydantic) so it can be unit-tested offline. The store
keeps per-user sync items keyed by (id, type) and merges pushes with
last-write-wins on `updated_at`, which matches the client's `CloudSyncEngine`
merge model (union + last-write-wins).

State is in-memory and **not** durable across process restarts — persistence
(PostgreSQL/Redis) is the documented roadmap item.
"""

from __future__ import annotations

import time
from dataclasses import dataclass


@dataclass
class StoredItem:
    """A single sync envelope stored server-side."""

    id: str
    type: str
    data: str
    version: int
    updated_at: int
    device_id: str
    deleted: bool = False

    @classmethod
    def from_envelope(cls, envelope: dict) -> StoredItem:
        """Build from a snake_case envelope dict (matches the client model)."""
        return cls(
            id=envelope["id"],
            type=envelope["type"],
            data=envelope["data"],
            version=envelope["version"],
            updated_at=envelope["updated_at"],
            device_id=envelope["device_id"],
            deleted=envelope.get("deleted", False),
        )

    def to_envelope(self) -> dict:
        return {
            "id": self.id,
            "type": self.type,
            "data": self.data,
            "version": self.version,
            "updated_at": self.updated_at,
            "device_id": self.device_id,
            "deleted": self.deleted,
        }


class SyncStore:
    """Per-user last-write-wins item store."""

    def __init__(self) -> None:
        # user_id -> {(id, type): StoredItem}
        self._items: dict[str, dict[tuple[str, str], StoredItem]] = {}

    def push(self, user_id: str, items: list[dict]) -> None:
        """Merge a push batch, keeping the newest version per (id, type)."""
        user_items = self._items.setdefault(user_id, {})
        for envelope in items:
            item = StoredItem.from_envelope(envelope)
            existing = user_items.get((item.id, item.type))
            # Last-write-wins on the client-reported update time.
            if existing is None or item.updated_at >= existing.updated_at:
                user_items[(item.id, item.type)] = item

    def pull(
        self,
        user_id: str,
        since_ms: int,
        types: set[str] | None = None,
    ) -> tuple[list[dict], int]:
        """Return items updated strictly after `since_ms` (optional type filter).

        Returns (envelopes sorted by updated_at, server_timestamp_ms).
        """
        user_items = self._items.get(user_id, {})
        matching = [
            item.to_envelope()
            for item in user_items.values()
            if item.updated_at > since_ms and (types is None or item.type in types)
        ]
        matching.sort(key=lambda e: e["updated_at"])
        return matching, int(time.time() * 1000)
