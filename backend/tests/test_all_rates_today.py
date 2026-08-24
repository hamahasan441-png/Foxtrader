"""Offline tests for the AllRatesToday provider adapter."""

from urllib.parse import parse_qs, urlparse

import pytest

from app.core.providers.all_rates_today import AllRatesTodayProvider
from app.core.providers.registry import build_provider
from app.core.providers.rest import MissingApiKeyError, ProviderRequestError


class _StubAllRatesToday(AllRatesTodayProvider):
    def __init__(self, rates_payload=None, symbols_payload=None):
        super().__init__(api_key="art_test_unit")
        self.rates_payload = rates_payload or []
        self.symbols_payload = symbols_payload or {
            "currencies": [
                {"code": "USD", "name": "US Dollar", "symbol": "$"},
                {"code": "EUR", "name": "Euro", "symbol": "€"},
            ]
        }
        self.last_url = None
        self.last_headers = None

    def _get_json(self, url, headers=None):
        self.last_url = url
        self.last_headers = headers or {}
        if url.endswith("/api/v1/symbols"):
            return self.symbols_payload
        return self.rates_payload


def test_minute_samples_aggregate_into_real_5m_ohlc():
    provider = _StubAllRatesToday(
        rates_payload=[
            {"time": "2026-08-24T12:00:00Z", "rate": 1.10},
            {"time": "2026-08-24T12:01:00Z", "rate": 1.13},
            {"time": "2026-08-24T12:02:00Z", "rate": 1.09},
            {"time": "2026-08-24T12:04:00Z", "rate": 1.12},
            {"time": "2026-08-24T12:05:00Z", "rate": 1.15},
        ]
    )

    candles = provider.fetch_candles("EUR/USD", 5, 10, None)

    assert len(candles) == 2
    assert candles[0].open == pytest.approx(1.10)
    assert candles[0].high == pytest.approx(1.13)
    assert candles[0].low == pytest.approx(1.09)
    assert candles[0].close == pytest.approx(1.12)
    assert candles[0].volume == 0.0
    assert candles[1].close == pytest.approx(1.15)

    query = parse_qs(urlparse(provider.last_url).query)
    assert query["source"] == ["EUR"]
    assert query["target"] == ["USD"]
    assert query["group"] == ["minute"]
    assert "art_test_unit" not in provider.last_url
    assert provider.last_headers["Authorization"] == "Bearer art_test_unit"


def test_hour_group_used_for_h4_and_before_is_honoured():
    provider = _StubAllRatesToday(
        rates_payload=[
            {"time": "2026-08-23T08:00:00Z", "rate": 0.91},
            {"time": "2026-08-23T09:00:00Z", "rate": 0.92},
            {"time": "2026-08-23T12:00:00Z", "rate": 0.93},
        ]
    )
    before = 1787472000000  # safely after test samples
    candles = provider.fetch_candles("USDEUR", 240, 10, before)
    assert candles
    assert all(c.timestamp < before for c in candles)
    assert parse_qs(urlparse(provider.last_url).query)["group"] == ["hour"]


def test_symbol_directory_builds_every_ordered_pair():
    provider = _StubAllRatesToday(
        symbols_payload={
            "currencies": [
                {"code": "USD", "name": "US Dollar", "symbol": "$"},
                {"code": "EUR", "name": "Euro", "symbol": "€"},
                {"code": "JPY", "name": "Japanese Yen", "symbol": "¥"},
            ]
        }
    )
    assert provider.fetch_pairs() == [
        "EURJPY", "EURUSD", "JPYEUR", "JPYUSD", "USDEUR", "USDJPY"
    ]


def test_invalid_pair_rejected_before_network_shape_is_used():
    provider = _StubAllRatesToday([])
    with pytest.raises(ProviderRequestError):
        provider.fetch_candles("BTCUSDT", 1, 10, None)


def test_missing_backend_key_is_explicit(monkeypatch):
    monkeypatch.delenv("ART_API_KEY", raising=False)
    provider = AllRatesTodayProvider(api_key=None)
    with pytest.raises(MissingApiKeyError):
        provider.fetch_candles("EURUSD", 60, 10, None)


def test_registry_accepts_common_all_rates_today_spellings():
    assert isinstance(build_provider("allratestoday"), AllRatesTodayProvider)
    assert isinstance(build_provider("all-rates-today"), AllRatesTodayProvider)
    assert isinstance(build_provider("all_rates_today"), AllRatesTodayProvider)
