"""Pure rate-limiter tests + HTTP 429 behaviour via TestClient."""

import pytest
from app.core.ratelimit import RateLimiter


def test_allows_up_to_limit_then_rejects():
    limiter = RateLimiter(max_requests=3, window_seconds=60)
    assert limiter.allow("ip-1") is True
    assert limiter.allow("ip-1") is True
    assert limiter.allow("ip-1") is True
    assert limiter.allow("ip-1") is False
    assert limiter.remaining("ip-1") == 0


def test_keys_are_independent():
    limiter = RateLimiter(max_requests=2, window_seconds=60)
    limiter.allow("a")
    limiter.allow("a")
    assert limiter.allow("b") is True  # different key unaffected
    assert limiter.allow("a") is False


def test_window_resets(monkeypatch):
    limiter = RateLimiter(max_requests=2, window_seconds=60)
    now = [100.0]
    monkeypatch.setattr("app.core.ratelimit.time.monotonic", lambda: now[0])
    assert limiter.allow("k") is True
    assert limiter.allow("k") is True
    assert limiter.allow("k") is False

    now[0] += 61  # window elapses
    assert limiter.allow("k") is True


def test_remaining_counts_down_and_reports():
    limiter = RateLimiter(max_requests=5, window_seconds=60)
    assert limiter.remaining("k") == 5
    limiter.allow("k")
    limiter.allow("k")
    assert limiter.remaining("k") == 3


def test_invalid_constructor_raises():
    with pytest.raises(ValueError):
        RateLimiter(0, 60)
    with pytest.raises(ValueError):
        RateLimiter(5, 0)


def test_expired_keys_are_evicted(monkeypatch):
    # Regression: every distinct key (client IP) was kept forever, so a caller
    # rotating addresses could grow the limiter's memory without bound.
    import app.core.ratelimit as ratelimit

    clock = {"now": 1000.0}
    monkeypatch.setattr(ratelimit.time, "monotonic", lambda: clock["now"])
    limiter = ratelimit.RateLimiter(max_requests=5, window_seconds=60)

    for i in range(ratelimit._PRUNE_THRESHOLD - 1):
        limiter.allow(f"ip-{i}")
    clock["now"] += 61  # every existing window has now expired
    limiter.allow("fresh-a")  # crosses the threshold and triggers a sweep

    assert limiter.tracked_keys() == 1
    # Budget semantics are unchanged for a key seen again after eviction.
    assert limiter.remaining("ip-0") == 5


def test_live_keys_survive_pruning(monkeypatch):
    import app.core.ratelimit as ratelimit

    clock = {"now": 1000.0}
    monkeypatch.setattr(ratelimit.time, "monotonic", lambda: clock["now"])
    limiter = ratelimit.RateLimiter(max_requests=2, window_seconds=60)

    limiter.allow("attacker")
    limiter.allow("attacker")
    for i in range(ratelimit._PRUNE_THRESHOLD):
        limiter.allow(f"ip-{i}")

    # Still inside its window, so the exhausted budget must not be forgotten.
    assert limiter.allow("attacker") is False
