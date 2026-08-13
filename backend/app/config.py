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


@dataclass(frozen=True)
class Settings:
    """Backend settings resolved from environment variables."""

    #: Which provider to serve candles from. Only "sample" ships today.
    provider: str = "sample"
    #: CORS allow-list; "*" in dev.
    cors_origins: list[str] = field(default_factory=lambda: ["*"])
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
    app_name: str = "FoxTrader Market Data API"
    version: str = "0.1.0"

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            provider=os.environ.get("FOX_PROVIDER", "sample").strip() or "sample",
            cors_origins=_split_csv(os.environ.get("FOX_CORS_ORIGINS", "*")) or ["*"],
            allow_credentials=_env_bool("FOX_ALLOW_CREDENTIALS", True),
            # Real deployments default to the durable SQLite backend.
            store_backend=(os.environ.get("FOX_STORE", "sqlite").strip() or "sqlite").lower(),
            db_path=os.environ.get("FOX_DB_PATH", "foxtrader.db").strip() or "foxtrader.db",
            app_name=os.environ.get("FOX_APP_NAME", "FoxTrader Market Data API"),
            version=os.environ.get("FOX_VERSION", "0.1.0"),
        )
