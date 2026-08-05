"""Twelve Data provider.

A real upstream for forex / stock / index candles, behind the same
[MarketDataProvider] seam as the offline sample provider. The *parsing* and
*request shaping* are pure functions (unit-tested offline with canned JSON);
only [TwelveDataProvider.fetch_candles] performs network I/O, importing `httpx`
lazily so this module (and its tests) load without that dependency installed.

Mirrors the client's TwelveDataDataSource: symbol normalization, timeframe→
interval mapping, `status == "error"` detection, and ascending OHLCV mapping.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from app.core.candles import Candle
from app.core.providers.base import ProviderError
from app.core.symbols import to_slash_pair

BASE_URL = "https://api.twelvedata.com/time_series"

# bar length in minutes -> Twelve Data interval token
_INTERVAL_BY_MINUTES: dict[int, str] = {
    1: "1min",
    5: "5min",
    15: "15min",
    30: "30min",
    60: "1h",
    240: "4h",
    1440: "1day",
    10080: "1week",
    43200: "1month",
}


def interval_for(timeframe_minutes: int) -> str:
    """Map our bar length to a Twelve Data interval token."""
    try:
        return _INTERVAL_BY_MINUTES[timeframe_minutes]
    except KeyError as exc:
        raise ProviderError(
            f"Twelve Data has no interval for {timeframe_minutes} minutes"
        ) from exc


def _parse_timestamp_ms(raw: str) -> int:
    """Twelve Data datetimes are UTC 'YYYY-MM-DD HH:MM:SS' or date-only."""
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            dt = datetime.strptime(raw, fmt).replace(tzinfo=timezone.utc)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    raise ProviderError(f"Unparseable Twelve Data datetime: '{raw}'")


def _to_float(value: Any) -> float | None:
    # Twelve Data encodes OHLCV as JSON strings ("1.2345").
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def parse_candles(payload: dict[str, Any]) -> list[Candle]:
    """Turn a Twelve Data time_series JSON object into ascending [Candle]s.

    Raises [ProviderError] when the payload signals an error. A payload with no
    "values" array yields an empty list (no data, not an error).
    """
    if payload.get("status") == "error":
        raise ProviderError(f"Twelve Data: {payload.get('message', 'unknown error')}")

    values = payload.get("values")
    if not isinstance(values, list):
        return []

    candles: list[Candle] = []
    for row in values:
        dt = row.get("datetime")
        o = _to_float(row.get("open"))
        h = _to_float(row.get("high"))
        low = _to_float(row.get("low"))
        c = _to_float(row.get("close"))
        if dt is None or None in (o, h, low, c):
            continue
        volume = _to_float(row.get("volume")) or 0.0
        candles.append(
            Candle(
                timestamp=_parse_timestamp_ms(dt),
                open=o,
                high=h,
                low=low,
                close=c,
                volume=volume,
            )
        )

    # Twelve Data returns newest-first; the contract is oldest-first.
    candles.sort(key=lambda x: x.timestamp)
    return candles


def build_params(
    symbol: str,
    timeframe_minutes: int,
    limit: int,
    api_key: str,
    before_ms: int | None,
) -> dict[str, Any]:
    """Assemble the Twelve Data query params (pure — no I/O)."""
    params: dict[str, Any] = {
        "symbol": to_slash_pair(symbol),
        "interval": interval_for(timeframe_minutes),
        "outputsize": max(1, min(limit, 5000)),
        "apikey": api_key,
        "format": "JSON",
        "order": "desc",
    }
    if before_ms is not None:
        # Fetch history ending just before the oldest bar we already have.
        end = datetime.fromtimestamp((before_ms - 1) / 1000, tz=timezone.utc)
        params["end_date"] = end.strftime("%Y-%m-%d %H:%M:%S")
    return params


class TwelveDataProvider:
    """Fetches candles from Twelve Data (network I/O in fetch_candles only)."""

    name = "twelvedata"

    def __init__(self, api_key: str, timeout_s: float = 10.0) -> None:
        if not api_key:
            raise ProviderError("Twelve Data requires an API key (FOX_TWELVEDATA_API_KEY).")
        self._api_key = api_key
        self._timeout_s = timeout_s

    def fetch_candles(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[Candle]:
        import httpx  # lazy: keeps the module importable without httpx installed

        params = build_params(symbol, timeframe_minutes, limit, self._api_key, before_ms)
        try:
            response = httpx.get(BASE_URL, params=params, timeout=self._timeout_s)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise ProviderError(f"Twelve Data request failed: {exc}") from exc

        candles = parse_candles(response.json())
        # `before` filtering defends against provider off-by-one on end_date.
        if before_ms is not None:
            candles = [c for c in candles if c.timestamp < before_ms]
        return candles[-limit:] if limit and len(candles) > limit else candles
