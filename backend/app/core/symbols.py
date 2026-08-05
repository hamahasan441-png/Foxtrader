"""Symbol normalization helpers (pure).

Different upstreams spell instruments differently. This mirrors the client's
Twelve Data adapter: a 6-letter all-alpha symbol whose halves are both known
currencies (e.g. "EURUSD") is a forex pair and becomes "EUR/USD"; everything
else (equities like "AAPL", crypto like "BTCUSD") passes through unchanged.
"""

from __future__ import annotations

# ISO-4217 majors/crosses the app deals with. Kept in sync with the client.
COMMON_CURRENCIES: frozenset[str] = frozenset(
    {"EUR", "USD", "GBP", "JPY", "AUD", "NZD", "CAD", "CHF", "CNH", "SEK", "NOK"}
)


def looks_like_forex(symbol: str) -> bool:
    s = symbol.upper()
    return (
        len(s) == 6
        and s.isalpha()
        and s[:3] in COMMON_CURRENCIES
        and s[3:] in COMMON_CURRENCIES
    )


def to_slash_pair(symbol: str) -> str:
    """"EURUSD" -> "EUR/USD" for forex; otherwise the symbol unchanged (upper-cased)."""
    s = symbol.upper()
    return f"{s[:3]}/{s[3:]}" if looks_like_forex(s) else s
