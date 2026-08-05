import pytest
from app.core.providers.base import ProviderError
from app.core.providers.twelvedata import build_params, interval_for, parse_candles


def _row(dt, o, h, low, c, v=None):
    row = {"datetime": dt, "open": o, "high": h, "low": low, "close": c}
    if v is not None:
        row["volume"] = v
    return row


# ---- interval mapping ----

def test_interval_for_maps_each_supported_bar_length():
    expected = {
        1: "1min", 5: "5min", 15: "15min", 30: "30min",
        60: "1h", 240: "4h", 1440: "1day", 10080: "1week", 43200: "1month",
    }
    for minutes, token in expected.items():
        assert interval_for(minutes) == token


def test_interval_for_unknown_raises_provider_error():
    with pytest.raises(ProviderError):
        interval_for(120)


# ---- parsing ----

def test_parse_candles_sorts_ascending_and_maps_string_numbers():
    payload = {
        "status": "ok",
        "values": [  # newest-first, as Twelve Data returns
            _row("2024-01-01 00:30:00", "102", "104", "101", "103", "12"),
            _row("2024-01-01 00:00:00", "100", "102", "99", "101", "10"),
            _row("2024-01-01 00:15:00", "101", "103", "100", "102", "11"),
        ],
    }
    candles = parse_candles(payload)
    assert [c.close for c in candles] == [101.0, 102.0, 103.0]
    assert [c.timestamp for c in candles] == sorted(c.timestamp for c in candles)
    assert candles[0].volume == 10.0


def test_parse_candles_daily_datetime_and_missing_volume_defaults_zero():
    payload = {"values": [_row("2024-01-02", "180", "182", "179", "181")]}
    candles = parse_candles(payload)
    assert len(candles) == 1
    assert candles[0].volume == 0.0
    # 2024-01-02 00:00:00 UTC in epoch millis
    assert candles[0].timestamp == 1_704_153_600_000


def test_parse_candles_error_status_raises_with_message():
    with pytest.raises(ProviderError) as exc:
        parse_candles({"status": "error", "message": "bad symbol"})
    assert "bad symbol" in str(exc.value)


def test_parse_candles_missing_values_is_empty_not_error():
    assert parse_candles({"status": "ok"}) == []


def test_parse_candles_skips_rows_with_unparseable_numbers():
    payload = {
        "values": [
            _row("2024-01-01 00:00:00", "x", "1", "1", "1"),
            _row("2024-01-01 00:01:00", "1", "1", "1", "1", "1"),
        ],
    }
    assert len(parse_candles(payload)) == 1


# ---- request shaping ----

def test_build_params_normalizes_forex_and_sets_interval_and_outputsize():
    params = build_params("eurusd", 60, limit=100, api_key="k", before_ms=None)
    assert params["symbol"] == "EUR/USD"
    assert params["interval"] == "1h"
    assert params["outputsize"] == 100
    assert params["apikey"] == "k"
    assert "end_date" not in params


def test_build_params_passes_non_forex_symbol_through():
    assert build_params("AAPL", 1440, limit=10, api_key="k", before_ms=None)["symbol"] == "AAPL"


def test_build_params_clamps_outputsize_and_adds_end_date_for_paging():
    params = build_params("EURUSD", 60, limit=99_999, api_key="k", before_ms=1_700_000_000_000)
    assert params["outputsize"] == 5000
    assert "end_date" in params
