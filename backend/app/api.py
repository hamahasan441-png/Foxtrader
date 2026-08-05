"""FastAPI application factory.

Wires the health check and the market router, selects the provider from
settings, and enables permissive CORS for local development. The heavy lifting
lives in `app.core` (pure, framework-free); this module is intentionally thin.
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import Settings
from app.core.providers.registry import build_provider


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    provider = build_provider(settings.provider)

    app = FastAPI(title=settings.app_name, version=settings.version)
    app.state.settings = settings
    app.state.provider = provider

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
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
    from app.routers.market import router as market_router

    app.include_router(market_router)
    return app


app = create_app()
