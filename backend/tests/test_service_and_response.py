import pytest
from app.core.candles import Candle, build_candles_response
from app.core.providers.sample import SampleProvider
from app.core.service import (
    DEFAULT_LIMIT,
    MAX_LIMIT,
    MIN_LIMIT,
    clamp_limit,
    get_candles,
)
from app.core.timeframes import UnknownTimeframeError

FIXED_NOW = 1_700_000_000_000


def provider() -> SampleProvider:
    return SampleProvider(now_ms_fn=lambda: FIXED_NOW)


# ---- response shape (matches the client's CandlesResponse/CandleDto) ----

def test_candle_to_dict_has_exact_client_keys_and_int_timestamp():
    candle = Candle(
        timestamp=1_700_000_000_000, open=1.0, high=2.0, low=0.5, close=1.5, volume=10.0
    )
    d = candle.to_dict()
    assert set(d.keys()) == {"timestamp", "open", "high", "low", "close", "volume"}
    assert isinstance(d["timestamp"], int)


def test_build_response_shape():
    resp = build_candles_response("EURUSD", "1H", [
        Candle(1, 1.0, 1.2, 0.9, 1.1, 100.0),
    ])
    assert set(resp.keys()) == {"symbol", "timeframe", "provider", "source", "candles"}
    assert resp["provider"] == "unknown"
    assert resp["source"] == "live"
    assert resp["symbol"] == "EURUSD"
    assert resp["timeframe"] == "1H"
    assert len(resp["candles"]) == 1


# ---- limit clamping ----

def test_clamp_limit_bounds():
    assert clamp_limit(None) == DEFAULT_LIMIT
    assert clamp_limit(0) == MIN_LIMIT
    assert clamp_limit(-5) == MIN_LIMIT
    assert clamp_limit(MAX_LIMIT + 1000) == MAX_LIMIT
    assert clamp_limit(250) == 250


# ---- get_candles service ----

def test_get_candles_echoes_symbol_and_timeframe_and_respects_limit():
    resp = get_candles("EURUSD", "15m", limit=120, before_ms=None, provider=provider())
    assert resp["symbol"] == "EURUSD"
    assert resp["timeframe"] == "15m"
    assert len(resp["candles"]) == 120


def test_get_candles_clamps_excessive_limit():
    resp = get_candles("EURUSD", "1H", limit=99_999, before_ms=FIXED_NOW, provider=provider())
    assert len(resp["candles"]) == MAX_LIMIT


def test_get_candles_before_paging_is_strictly_earlier():
    resp = get_candles("EURUSD", "1H", limit=50, before_ms=FIXED_NOW, provider=provider())
    assert all(c["timestamp"] < FIXED_NOW for c in resp["candles"])


def test_get_candles_unknown_timeframe_raises():
    with pytest.raises(UnknownTimeframeError):
        get_candles("EURUSD", "3H", limit=10, before_ms=None, provider=provider())
