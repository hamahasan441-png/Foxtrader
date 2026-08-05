"""Deterministic synthetic market-data provider.

This is the default provider: it needs no network, no API key, and produces a
stable, seeded random walk so the endpoint is immediately usable for local
development and tests. It is explicitly *not* real market data — the client
labels anything from this back end accordingly. Real providers implement the
same [MarketDataProvider] protocol and replace this one via configuration.
"""

from __future__ import annotations

import random
import time
import zlib

from app.core.candles import Candle

_MS_PER_MINUTE = 60_000


class SampleProvider:
    """A reproducible synthetic OHLCV source (seeded per symbol)."""

    name = "sample"

    def __init__(self, now_ms_fn=None) -> None:
        # Injectable clock keeps tests independent of wall-clock time.
        self._now_ms_fn = now_ms_fn or (lambda: int(time.time() * 1000))

    def fetch_candles(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[Candle]:
        if limit <= 0 or timeframe_minutes <= 0:
            return []

        step = timeframe_minutes * _MS_PER_MINUTE
        # Anchor the newest bar just before `before_ms` (paging) or "now",
        # aligned to the timeframe grid so bars are evenly spaced.
        anchor = before_ms if before_ms is not None else self._now_ms_fn()
        newest_open = ((anchor // step) - 1) * step
        oldest_open = newest_open - step * (limit - 1)

        # Deterministic seed from the symbol (process-independent, unlike hash()).
        seed = zlib.crc32(symbol.encode("utf-8"))
        rng = random.Random(seed)

        # A stable, symbol-specific base price and volatility.
        base_price = 50.0 + (seed % 20_000) / 100.0  # ~50..250
        volatility = base_price * 0.004  # ~0.4% per bar

        candles: list[Candle] = []
        close = base_price
        ts = oldest_open
        while ts <= newest_open:
            open_ = close
            drift = rng.uniform(-volatility, volatility)
            close = max(0.01, open_ + drift)
            wick = abs(rng.uniform(0.0, volatility))
            high = max(open_, close) + wick
            low = max(0.01, min(open_, close) - wick)
            volume = round(rng.uniform(500.0, 5_000.0), 2)
            candles.append(
                Candle(
                    timestamp=ts,
                    open=open_,
                    high=high,
                    low=low,
                    close=close,
                    volume=volume,
                )
            )
            ts += step

        return candles
