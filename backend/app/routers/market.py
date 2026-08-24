"""Market-data HTTP routes.

The generic path serves the backend-selected provider. Provider-specific
AllRatesToday routes accept the encrypted-at-rest Android key in a dedicated
HTTPS header and forward it to the vendor as a Bearer token. The credential is
never placed in the URL or request logs.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Header, HTTPException, Path, Query, Request

from app.core.providers.all_rates_today import AllRatesTodayProvider
from app.core.providers.rest import MissingApiKeyError, ProviderRequestError
from app.core.service import DEFAULT_LIMIT, MAX_LIMIT, MIN_LIMIT, get_candles
from app.core.timeframes import SUPPORTED_LABELS, UnknownTimeframeError

router = APIRouter(prefix="/api/v1/market", tags=["market"])


def _all_rates_today_provider(client_api_key: str | None) -> AllRatesTodayProvider:
    """Build a request-scoped provider; blank header falls back to ART_API_KEY."""
    key = (client_api_key or "").strip() or None
    return AllRatesTodayProvider(api_key=key)


def _serve_candles(
    *,
    provider: Any,
    symbol: str,
    timeframe: str,
    limit: int,
    before: int | None,
) -> dict[str, Any]:
    try:
        return get_candles(
            symbol=symbol,
            timeframe_label=timeframe,
            limit=limit,
            before_ms=before,
            provider=provider,
        )
    except UnknownTimeframeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except MissingApiKeyError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ProviderRequestError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@router.get("/candles/{symbol}/{timeframe}")
def get_candles_route(
    request: Request,
    symbol: str = Path(..., min_length=1, max_length=32),
    timeframe: str = Path(..., description=f"One of: {', '.join(SUPPORTED_LABELS)}"),
    limit: int = Query(DEFAULT_LIMIT, ge=MIN_LIMIT, le=MAX_LIMIT),
    before: int | None = Query(None, description="Epoch millis; return bars strictly before this"),
) -> dict[str, Any]:
    return _serve_candles(
        provider=request.app.state.provider,
        symbol=symbol,
        timeframe=timeframe,
        limit=limit,
        before=before,
    )


@router.get("/providers/allratestoday/candles/{symbol}/{timeframe}")
def get_all_rates_today_candles(
    symbol: str = Path(..., min_length=6, max_length=16),
    timeframe: str = Path(..., description=f"One of: {', '.join(SUPPORTED_LABELS)}"),
    limit: int = Query(DEFAULT_LIMIT, ge=MIN_LIMIT, le=MAX_LIMIT),
    before: int | None = Query(None, description="Epoch millis; return bars strictly before this"),
    api_key: str | None = Header(None, alias="X-AllRatesToday-Key"),
) -> dict[str, Any]:
    """AllRatesToday live/historical FX bars using the app key or server fallback."""
    return _serve_candles(
        provider=_all_rates_today_provider(api_key),
        symbol=symbol,
        timeframe=timeframe,
        limit=limit,
        before=before,
    )


@router.get("/providers/allratestoday/symbols")
def get_all_rates_today_symbols(
    api_key: str | None = Header(None, alias="X-AllRatesToday-Key"),
) -> dict[str, Any]:
    """Return authenticated provider-supported currencies and ordered FX pairs."""
    provider = _all_rates_today_provider(api_key)
    try:
        currencies = provider.fetch_currencies()
    except MissingApiKeyError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ProviderRequestError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    codes = [row["code"] for row in currencies]
    pairs = [f"{base}{quote}" for base in codes for quote in codes if base != quote]
    return {
        "provider": provider.name,
        "currencies": currencies,
        "pairs": pairs,
        "currency_count": len(currencies),
        "pair_count": len(pairs),
    }
