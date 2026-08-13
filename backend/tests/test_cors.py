"""CORS hardening tests.

Verify the dangerous wildcard-origin + credentials combination can never reach
the running middleware: when a wildcard origin is configured with credentials
enabled, `create_app` must disable credentials (browsers reject credentialed
requests against "*") and emit a hard warning rather than silently shipping the
unsafe combo.
"""

import logging

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed (offline sandbox)")

from app.api import create_app  # noqa: E402
from app.config import Settings  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402


def _cors_middleware_kwargs(app):
    """Extract the CORSMiddleware kwargs installed on the app, if present."""
    for middleware in app.user_middleware:
        if middleware.cls is CORSMiddleware:
            return middleware.kwargs
    return None


def test_wildcard_origin_with_credentials_never_reaches_the_middleware(caplog):
    settings = Settings(cors_origins=["*"], allow_credentials=True)

    with caplog.at_level(logging.WARNING, logger="app.api"):
        app = create_app(settings)

    # The middleware must never be configured with the unsafe combo.
    kwargs = _cors_middleware_kwargs(app)
    assert kwargs is not None
    assert kwargs["allow_origins"] == ["*"]
    assert kwargs["allow_credentials"] is False

    # And it must not be silent about it.
    assert any("allow_credentials" in record.getMessage() for record in caplog.records)


def test_explicit_origin_allows_credentials():
    settings = Settings(cors_origins=["https://app.foxtrader.io"], allow_credentials=True)

    app = create_app(settings)

    kwargs = _cors_middleware_kwargs(app)
    assert kwargs is not None
    assert "https://app.foxtrader.io" in kwargs["allow_origins"]
    assert kwargs["allow_credentials"] is True


def test_credentials_can_be_explicitly_disabled():
    settings = Settings(cors_origins=["https://app.foxtrader.io"], allow_credentials=False)

    app = create_app(settings)

    kwargs = _cors_middleware_kwargs(app)
    assert kwargs is not None
    assert kwargs["allow_credentials"] is False
