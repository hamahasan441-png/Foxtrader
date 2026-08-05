import pytest
from app.core.providers.base import ProviderError
from app.core.providers.registry import UnknownProviderError, build_provider
from app.core.providers.sample import SampleProvider
from app.core.symbols import looks_like_forex, to_slash_pair

# ---- symbol normalization ----

def test_forex_pair_is_detected_and_slashed():
    assert looks_like_forex("EURUSD")
    assert to_slash_pair("eurusd") == "EUR/USD"


def test_non_forex_passes_through():
    assert not looks_like_forex("AAPL")      # too short
    assert not looks_like_forex("BTCUSD")    # BTC not a fiat currency
    assert to_slash_pair("AAPL") == "AAPL"
    assert to_slash_pair("BTCUSD") == "BTCUSD"


# ---- registry ----

def test_build_sample_provider_is_default():
    assert isinstance(build_provider("sample"), SampleProvider)
    assert isinstance(build_provider(""), SampleProvider)


def test_unknown_provider_raises():
    with pytest.raises(UnknownProviderError):
        build_provider("nasdaq-direct")


def test_twelvedata_without_key_raises_provider_error(monkeypatch):
    monkeypatch.delenv("FOX_TWELVEDATA_API_KEY", raising=False)
    with pytest.raises(ProviderError):
        build_provider("twelvedata")


def test_twelvedata_with_key_builds(monkeypatch):
    monkeypatch.setenv("FOX_TWELVEDATA_API_KEY", "test-key")
    provider = build_provider("twelvedata")
    assert provider.name == "twelvedata"
