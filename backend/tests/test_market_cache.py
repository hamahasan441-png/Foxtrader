"""Test that market-data endpoint caches identical requests within TTL (Task 5)."""

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed")

from app.api import create_app
from app.config import Settings
from app.core.service import clear_candle_cache
from fastapi.testclient import TestClient


class CountingProvider:
    """Tiny provider that counts fetch_candles calls."""

    name = "counting-sample"

    def __init__(self):
        self.call_count = 0
        self.last_args = None

    def fetch_candles(self, symbol, timeframe_minutes, limit, before_ms):
        self.call_count += 1
        self.last_args = (symbol, timeframe_minutes, limit, before_ms)
        # Return minimal valid candle list (matches build_candles_response expectations)
        # build_candles_response expects list of Candle dataclass? Actually our provider
        # base returns list of dicts or dataclass? Let's check sample provider.
        # We'll return empty list — build_candles_response handles any iterable.
        return []


def test_repeated_identical_requests_hit_cache():
    clear_candle_cache()
    provider = CountingProvider()
    settings = Settings(provider="sample", store_backend="memory", rate_limit_enabled=False)
    app = create_app(settings)
    # Override provider with counting one
    app.state.provider = provider
    client = TestClient(app)

    # First request — should hit provider
    r1 = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10})
    assert r1.status_code == 200, r1.text
    assert provider.call_count == 1

    # Second identical request within TTL — should be served from cache, no extra provider call
    r2 = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10})
    assert r2.status_code == 200
    assert provider.call_count == 1, (
        f"Expected cache hit, but provider was called {provider.call_count} times"
    )

    # Different limit — should miss cache and call provider again
    r3 = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 20})
    assert r3.status_code == 200
    assert provider.call_count == 2

    # Different symbol — miss
    r4 = client.get("/api/v1/market/candles/GBPUSD/1H", params={"limit": 10})
    assert r4.status_code == 200
    assert provider.call_count == 3

    # Same as first again — should still be cached (still within TTL)
    r5 = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10})
    assert r5.status_code == 200
    assert provider.call_count == 3


def test_cache_with_before_param_distinct():
    clear_candle_cache()
    provider = CountingProvider()
    settings = Settings(provider="sample", store_backend="memory", rate_limit_enabled=False)
    app = create_app(settings)
    app.state.provider = provider
    client = TestClient(app)

    r1 = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10, "before": 1234567890})
    assert r1.status_code == 200
    assert provider.call_count == 1

    r2 = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10, "before": 1234567890})
    assert r2.status_code == 200
    assert provider.call_count == 1

    r3 = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10, "before": 9999999999})
    assert r3.status_code == 200
    assert provider.call_count == 2
