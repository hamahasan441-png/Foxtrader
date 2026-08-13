"""Cloud-sync routes.

Thin FastAPI adapter over the pure `app.core.sync_store.SyncStore`. The request /
response shapes match the Android client's Retrofit contract (`SyncApi.kt`) and
the domain models in `Auth.kt` — including camelCase field names:

    POST /api/v1/sync/push   {items, lastSyncTimestamp, deviceId}  (Bearer required)
    GET  /api/v1/sync/pull   ?since=<ms>&types=<csv>               (Bearer required)
                             -> {items: [...], serverTimestamp, hasMore}

Both endpoints require a valid `Authorization: Bearer <access token>` issued by
the auth endpoints.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, ConfigDict, Field

from app.core.auth import AuthService, User
from app.core.sync_store import SyncStore

router = APIRouter(prefix="/api/v1/sync", tags=["sync"])


class SyncEnvelopeIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    id: str
    type: str
    data: str
    version: int
    updated_at: int = Field(alias="updatedAt")
    device_id: str = Field(alias="deviceId")
    deleted: bool = False


class SyncPushIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    items: list[SyncEnvelopeIn]
    last_sync_timestamp: int = Field(alias="lastSyncTimestamp")
    device_id: str = Field(alias="deviceId")


class SyncEnvelopeOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    id: str
    type: str
    data: str
    version: int
    updated_at: int = Field(alias="updatedAt")
    device_id: str = Field(alias="deviceId")
    deleted: bool = False


class SyncPullOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    items: list[SyncEnvelopeOut]
    server_timestamp: int = Field(alias="serverTimestamp")
    has_more: bool = Field(alias="hasMore", default=False)


def get_current_user(request: Request, authorization: str | None = Header(default=None)) -> User:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid authorization header")
    service: AuthService = request.app.state.auth
    user = service.authenticate_access(authorization.split(" ", 1)[1].strip())
    if user is None:
        raise HTTPException(status_code=401, detail="Invalid or expired access token")
    return user


@router.post("/push", status_code=status.HTTP_204_NO_CONTENT)
def push(
    request: Request,
    body: SyncPushIn,
    user: Annotated[User, Depends(get_current_user)],
) -> None:
    store: SyncStore = request.app.state.sync_store
    # model_dump() yields snake_case keys the pure store understands.
    store.push(user.id, [item.model_dump() for item in body.items])
    return None


@router.get("/pull", response_model=SyncPullOut)
def pull(
    request: Request,
    user: Annotated[User, Depends(get_current_user)],
    since: Annotated[int, Query(ge=0, description="Epoch ms; return items updated after this")] = 0,
    types: Annotated[str | None, Query(description="Comma-separated SyncableType filter")] = None,
) -> SyncPullOut:
    store: SyncStore = request.app.state.sync_store
    type_filter = {t.strip() for t in types.split(",") if t.strip()} if types else None
    items, server_timestamp = store.pull(user.id, since, type_filter)
    return SyncPullOut(
        items=[SyncEnvelopeOut(**item) for item in items],
        server_timestamp=server_timestamp,
    )
