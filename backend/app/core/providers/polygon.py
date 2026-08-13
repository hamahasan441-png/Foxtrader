"""Polygon.io market-data provider (stocks, forex, crypto, indices).

Polygon returns aggregate bars as `{t, o, h, l, c, v, ...}` where `t` is the
epoch **milliseconds** bar-open time (already in our native unit). This adapter
maps client timeframe minutes to Polygon's `multiplier`/`timespan` vocabulary and
uses Polygon's `from`/`to` date window for `before_ms` paging.

Requires an API key via `FOX_POLYGON_KEY`.
"""

from __future__ import annotations

import os
from typing import Any
from urllib.parse import urlencode

from app.core.candles import Candle
from app.core.providers.rest import MissingApiKeyError, ProviderRequestError, RESTProvider

_BASE_URL = "https://api.polygon.io/v2/aggs/ticker/{symbol}/range/{mult}/{span}/{start}/{end}"

# client timeframe minutes -> (multiplier, timespan)
_MULT_TIMESPAN: dict[int, tuple[int, str]] = {
    1: (1, "minute"),
    5: (5, "minute"),
    15: (15, "minute"),
    30: (30, "minute"),
    60: (1, "hour"),
    240: (4, "hour"),
    1440: (1, "day"),
    10080: (1, "week"),
    43200: (1, "month"),
}


class PolygonProvider(RESTProvider):
    name = "polygon"
    requires_api_key = True

    def __init__(self, api_key: str | None = None) -> None:
        self._api_key = api_key

    def _resolve_key(self) -> str:
        key = self._api_key or os.environ.get("FOX_POLYGON_KEY", "")
        if not key:
            raise MissingApiKeyError("Polygon API key is required (set FOX_POLYGON_KEY).")
        return key

    def _fetch_rows(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[dict[str, Any]]:
        mapping = _MULT_TIMESPAN.get(timeframe_minutes)
        if mapping is None:
            raise ProviderRequestError(
                f"{self.name} does not support timeframe {timeframe_minutes}m"
            )
        mult, span = mapping

        # Window: from = a few bars before `to` (or ~50 bars of history back),
        # to = the bar before `before_ms` (or today).
        end_ms = (before_ms or _now_ms()) - 1
        # Estimate a start far enough back to cover `limit` bars.
        start_ms = end_ms - limit * timeframe_minutes * 60_000 - 60_000
        params = {
            "adjusted": "true",
            "sort": "asc",
            "limit": str(min(max(limit, 1), 5000)),
            "apiKey": self._resolve_key(),
        }
        url = _BASE_URL.format(
            symbol=symbol, mult=mult, span=span,
            start=_date(start_ms), end=_date(end_ms),
        ) + f"?{urlencode(params)}"
        payload = self._get_json(url)
        results = payload.get("results")
        if not isinstance(results, list):
            raise ProviderRequestError(
                f"{self.name} returned no results for {symbol} @ {mult}{span}"
            )
        return results

    def _to_candle(self, row: dict[str, Any]) -> Candle | None:
        ts = row.get("t")
        if not ts:
            return None
        return Candle(
            timestamp=int(ts),
            open=float(row["o"]),
            high=float(row["h"]),
            low=float(row["l"]),
            close=float(row["c"]),
            volume=float(row.get("v") or 0.0),
        )


def _now_ms() -> int:
    import time as _time

    return int(_time.time() * 1000)


def _date(ms: int) -> str:
    import datetime as _dt

    return _dt.datetime.fromtimestamp(ms / 1000, tz=_dt.timezone.utc).strftime("%Y-%m-%d")
