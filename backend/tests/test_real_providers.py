"""Tests for the real REST market-data providers (Twelve Data, Polygon).

HTTP is stubbed via a fake `_get_json` so these run fully offline; they verify
symbol/timeframe mapping, OHLCV normalization, `before_ms` filtering, ascending
order, malformed-row skipping, and missing-key errors.
"""

import time

import pytest
from app.core.providers.polygon import PolygonProvider
from app.core.providers.rest import MissingApiKeyError, ProviderRequestError
from app.core.providers.twelve_data import TwelveDataProvider


class _StubTwelve(TwelveDataProvider):
    def __init__(self, payload):
        super().__init__(api_key="test-key")
        self._payload = payload
        self.last_url = None

    def _get_json(self, url, headers=None):
        self.last_url = url
        return self._payload


class _StubPolygon(PolygonProvider):
    def __init__(self, payload):
        super().__init__(api_key="test-key")
        self._payload = payload
        self.last_url = None

    def _get_json(self, url, headers=None):
        self.last_url = url
        return self._payload


def _td_row(dt, o, h, low, c, v=1000.0):
    return {
        "datetime": dt,
        "open": str(o),
        "high": str(h),
        "low": str(low),
        "close": str(c),
        "volume": str(v),
    }


def test_twelve_data_maps_and_sorts_candles():
    provider = _StubTwelve(
        {"status": "ok", "values": [
            _td_row("2024-01-05 15:00:00", 1.2, 1.3, 1.1, 1.25, 500),
            _td_row("2024-01-05 15:01:00", 1.25, 1.35, 1.2, 1.30, 700),
            _td_row("2024-01-05 15:02:00", 1.30, 1.40, 1.25, 1.35, 900),
        ]}
    )
    candles = provider.fetch_candles("EURUSD", 60, 100, None)
    assert len(candles) == 3
    assert candles[0].open == 1.2
    assert candles[-1].close == 1.35
    assert candles == sorted(candles, key=lambda c: c.timestamp)
    # Interval mapping for 60m -> 1h.
    assert "interval=1h" in provider.last_url
    assert "apikey=test-key" in provider.last_url


def test_twelve_data_honours_before_ms():
    rows = [
        _td_row("2024-01-05 15:00:00", 1, 2, 0.5, 1.5),
        _td_row("2024-01-05 16:00:00", 1.5, 2.5, 1.0, 2.0),
        _td_row("2024-01-05 17:00:00", 2.0, 3.0, 1.5, 2.5),
    ]
    provider = _StubTwelve({"status": "ok", "values": rows})
    # before_ms corresponds to 2024-01-05 16:00:00 in ms.
    parsed = time.strptime("2024-01-05 16:00:00", "%Y-%m-%d %H:%M:%S")
    before = int(time.mktime(parsed) * 1000)
    candles = provider.fetch_candles("EURUSD", 60, 100, before)
    assert all(c.timestamp < before for c in candles)


def test_twelve_data_missing_key_raises():
    provider = TwelveDataProvider(api_key=None, )
    provider._api_key = None
    import os

    if "FOX_TWELVE_DATA_KEY" in os.environ:
        del os.environ["FOX_TWELVE_DATA_KEY"]
    with pytest.raises(MissingApiKeyError):
        provider.fetch_candles("EURUSD", 60, 100, None)


def test_twelve_data_upstream_error_raises():
    provider = _StubTwelve({"status": "error", "message": "invalid symbol"})
    with pytest.raises(ProviderRequestError):
        provider.fetch_candles("NOPE", 60, 100, None)


def test_twelve_data_skips_malformed_rows():
    provider = _StubTwelve(
        {"status": "ok", "values": [
            _td_row("2024-01-05 15:00:00", 1, 2, 0.5, 1.5),
            {"bad": "row"},
            _td_row("2024-01-05 16:00:00", 1.5, 2.5, 1.0, 2.0),
        ]}
    )
    candles = provider.fetch_candles("EURUSD", 60, 100, None)
    assert len(candles) == 2


def test_polygon_maps_ms_timestamps_and_window():
    provider = _StubPolygon(
        {"resultsCount": 2, "results": [
            {"t": 1700000000000, "o": 1.2, "h": 1.3, "l": 1.1, "c": 1.25, "v": 1000},
            {"t": 1700000030000, "o": 1.25, "h": 1.35, "l": 1.2, "c": 1.3, "v": 2000},
        ]}
    )
    candles = provider.fetch_candles("X:BTCUSD", 5, 100, None)
    assert len(candles) == 2
    assert candles[0].timestamp == 1700000000000
    assert candles[0].volume == 1000
    assert "range/5/minute/" in provider.last_url
    assert "apiKey=test-key" in provider.last_url


def test_polygon_missing_key_raises():
    provider = PolygonProvider(api_key=None)
    import os

    if "FOX_POLYGON_KEY" in os.environ:
        del os.environ["FOX_POLYGON_KEY"]
    with pytest.raises(MissingApiKeyError):
        provider.fetch_candles("AAPL", 1440, 100, None)


def test_polygon_limits_to_requested_count():
    results = [
        {"t": 1700000000000 + i * 60000, "o": 1, "h": 2, "l": 0.5, "c": 1.5, "v": 1}
        for i in range(10)
    ]
    provider = _StubPolygon({"results": results})
    candles = provider.fetch_candles("AAPL", 1440, 3, None)
    assert len(candles) == 3  # last 3 only
    assert candles[-1].timestamp == max(c["t"] for c in results)


def test_providers_satisfy_market_data_contract():
    from app.core.providers.base import MarketDataProvider

    for provider in (TwelveDataProvider(api_key="k"), PolygonProvider(api_key="k")):
        assert isinstance(provider, MarketDataProvider)
        assert provider.name


@pytest.mark.parametrize("provider_name,env_var", [
    ("twelvedata", "FOX_TWELVE_DATA_KEY"),
    ("polygon", "FOX_POLYGON_KEY"),
])
def test_market_route_503_when_api_key_missing(monkeypatch, provider_name, env_var):
    pytest.importorskip("fastapi")
    from app.api import create_app
    from app.config import Settings
    from fastapi.testclient import TestClient

    monkeypatch.delenv(env_var, raising=False)
    client = TestClient(create_app(Settings(provider=provider_name, store_backend="memory")))
    r = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10})
    assert r.status_code == 503
    assert "API key" in r.json()["detail"]
