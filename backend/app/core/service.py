"""Framework-free market-data service.

The single entry point the HTTP router delegates to. It validates and clamps
inputs, invokes the configured [MarketDataProvider], and returns the
client-shaped response dict. Pure and synchronous so it is unit-testable
without a running server.

Includes a small in-process TTL cache for candles to absorb duplicate/bursty
client polling of the most-recent window. The cache is keyed by
(symbol, timeframe_label, limit, before_ms) and has a short TTL (a few seconds).

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

# Guardrails independent of any single provider.
MIN_LIMIT = 1
MAX_LIMIT = 5_000
DEFAULT_LIMIT = 500

# Short TTL for the most-recent window cache — enough to absorb bursty polling
# from the same client or multiple tabs, without serving stale historical data.
CACHE_TTL_SECONDS = 5.0

# In-process TTL cache: key -> (expires_at_monotonic, response_dict)
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
    provider_name: str | None = None,
) -> tuple[Any, ...]:
    # Include provider name to avoid cross-provider stale hits if the deployment
    # ever swaps provider mid-process (rare, but safe). Primary key per task spec
    # is (symbol, timeframe_label, limit, before_ms).
    return (symbol, timeframe_label, limit, before_ms, provider_name)


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

    Results are cached in-process for ``CACHE_TTL_SECONDS`` to absorb duplicate
    / bursty polling. For multi-worker deployments this should be moved to a
    shared cache (Redis) — same caveat as the rate limiter.
    """
    timeframe_minutes = parse_timeframe(timeframe_label)
    safe_limit = clamp_limit(limit)

    # Attempt cache hit (fast path)
    key = _cache_key(symbol, timeframe_label, safe_limit, before_ms, getattr(provider, "name", None))
    now = time.monotonic()
    with _cache_lock:
        entry = _cache.get(key)
        if entry is not None:
            expires_at, cached_response = entry
            if now < expires_at:
                # Return a shallow copy to avoid accidental mutation of cached dict
                # (callers treat it as read-only, but be defensive).
                return dict(cached_response)
            else:
                # Expired — evict
                _cache.pop(key, None)

    # Cache miss — fetch from provider
    candles = provider.fetch_candles(
        symbol=symbol,
        timeframe_minutes=timeframe_minutes,
        limit=safe_limit,
        before_ms=before_ms,
    )
    response = build_candles_response(symbol, timeframe_label, candles)

    # Store in cache
    with _cache_lock:
        _cache[key] = (now + CACHE_TTL_SECONDS, response)
        # Opportunistic cleanup: if cache grows too large, evict expired entries
        # (bounded cleanup to avoid unbounded growth in long-running process).
        if len(_cache) > 1000:
            expired_keys = [k for k, (exp, _) in _cache.items() if exp <= now]
            for ek in expired_keys:
                _cache.pop(ek, None)

    return response


def clear_candle_cache() -> None:
    """Clear the in-process candle cache — useful for tests."""
    with _cache_lock:
        _cache.clear()
