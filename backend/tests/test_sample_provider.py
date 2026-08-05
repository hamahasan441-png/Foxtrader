from app.core.providers.sample import SampleProvider

FIXED_NOW = 1_700_000_000_000  # fixed clock so tests don't depend on wall time


def provider() -> SampleProvider:
    return SampleProvider(now_ms_fn=lambda: FIXED_NOW)


def test_returns_requested_count():
    candles = provider().fetch_candles("EURUSD", 60, limit=200, before_ms=None)
    assert len(candles) == 200


def test_candles_are_ascending_and_evenly_spaced_by_timeframe():
    tf_minutes = 15
    candles = provider().fetch_candles("AAPL", tf_minutes, limit=50, before_ms=None)
    timestamps = [c.timestamp for c in candles]
    assert timestamps == sorted(timestamps)
    step = tf_minutes * 60_000
    diffs = {b - a for a, b in zip(timestamps, timestamps[1:], strict=False)}
    assert diffs == {step}


def test_ohlc_is_internally_valid():
    for c in provider().fetch_candles("BTCUSD", 240, limit=120, before_ms=None):
        assert c.high >= max(c.open, c.close)
        assert c.low <= min(c.open, c.close)
        assert c.low > 0.0
        assert c.volume >= 0.0


def test_determinism_same_inputs_same_output():
    a = provider().fetch_candles("EURUSD", 60, limit=100, before_ms=FIXED_NOW)
    b = provider().fetch_candles("EURUSD", 60, limit=100, before_ms=FIXED_NOW)
    assert [c.to_dict() for c in a] == [c.to_dict() for c in b]


def test_different_symbols_differ():
    a = provider().fetch_candles("EURUSD", 60, limit=100, before_ms=FIXED_NOW)
    b = provider().fetch_candles("GBPUSD", 60, limit=100, before_ms=FIXED_NOW)
    assert [c.to_dict() for c in a] != [c.to_dict() for c in b]


def test_before_paging_returns_only_earlier_bars():
    before = FIXED_NOW
    candles = provider().fetch_candles("EURUSD", 60, limit=100, before_ms=before)
    assert candles, "expected candles"
    assert all(c.timestamp < before for c in candles)


def test_non_positive_limit_or_timeframe_yields_empty():
    p = provider()
    assert p.fetch_candles("EURUSD", 60, limit=0, before_ms=None) == []
    assert p.fetch_candles("EURUSD", 0, limit=10, before_ms=None) == []
