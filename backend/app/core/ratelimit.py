"""Fixed-window rate limiter (pure, thread-safe, in-process).

A simple, dependency-free fixed-window limiter keyed by an arbitrary string
(client IP, user id, route group). Used to throttle auth endpoints (brute-force
protection) and sync push. A production multi-worker deploy should replace this
with a shared store (Redis) — the interface is intentionally tiny so that swap
is trivial.
"""

from __future__ import annotations

import threading
import time

#: Once this many keys are tracked, expired windows are swept on the next hit.
_PRUNE_THRESHOLD = 1024


class RateLimiter:
    """Fixed-window counter: allows `max_requests` hits per `window_seconds`."""

    def __init__(self, max_requests: int, window_seconds: int) -> None:
        if max_requests <= 0 or window_seconds <= 0:
            raise ValueError("max_requests and window_seconds must be positive")
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._counts: dict[str, tuple[int, float]] = {}  # key -> (count, window_start)
        self._lock = threading.RLock()
        self._next_prune_size = _PRUNE_THRESHOLD

    def allow(self, key: str) -> bool:
        """Record a hit for `key` and return whether it is within the budget."""
        now = time.monotonic()
        with self._lock:
            count, window_start = self._counts.get(key, (0, now))
            if now - window_start >= self.window_seconds:
                count, window_start = 0, now
            count += 1
            self._counts[key] = (count, window_start)
            if len(self._counts) >= self._next_prune_size:
                self._prune(now)
            return count <= self.max_requests

    def _prune(self, now: float) -> None:
        """Drop keys whose window has expired.

        Keys are client IPs or user ids, so without eviction every distinct
        client ever seen stayed in memory for the life of the process — an
        unbounded growth any caller rotating source addresses could drive. An
        expired entry carries no information: `allow` would reset it anyway.
        The next sweep is scheduled at twice the surviving size so a large
        live population is not rescanned on every request.
        """
        expired = [
            k for k, (_, start) in self._counts.items() if now - start >= self.window_seconds
        ]
        for k in expired:
            del self._counts[k]
        self._next_prune_size = max(_PRUNE_THRESHOLD, len(self._counts) * 2)

    def remaining(self, key: str) -> int:
        """How many more hits `key` may make in the current window."""
        now = time.monotonic()
        with self._lock:
            count, window_start = self._counts.get(key, (0, now))
            if now - window_start >= self.window_seconds:
                return self.max_requests
            return max(0, self.max_requests - count)

    def reset(self) -> None:
        with self._lock:
            self._counts.clear()
            self._next_prune_size = _PRUNE_THRESHOLD

    def tracked_keys(self) -> int:
        """Number of keys currently held (for tests and diagnostics)."""
        with self._lock:
            return len(self._counts)
