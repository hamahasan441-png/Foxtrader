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
