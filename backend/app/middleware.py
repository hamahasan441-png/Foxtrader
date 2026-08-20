"""HTTP middleware: structured request logging + auth/market rate limiting.

- [LoggingMiddleware] emits one structured log line per request (method, path,
  status, duration, client IP) — never token/body contents.
- [RateLimitMiddleware] throttles the auth endpoints per client IP to blunt
  credential-stuffing/brute-force attempts and returns 429 with standard
  `Retry-After` headers when exceeded. It also throttles market-data endpoints
  with a higher-throughput bucket (legitimate polling).

Both are configured from `app.state.settings` and wired in `app/api.py`.
"""

from __future__ import annotations

import logging
import time

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse

from app.core.ratelimit import RateLimiter

logger = logging.getLogger("fox.http")

_AUTH_RATE_LIMITED_PREFIXES = (
    "/api/v1/auth/",
    "/api/v1/sync/push",
)

_MARKET_RATE_LIMITED_PREFIXES = (
    "/api/v1/market/",
)

# Backward compat: old name used in tests / docs. Now includes market path
# for the single-limiter convenience, but production uses two separate buckets.
_RATE_LIMITED_PREFIXES = _AUTH_RATE_LIMITED_PREFIXES + _MARKET_RATE_LIMITED_PREFIXES


def client_ip(
    request: Request, trusted_proxies: list[str] | None = None
) -> str:
    """Return the effective client IP, respecting X-Forwarded-For only when trusted.

    The immediate TCP peer is ``request.client.host``. We only honor
    ``X-Forwarded-For`` when that peer is in ``trusted_proxies``. When trusted,
    we take the **first** entry of XFF (standard behaviour where the proxy
    prepends the original client IP, e.g. ``XFF: client, proxy1, proxy2``).
    If no trusted proxy list is supplied, we attempt to read it from
    ``request.app.state.settings.trusted_proxies``; when absent we treat it as
    empty (i.e. never trust XFF), which is the safe default.

    This prevents any client from spoofing a fresh rate-limit bucket per
    request by setting an arbitrary XFF header.
    """

    peer = request.client.host if request.client else "unknown"

    # Resolve trusted proxies: explicit arg > app state > empty (safe default)
    if trusted_proxies is None:
        try:
            settings = getattr(request.app.state, "settings", None)
            trusted_proxies = getattr(settings, "trusted_proxies", []) if settings else []
        except Exception:
            trusted_proxies = []

    forwarded = request.headers.get("x-forwarded-for")
    if forwarded and peer in (trusted_proxies or []):
        # Trust X-Forwarded-For only from a known proxy.
        # Take first entry assuming the proxy prepends client IP (standard).
        first = forwarded.split(",")[0].strip()
        if first:
            return first

    return peer


class LoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        start = time.monotonic()
        try:
            response = await call_next(request)
        except Exception:
            logger.exception(
                "request_failed",
                extra={
                    "method": request.method,
                    "path": request.url.path,
                    "client": client_ip(request),
                },
            )
            raise
        duration_ms = (time.monotonic() - start) * 1000.0
        logger.info(
            "request_completed",
            extra={
                "method": request.method,
                "path": request.url.path,
                "status": response.status_code,
                "duration_ms": round(duration_ms, 2),
                "client": client_ip(request),
            },
        )
        return response


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Rate limiter with separate buckets for auth and market data.

    Auth endpoints get a strict bucket (low per-window limit) to blunt brute-
    force. Market-data endpoints get a higher-throughput bucket because legitimate
    clients poll them frequently.

    For backward compatibility the constructor accepts a single ``limiter`` that
    is used for both buckets when ``market_limiter`` is not supplied (the old
    tests construct it that way). Production in ``app/api.py`` passes distinct
    limiters.
    """

    def __init__(
        self,
        app,
        limiter: RateLimiter,
        market_limiter: RateLimiter | None = None,
    ) -> None:
        super().__init__(app)
        self._limiter = limiter
        self._market_limiter = market_limiter or limiter

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        # Determine which bucket applies
        limiter: RateLimiter | None = None
        if path.startswith(_MARKET_RATE_LIMITED_PREFIXES):
            limiter = self._market_limiter
        elif path.startswith(_AUTH_RATE_LIMITED_PREFIXES):
            limiter = self._limiter

        if limiter is not None:
            # Resolve trusted proxies from settings if present (safe default = empty).
            try:
                settings = getattr(request.app.state, "settings", None)
                trusted = getattr(settings, "trusted_proxies", []) if settings else []
            except Exception:
                trusted = []
            key = client_ip(request, trusted_proxies=trusted)
            if not limiter.allow(key):
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Too many requests — slow down and try again shortly."},
                    headers={
                        "Retry-After": str(limiter.window_seconds),
                        "X-RateLimit-Limit": str(limiter.max_requests),
                        "X-RateLimit-Remaining": str(limiter.remaining(key)),
                    },
                )
        return await call_next(request)
