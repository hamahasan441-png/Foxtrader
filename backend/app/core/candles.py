"""Candle value object and the client-shaped response builder.

Pure module. The response dict produced here matches the Android client's
`CandlesResponse`/`CandleDto` kotlinx-serialization models exactly:

    {"symbol": str, "timeframe": str,
     "candles": [{"timestamp": int, "open": float, "high": float,
                  "low": float, "close": float, "volume": float}, ...]}
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

# Price rounding: FX-grade precision is plenty and keeps payloads tidy.
_PRICE_DP = 5
_VOLUME_DP = 2


@dataclass(frozen=True)
class Candle:
    """A single OHLCV bar. `timestamp` is epoch milliseconds (bar open time)."""

    timestamp: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "timestamp": int(self.timestamp),
            "open": round(self.open, _PRICE_DP),
            "high": round(self.high, _PRICE_DP),
            "low": round(self.low, _PRICE_DP),
            "close": round(self.close, _PRICE_DP),
            "volume": round(self.volume, _VOLUME_DP),
        }


def build_candles_response(
    symbol: str,
    timeframe_label: str,
    candles: list[Candle],
) -> dict[str, Any]:
    """Assemble the exact JSON object the client deserializes into CandlesResponse."""
    return {
        "symbol": symbol,
        "timeframe": timeframe_label,
        "candles": [candle.to_dict() for candle in candles],
    }
