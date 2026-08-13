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


class RateLimiter:
    """Fixed-window counter: allows `max_requests` hits per `window_seconds`."""

    def __init__(self, max_requests: int, window_seconds: int) -> None:
        if max_requests <= 0 or window_seconds <= 0:
            raise ValueError("max_requests and window_seconds must be positive")
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._counts: dict[str, tuple[int, int]] = {}  # key -> (count, window_start)
        self._lock = threading.RLock()

    def allow(self, key: str) -> bool:
        """Record a hit for `key` and return whether it is within the budget."""
        now = time.monotonic()
        with self._lock:
            count, window_start = self._counts.get(key, (0, now))
            if now - window_start >= self.window_seconds:
                count, window_start = 0, now
            count += 1
            self._counts[key] = (count, window_start)
            return count <= self.max_requests

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
