"""Framework-free market-data service.

The single entry point the HTTP router delegates to. It validates and clamps
inputs, invokes the configured [MarketDataProvider], and returns the
client-shaped response dict. Pure and synchronous so it is unit-testable
without a running server.
"""

from __future__ import annotations

from typing import Any

from app.core.candles import build_candles_response
from app.core.providers.base import MarketDataProvider
from app.core.timeframes import parse_timeframe

# Guardrails independent of any single provider.
MIN_LIMIT = 1
MAX_LIMIT = 5_000
DEFAULT_LIMIT = 500


def clamp_limit(limit: int | None) -> int:
    """Coerce a requested candle count into the allowed range."""
    if limit is None:
        return DEFAULT_LIMIT
    return max(MIN_LIMIT, min(MAX_LIMIT, limit))


def get_candles(
    symbol: str,
    timeframe_label: str,
    limit: int | None,
    before_ms: int | None,
    provider: MarketDataProvider,
) -> dict[str, Any]:
    """Resolve a candles request into the client's CandlesResponse shape.

    Raises [UnknownTimeframeError] for an unsupported timeframe label so the
    caller can map it to HTTP 400.
    """
    timeframe_minutes = parse_timeframe(timeframe_label)
    safe_limit = clamp_limit(limit)
    candles = provider.fetch_candles(
        symbol=symbol,
        timeframe_minutes=timeframe_minutes,
        limit=safe_limit,
        before_ms=before_ms,
    )
    return build_candles_response(symbol, timeframe_label, candles)
