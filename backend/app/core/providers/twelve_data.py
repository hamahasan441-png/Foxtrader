"""Twelve Data market-data provider (forex, stocks, indices, crypto).

Twelve Data returns candles as a time-series of `{datetime, open, high, low,
close, volume}` rows. This adapter maps client timeframe minutes to Twelve
Data's interval vocabulary and normalizes rows into [Candle]s.

Requires an API key via `FOX_TWELVE_DATA_KEY`. Historical paging: the free
endpoint returns recent bars; `before_ms` is honoured by filtering the fetched
window (true deep-history paging would need `start_date`/`end_date` and is a
documented limitation).
"""

from __future__ import annotations

import os
import time
from typing import Any
from urllib.parse import urlencode

from app.core.candles import Candle
from app.core.providers.rest import MissingApiKeyError, ProviderRequestError, RESTProvider

_BASE_URL = "https://api.twelvedata.com/time_series"

# client timeframe minutes -> Twelve Data interval
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


class TwelveDataProvider(RESTProvider):
    name = "twelvedata"
    requires_api_key = True

    def __init__(self, api_key: str | None = None) -> None:
        self._api_key = api_key

    def _resolve_key(self) -> str:
        key = self._api_key or os.environ.get("FOX_TWELVE_DATA_KEY", "")
        if not key:
            raise MissingApiKeyError(
                "Twelve Data API key is required (set FOX_TWELVE_DATA_KEY)."
            )
        return key

    def _fetch_rows(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[dict[str, Any]]:
        interval = _INTERVAL_BY_MINUTES.get(timeframe_minutes)
        if interval is None:
            raise ProviderRequestError(
                f"{self.name} does not support timeframe {timeframe_minutes}m"
            )
        params = {
            "symbol": symbol,
            "interval": interval,
            "outputsize": str(min(max(limit, 1), 5000)),
            "apikey": self._resolve_key(),
            "order": "ASC",
        }
        url = f"{_BASE_URL}?{urlencode(params)}"
        payload = self._get_json(url)
        values = payload.get("values")
        if not isinstance(values, list):
            raise ProviderRequestError(
                f"{self.name} returned no values for {symbol} @ {interval}"
            )
        return values

    def _to_candle(self, row: dict[str, Any]) -> Candle | None:
        # Twelve Data datetimes are like "2024-01-05 16:00:00" in UTC.
        dt = row.get("datetime")
        if not dt:
            return None
        ts = _parse_datetime_ms(dt)
        if ts is None:
            return None
        return Candle(
            timestamp=ts,
            open=float(row["open"]),
            high=float(row["high"]),
            low=float(row["low"]),
            close=float(row["close"]),
            volume=float(row.get("volume") or 0.0),
        )


def _parse_datetime_ms(dt: str) -> int | None:
    """Parse 'YYYY-MM-DD HH:MM:SS' (assumed UTC) to epoch milliseconds."""
    try:
        struct = time.strptime(dt, "%Y-%m-%d %H:%M:%S")
        return int(time.mktime(struct) * 1000)
    except ValueError:
        return None
