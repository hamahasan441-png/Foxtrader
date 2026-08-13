"""Shared base for REST market-data providers.

A provider hits a vendor REST API, normalizes the rows into `Candle`s, and
honours the [MarketDataProvider] contract (ascending, `before_ms` filtered,
internally valid OHLCV). The concrete HTTP call is isolated in [_get_json] so
tests can stub it without hitting the network.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any

from app.core.candles import Candle


class ProviderRequestError(RuntimeError):
    """Raised when an upstream provider returns an error or malformed data."""


class MissingApiKeyError(ProviderRequestError):
    """Raised when a provider requiring an API key is used without one."""


class RESTProvider:
    """Base class for HTTP OHLCV providers."""

    name: str = "rest"
    requires_api_key: bool = True
    _timeout_seconds: float = 15.0

    def fetch_candles(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[Candle]:
        rows = self._fetch_rows(symbol, timeframe_minutes, limit, before_ms)
        candles: list[Candle] = []
        for row in rows:
            try:
                candle = self._to_candle(row)
            except (KeyError, TypeError, ValueError):
                continue  # skip malformed rows; never crash on a bad upstream
            if candle is not None and _is_valid(candle) and (
                before_ms is None or candle.timestamp < before_ms
            ):
                candles.append(candle)
        candles.sort(key=lambda c: c.timestamp)
        return candles[-limit:]

    # ------------------------------------------------------------------
    # Overridden by concrete providers
    # ------------------------------------------------------------------

    def _fetch_rows(
        self,
        symbol: str,
        timeframe_minutes: int,
        limit: int,
        before_ms: int | None,
    ) -> list[dict[str, Any]]:
        raise NotImplementedError

    def _to_candle(self, row: dict[str, Any]) -> Candle | None:
        raise NotImplementedError

    # ------------------------------------------------------------------
    # HTTP helper (overridable for tests)
    # ------------------------------------------------------------------

    def _get_json(self, url: str, headers: dict[str, str] | None = None) -> Any:
        request = urllib.request.Request(url, headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=self._timeout_seconds) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError) as exc:
            raise ProviderRequestError(f"{self.name} request failed: {exc}") from exc
        if isinstance(payload, dict) and payload.get("status") == "error":
            raise ProviderRequestError(f"{self.name} error: {payload.get('message', 'unknown')}")
        return payload


def _is_valid(candle: Candle) -> bool:
    return (
        candle.high >= max(candle.open, candle.close)
        and candle.low <= min(candle.open, candle.close)
        and candle.volume >= 0.0
        and candle.timestamp > 0
    )
