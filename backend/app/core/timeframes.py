"""Timeframe label parsing.

The labels here are the exact strings the FoxTrader Android client sends on the
path (`Timeframe.label` in the Kotlin domain model), so the backend speaks the
client's vocabulary 1:1. Pure module — no framework imports.
"""

from __future__ import annotations

# label -> bar length in minutes. Mirrors the client's Timeframe enum.
TIMEFRAME_MINUTES: dict[str, int] = {
    "1m": 1,
    "5m": 5,
    "15m": 15,
    "30m": 30,
    "1H": 60,
    "4H": 240,
    "1D": 1440,
    "1W": 10080,
    "1M": 43200,
}

SUPPORTED_LABELS: tuple[str, ...] = tuple(TIMEFRAME_MINUTES.keys())


class UnknownTimeframeError(ValueError):
    """Raised when a timeframe label is not one the client/back end supports."""

    def __init__(self, label: str) -> None:
        self.label = label
        super().__init__(
            f"Unsupported timeframe '{label}'. Supported: {', '.join(SUPPORTED_LABELS)}"
        )


def parse_timeframe(label: str) -> int:
    """Return the bar length in minutes for a client timeframe label.

    Raises [UnknownTimeframeError] for anything not in [TIMEFRAME_MINUTES] so the
    HTTP layer can turn it into a 400 rather than silently guessing.
    """
    try:
        return TIMEFRAME_MINUTES[label]
    except KeyError as exc:
        raise UnknownTimeframeError(label) from exc
