"""Market-data HTTP routes.

Thin FastAPI adapter over the pure `service.get_candles`. The path matches the
Android client's Retrofit contract exactly:

    GET /api/v1/market/candles/{symbol}/{timeframe}?limit=500&before=<epoch_ms>
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Path, Query, Request

from app.core.service import DEFAULT_LIMIT, MAX_LIMIT, MIN_LIMIT, get_candles
from app.core.timeframes import SUPPORTED_LABELS, UnknownTimeframeError

router = APIRouter(prefix="/api/v1/market", tags=["market"])


@router.get("/candles/{symbol}/{timeframe}")
def get_candles_route(
    request: Request,
    symbol: str = Path(..., min_length=1, max_length=32),
    timeframe: str = Path(..., description=f"One of: {', '.join(SUPPORTED_LABELS)}"),
    limit: int = Query(DEFAULT_LIMIT, ge=MIN_LIMIT, le=MAX_LIMIT),
    before: int | None = Query(None, description="Epoch millis; return bars strictly before this"),
) -> dict[str, Any]:
    provider = request.app.state.provider
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
