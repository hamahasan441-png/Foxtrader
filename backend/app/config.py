"""Runtime configuration, read from the environment (stdlib only).

Deliberately avoids pydantic-settings so the config layer has zero third-party
dependencies and can be imported/tested offline.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _split_csv(raw: str) -> list[str]:
    return [item.strip() for item in raw.split(",") if item.strip()]


def _env_bool(name: str, default: bool) -> bool:
    """Parse a boolean env var; missing/unparseable falls back to [default]."""
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    """Parse an int env var; missing/unparseable falls back to [default]."""
    value = os.environ.get(name)
    if value is None:
        return default
    try:
        return int(value.strip())
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """Backend settings resolved from environment variables."""

    #: Which provider to serve candles from. Only "sample" ships today.
    provider: str = "sample"
    #: CORS allow-list. Defaults to a single local origin rather than "*" so a
    #: fresh deployment never silently allows any cross-origin site to call the
    #: API. Operators must explicitly opt in to a wildcard or extra origins.
    cors_origins: list[str] = field(default_factory=lambda: ["http://localhost"])
    #: Whether credentialed (cookies / Authorization) cross-origin requests are
    #: allowed. The application will only honour this when [cors_origins] is an
    #: explicit non-wildcard allow-list; a wildcard origin can never carry
    #: credentials, so [create_app] force-disables it and logs a hard warning.
    allow_credentials: bool = True
    #: Auth/sync persistence backend: "memory" (stateless) or "sqlite" (durable).
    #: Defaults to "memory" for direct construction (tests); `from_env` defaults
    #: to "sqlite" for real deployments.
    store_backend: str = "memory"
    #: SQLite database file path (only used when [store_backend] is "sqlite").
    db_path: str = "foxtrader.db"
    #: Enable auth/sync rate limiting (per client IP).
    rate_limit_enabled: bool = True
    #: Max auth requests per client IP per window.
    rate_limit_auth_per_window: int = 20
    #: Rate-limit window in seconds.
    rate_limit_window_seconds: int = 60
    app_name: str = "FoxTrader Market Data API"
    version: str = "0.1.0"

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            provider=os.environ.get("FOX_PROVIDER", "sample").strip() or "sample",
            cors_origins=_split_csv(os.environ.get("FOX_CORS_ORIGINS", "http://localhost")) or ["http://localhost"],
            allow_credentials=_env_bool("FOX_ALLOW_CREDENTIALS", True),
            # Real deployments default to the durable SQLite backend.
            store_backend=(os.environ.get("FOX_STORE", "sqlite").strip() or "sqlite").lower(),
            db_path=os.environ.get("FOX_DB_PATH", "foxtrader.db").strip() or "foxtrader.db",
            rate_limit_enabled=_env_bool("FOX_RATE_LIMIT_ENABLED", True),
            rate_limit_auth_per_window=_env_int("FOX_RATE_LIMIT_AUTH_PER_WINDOW", 20),
            rate_limit_window_seconds=_env_int("FOX_RATE_LIMIT_WINDOW_SECONDS", 60),
            app_name=os.environ.get("FOX_APP_NAME", "FoxTrader Market Data API"),
            version=os.environ.get("FOX_VERSION", "0.1.0"),
        )
