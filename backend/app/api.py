"""FastAPI application factory.

Wires the health check and the market router, selects the provider from
settings, and enables permissive CORS for local development. The heavy lifting
lives in `app.core` (pure, framework-free); this module is intentionally thin.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import Settings
from app.core.auth import AuthService
from app.core.providers.registry import build_provider
from app.core.sync_store import SyncStore

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    provider = build_provider(settings.provider)

    app = FastAPI(title=settings.app_name, version=settings.version)
    app.state.settings = settings
    app.state.provider = provider

    # Auth + cloud-sync stores. In-memory (not durable across restarts) — the
    # FastAPI layer and the client contract work end-to-end; durable persistence
    # (PostgreSQL/Redis) is the documented roadmap item.
    app.state.auth = AuthService()
    app.state.sync_store = SyncStore()

    # A wildcard allow-origin ("*") can never carry credentials: browsers
    # reject a credentialed response whose `Access-Control-Allow-Origin` is
    # "*" (they require an explicit origin echo). Enabling both would either
    # break credentialed cross-origin calls at the browser or — if a middleware
    # naively echoes the origin — allow any site to read authenticated
    # responses. We therefore force-disable credentials whenever the origin
    # list contains a wildcard, and log a hard warning so the misconfiguration
    # is never silent.
    origins = settings.cors_origins
    wildcard = "*" in origins
    allow_credentials = settings.allow_credentials and not wildcard
    if settings.allow_credentials and wildcard:
        logger.warning(
            "CORS: allow_credentials=True is incompatible with a wildcard "
            "allow_origin; credentials have been disabled. Set FOX_CORS_ORIGINS "
            "to an explicit origin list to allow credentialed requests."
        )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_credentials=allow_credentials,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {
            "status": "ok",
            "service": settings.app_name,
            "version": settings.version,
            "provider": provider.name,
        }

    # Import here so `app.core` stays importable without FastAPI installed
    # (the pure core and its tests never import this module).
    from app.routers.auth import router as auth_router
    from app.routers.market import router as market_router
    from app.routers.sync import router as sync_router

    app.include_router(market_router)
    app.include_router(auth_router)
    app.include_router(sync_router)
    return app


app = create_app()
