"""Market-data provider seam.

A provider turns (symbol, timeframe, window) into OHLCV candles. Keeping this a
`Protocol` means the HTTP layer depends on the shape, not a concrete source, so
a real upstream (Twelve Data, Polygon, OANDA, ...) can be dropped in later
behind the same contract without touching the router — mirroring the client's
own provider-adapter design.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from app.core.candles import Candle


@runtime_checkable
class MarketDataProvider(Protocol):
    """Fetches candles for one symbol/timeframe window, oldest-first."""

    #: Short identifier surfaced in /health and logs (e.g. "sample").
    name: str

    def fetch_candles(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[Candle]:
        """Return up to `limit` candles.

        Contract:
        - Candles are returned in ascending timestamp order (oldest first).
        - `timeframe_minutes` is the bar length; consecutive bars are spaced by
          exactly that many minutes.
        - When `before_ms` is given, every returned candle's timestamp is
          strictly less than `before_ms` (used for paging older history).
        - Each candle is internally valid: high >= max(open, close),
          low <= min(open, close), volume >= 0.
        """
        ...
