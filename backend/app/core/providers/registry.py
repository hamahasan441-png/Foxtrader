"""Provider selection.

Maps a settings/env provider name to a concrete [MarketDataProvider]. Offline
sample and real Twelve Data / Polygon / AllRatesToday adapters are wired here,
keeping selection in one place instead of scattered conditionals.
"""

from __future__ import annotations

from app.core.providers.all_rates_today import AllRatesTodayProvider
from app.core.providers.base import MarketDataProvider
from app.core.providers.polygon import PolygonProvider
from app.core.providers.sample import SampleProvider
from app.core.providers.twelve_data import TwelveDataProvider


class UnknownProviderError(ValueError):
    """Raised when configured with a provider name that isn't registered."""


def build_provider(name: str) -> MarketDataProvider:
    key = (name or "sample").strip().lower().replace("_", "").replace("-", "")
    if key == "sample":
        return SampleProvider()
    if key == "twelvedata":
        return TwelveDataProvider()
    if key == "polygon":
        return PolygonProvider()
    if key == "allratestoday":
        return AllRatesTodayProvider()
    raise UnknownProviderError(
        f"Unknown provider '{name}'. Available: sample, twelvedata, polygon, allratestoday"
    )
