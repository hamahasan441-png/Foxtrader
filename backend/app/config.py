"""Runtime configuration, read from the environment (stdlib only).

Deliberately avoids pydantic-settings so the config layer has zero third-party
dependencies and can be imported/tested offline.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _split_csv(raw: str) -> list[str]:
    return [item.strip() for item in raw.split(",") if item.strip()]


@dataclass(frozen=True)
class Settings:
    """Backend settings resolved from environment variables."""

    #: Which provider to serve candles from. Only "sample" ships today.
    provider: str = "sample"
    #: CORS allow-list; "*" in dev.
    cors_origins: list[str] = field(default_factory=lambda: ["*"])
    app_name: str = "FoxTrader Market Data API"
    version: str = "0.1.0"

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            provider=os.environ.get("FOX_PROVIDER", "sample").strip() or "sample",
            cors_origins=_split_csv(os.environ.get("FOX_CORS_ORIGINS", "*")) or ["*"],
            app_name=os.environ.get("FOX_APP_NAME", "FoxTrader Market Data API"),
            version=os.environ.get("FOX_VERSION", "0.1.0"),
        )
