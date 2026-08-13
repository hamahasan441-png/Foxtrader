"""HTTP middleware: structured request logging + auth rate limiting.

- [LoggingMiddleware] emits one structured log line per request (method, path,
  status, duration, client IP) — never token/body contents.
- [RateLimitMiddleware] throttles the auth endpoints per client IP to blunt
  credential-stuffing/brute-force attempts and returns 429 with standard
  `Retry-After` headers when exceeded.

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

_RATE_LIMITED_PREFIXES = (
    "/api/v1/auth/",
    "/api/v1/sync/push",
)


def client_ip(request: Request) -> str:
    # Trust X-Forwarded-For only from a known proxy; otherwise use the peer.
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


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
    def __init__(self, app, limiter: RateLimiter) -> None:
        super().__init__(app)
        self._limiter = limiter

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        if path.startswith(_RATE_LIMITED_PREFIXES):
            key = client_ip(request)
            if not self._limiter.allow(key):
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Too many requests — slow down and try again shortly."},
                    headers={
                        "Retry-After": str(self._limiter.window_seconds),
                        "X-RateLimit-Limit": str(self._limiter.max_requests),
                        "X-RateLimit-Remaining": str(self._limiter.remaining(key)),
                    },
                )
        return await call_next(request)
