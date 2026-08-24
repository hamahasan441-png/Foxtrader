"""Framework-free market-data service.

The single entry point the HTTP router delegates to. It validates and clamps
inputs, invokes the configured [MarketDataProvider], and returns the
client-shaped response dict. Pure and synchronous so it is unit-testable
without a running server.

Includes a small in-process TTL cache for candles to absorb duplicate/bursty
client polling of the most-recent window. The cache is keyed by symbol,
timeframe, window and provider cache namespace. Credentialed providers may
supply a non-secret cache namespace so one client's authenticated request can
never satisfy another client's key validation path.

NOTE: This is in-process only. A multi-worker deployment should later move this
to a shared cache (Redis), same pattern as the rate limiter's own documented
caveat (see app.core.ratelimit). The interface is intentionally tiny so that
swap is trivial.
"""

from __future__ import annotations

import threading
import time
from typing import Any

from app.core.candles import build_candles_response
from app.core.providers.base import MarketDataProvider
from app.core.timeframes import parse_timeframe

MIN_LIMIT = 1
MAX_LIMIT = 5_000
DEFAULT_LIMIT = 500
CACHE_TTL_SECONDS = 5.0

_cache: dict[tuple[Any, ...], tuple[float, dict[str, Any]]] = {}
_cache_lock = threading.RLock()


def clamp_limit(limit: int | None) -> int:
    """Coerce a requested candle count into the allowed range."""
    if limit is None:
        return DEFAULT_LIMIT
    return max(MIN_LIMIT, min(MAX_LIMIT, limit))


def _cache_key(
    symbol: str,
    timeframe_label: str,
    limit: int,
    before_ms: int | None,
    provider_namespace: str | None = None,
) -> tuple[Any, ...]:
    return (symbol, timeframe_label, limit, before_ms, provider_namespace)


def _provider_cache_namespace(provider: MarketDataProvider) -> str | None:
    """Resolve a safe cache namespace without ever storing a raw credential."""
    namespace = getattr(provider, "cache_namespace", None)
    if callable(namespace):
        namespace = namespace()
    if namespace is not None:
        return str(namespace)
    return getattr(provider, "name", None)


def get_candles(
    symbol: str,
    timeframe_label: str,
    limit: int | None,
    before_ms: int | None,
    provider: MarketDataProvider,
) -> dict[str, Any]:
    """Resolve a candles request into the client's CandlesResponse shape."""
    timeframe_minutes = parse_timeframe(timeframe_label)
    safe_limit = clamp_limit(limit)

    # Resolve the provider namespace before cache lookup. Credentialed providers
    # can validate their key here and provide a one-way fingerprint namespace,
    # preventing an invalid/missing key from receiving a previous valid hit.
    provider_namespace = _provider_cache_namespace(provider)
    key = _cache_key(symbol, timeframe_label, safe_limit, before_ms, provider_namespace)
    now = time.monotonic()
    with _cache_lock:
        entry = _cache.get(key)
        if entry is not None:
            expires_at, cached_response = entry
            if now < expires_at:
                return dict(cached_response)
            _cache.pop(key, None)

    candles = provider.fetch_candles(
        symbol=symbol,
        timeframe_minutes=timeframe_minutes,
        limit=safe_limit,
        before_ms=before_ms,
    )
    provider_name = getattr(provider, "name", "unknown")
    source = "synthetic" if provider_name.lower() == "sample" else "live"
    response = build_candles_response(
        symbol,
        timeframe_label,
        candles,
        provider=provider_name,
        source=source,
    )

    with _cache_lock:
        _cache[key] = (now + CACHE_TTL_SECONDS, response)
        if len(_cache) > 1000:
            expired_keys = [k for k, (exp, _) in _cache.items() if exp <= now]
            for expired_key in expired_keys:
                _cache.pop(expired_key, None)

    return response


def clear_candle_cache() -> None:
    """Clear the in-process candle cache — useful for tests."""
    with _cache_lock:
        _cache.clear()
