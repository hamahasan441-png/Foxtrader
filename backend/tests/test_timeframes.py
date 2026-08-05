import pytest
from app.core.timeframes import (
    SUPPORTED_LABELS,
    TIMEFRAME_MINUTES,
    UnknownTimeframeError,
    parse_timeframe,
)


def test_all_client_labels_parse_to_expected_minutes():
    expected = {
        "1m": 1, "5m": 5, "15m": 15, "30m": 30,
        "1H": 60, "4H": 240, "1D": 1440, "1W": 10080, "1M": 43200,
    }
    assert TIMEFRAME_MINUTES == expected
    for label, minutes in expected.items():
        assert parse_timeframe(label) == minutes


def test_supported_labels_matches_mapping():
    assert set(SUPPORTED_LABELS) == set(TIMEFRAME_MINUTES)


def test_unknown_timeframe_raises_with_label():
    with pytest.raises(UnknownTimeframeError) as exc:
        parse_timeframe("2h")
    assert exc.value.label == "2h"


def test_labels_are_case_sensitive_matching_the_client():
    # The client sends "1H"/"1D"; lowercase variants are not valid.
    for bad in ("1h", "1d", "1w"):
        with pytest.raises(UnknownTimeframeError):
            parse_timeframe(bad)
