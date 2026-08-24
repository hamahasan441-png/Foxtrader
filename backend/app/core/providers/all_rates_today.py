"""AllRatesToday FX market-data provider.

AllRatesToday exposes live interbank mid-market rate samples and historical
rate series rather than exchange OHLCV bars. FoxTrader requests the finest
documented grouping needed by the selected chart timeframe and aggregates real
returned samples into OHLC bars. Volume is unavailable and remains 0.0.

The credential can be supplied per request by the FoxTrader Android app through
the backend proxy, or via ART_API_KEY for server-managed deployments.
"""

from __future__ import annotations

import calendar
import os
import re
import time
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlencode

from app.core.candles import Candle
from app.core.providers.rest import MissingApiKeyError, ProviderRequestError, RESTProvider

_BASE_URL = "https://allratestoday.com"
_RATES_URL = f"{_BASE_URL}/api/v1/rates"
_SYMBOLS_URL = f"{_BASE_URL}/api/v1/symbols"
_PAIR_RE = re.compile(r"^[A-Z]{6}$")


class AllRatesTodayProvider(RESTProvider):
    name = "allratestoday"
    requires_api_key = True

    def __init__(self, api_key: str | None = None) -> None:
        self._api_key = api_key

    def _resolve_key(self) -> str:
        key = (self._api_key or os.environ.get("ART_API_KEY", "")).strip()
        if not key:
            raise MissingApiKeyError(
                "AllRatesToday API key is required. Enter it in FoxTrader Settings or set ART_API_KEY on the backend."
            )
        return key

    def _auth_headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self._resolve_key()}",
            "Accept": "application/json",
        }

    def fetch_currencies(self) -> list[dict[str, str]]:
        payload = self._get_json(_SYMBOLS_URL, headers=self._auth_headers())
        if not isinstance(payload, dict):
            raise ProviderRequestError("allratestoday returned a malformed symbols payload")
        raw = payload.get("currencies")
        if not isinstance(raw, list):
            raise ProviderRequestError("allratestoday symbols payload has no currencies list")

        currencies: list[dict[str, str]] = []
        seen: set[str] = set()
        for item in raw:
            if not isinstance(item, dict):
                continue
            code = str(item.get("code") or "").strip().upper()
            if len(code) != 3 or not code.isalpha() or code in seen:
                continue
            seen.add(code)
            currencies.append(
                {
                    "code": code,
                    "name": str(item.get("name") or code).strip() or code,
                    "symbol": str(item.get("symbol") or "").strip(),
                }
            )
        if len(currencies) < 2:
            raise ProviderRequestError("allratestoday returned too few valid currencies")
        currencies.sort(key=lambda row: row["code"])
        return currencies

    def fetch_pairs(self) -> list[str]:
        codes = [row["code"] for row in self.fetch_currencies()]
        return [f"{base}{quote}" for base in codes for quote in codes if base != quote]

    def _fetch_rows(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[dict[str, Any]]:
        base, quote = _parse_pair(symbol)
        safe_limit = min(max(int(limit), 1), 5000)
        end_ms = (before_ms - 1) if before_ms is not None else int(time.time() * 1000)
        requested_span_ms = timeframe_minutes * safe_limit * 60_000
        margin = max(requested_span_ms // 3, 2 * 86_400_000)
        start_ms = max(0, end_ms - requested_span_ms - margin)

        params = {
            "source": base,
            "target": quote,
            "from": _date_utc(start_ms),
            "to": _date_utc(end_ms),
            "group": _group_for_timeframe(timeframe_minutes),
        }
        payload = self._get_json(
            f"{_RATES_URL}?{urlencode(params)}",
            headers=self._auth_headers(),
        )
        if isinstance(payload, dict) and payload.get("error"):
            raise ProviderRequestError(f"allratestoday error: {payload.get('error')}")

        points = _extract_rate_points(payload, quote)
        points = [(ts, rate) for ts, rate in points if start_ms <= ts <= end_ms]
        if before_ms is not None:
            points = [(ts, rate) for ts, rate in points if ts < before_ms]
        if not points:
            raise ProviderRequestError(
                f"allratestoday returned no rate samples for {base}/{quote}"
            )
        return _aggregate_rows(points, timeframe_minutes)[-safe_limit:]

    def _to_candle(self, row: dict[str, Any]) -> Candle | None:
        return Candle(
            timestamp=int(row["timestamp"]),
            open=float(row["open"]),
            high=float(row["high"]),
            low=float(row["low"]),
            close=float(row["close"]),
            volume=0.0,
        )


def _parse_pair(symbol: str) -> tuple[str, str]:
    normalized = (
        symbol.strip().upper()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
    )
    if not _PAIR_RE.fullmatch(normalized):
        raise ProviderRequestError(
            f"allratestoday expects a 6-letter ISO currency pair, got '{symbol}'"
        )
    base, quote = normalized[:3], normalized[3:]
    if base == quote:
        raise ProviderRequestError("allratestoday base and quote currencies must differ")
    return base, quote


def _group_for_timeframe(timeframe_minutes: int) -> str:
    if timeframe_minutes < 60:
        return "minute"
    if timeframe_minutes < 1440:
        return "hour"
    return "day"


def _date_utc(timestamp_ms: int) -> str:
    return datetime.fromtimestamp(timestamp_ms / 1000.0, tz=timezone.utc).strftime("%Y-%m-%d")


def _parse_timestamp_ms(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        raw = float(value)
        return int(raw if raw >= 100_000_000_000 else raw * 1000)
    text = str(value).strip()
    if not text:
        return None
    if text.isdigit():
        return _parse_timestamp_ms(int(text))
    try:
        normalized = text.replace("Z", "+00:00")
        dt = datetime.fromisoformat(normalized)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp() * 1000)
    except ValueError:
        pass
    try:
        tm = time.strptime(text[:10], "%Y-%m-%d")
        return calendar.timegm(tm) * 1000
    except ValueError:
        return None


def _extract_rate_points(payload: Any, target: str) -> list[tuple[int, float]]:
    rows: list[Any]
    if isinstance(payload, list):
        rows = payload
    elif isinstance(payload, dict):
        candidate = payload.get("data")
        if isinstance(candidate, list):
            rows = candidate
        else:
            candidate = payload.get("rates")
            if isinstance(candidate, list):
                rows = candidate
            elif isinstance(candidate, dict):
                rows = []
                for key, value in candidate.items():
                    if isinstance(value, dict):
                        rate_value = value.get(target) or value.get("rate")
                    else:
                        rate_value = value
                    rows.append({"time": key, "rate": rate_value})
            elif "rate" in payload:
                rows = [payload]
            else:
                rows = []
    else:
        rows = []

    points: list[tuple[int, float]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        ts = _parse_timestamp_ms(row.get("timestamp") or row.get("time") or row.get("date"))
        rate_value = row.get("rate")
        if rate_value is None and isinstance(row.get("rates"), dict):
            rate_value = row["rates"].get(target)
        try:
            rate = float(rate_value)
        except (TypeError, ValueError):
            continue
        if ts is None or rate <= 0.0:
            continue
        points.append((ts, rate))

    return sorted({ts: rate for ts, rate in points}.items())


def _aggregate_rows(
    points: list[tuple[int, float]], timeframe_minutes: int
) -> list[dict[str, Any]]:
    bucket_ms = timeframe_minutes * 60_000
    buckets: dict[int, list[tuple[int, float]]] = defaultdict(list)
    for ts, rate in points:
        bucket = (ts // bucket_ms) * bucket_ms
        buckets[bucket].append((ts, rate))

    rows: list[dict[str, Any]] = []
    for bucket in sorted(buckets):
        samples = sorted(buckets[bucket], key=lambda item: item[0])
        values = [rate for _, rate in samples]
        rows.append(
            {
                "timestamp": bucket,
                "open": values[0],
                "high": max(values),
                "low": min(values),
                "close": values[-1],
                "volume": 0.0,
            }
        )
    return rows
