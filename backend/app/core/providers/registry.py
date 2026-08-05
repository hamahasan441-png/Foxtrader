"""Provider selection.

Maps a settings/env provider name to a concrete [MarketDataProvider]. Today
only the offline `sample` provider is wired; real upstreams register here as
they are implemented (e.g. "twelvedata", "polygon"), keeping selection in one
place instead of scattered conditionals.
"""

from __future__ import annotations

import os

from app.core.providers.base import MarketDataProvider
from app.core.providers.sample import SampleProvider

AVAILABLE = ("sample", "twelvedata")


class UnknownProviderError(ValueError):
    """Raised when configured with a provider name that isn't registered."""


def build_provider(name: str) -> MarketDataProvider:
    key = (name or "sample").strip().lower()
    if key == "sample":
        return SampleProvider()
    if key == "twelvedata":
        # Imported here so the default path never depends on the real provider.
        from app.core.providers.twelvedata import TwelveDataProvider

        return TwelveDataProvider(api_key=os.environ.get("FOX_TWELVEDATA_API_KEY", ""))
    raise UnknownProviderError(
        f"Unknown provider '{name}'. Available: {', '.join(AVAILABLE)}"
    )
