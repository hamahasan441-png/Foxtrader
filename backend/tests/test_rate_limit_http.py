"""HTTP tests for rate limiting + input validation hardening."""

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed (offline sandbox)")

from app.api import create_app  # noqa: E402
from app.config import Settings  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture()
def client() -> TestClient:
    settings = Settings(
        provider="sample",
        store_backend="memory",
        rate_limit_enabled=True,
        rate_limit_auth_per_window=2,
        rate_limit_window_seconds=60,
    )
    return TestClient(create_app(settings))


def test_auth_endpoint_returns_429_after_budget_exhausted(client: TestClient):
    body = {"email": "a@b.c", "password": "Password123", "displayName": "A"}
    # Two allowed hits...
    r1 = client.post("/api/v1/auth/register", json=body)
    assert r1.status_code in (201, 422)
    r2 = client.post("/api/v1/auth/login", json={"email": "a@b.c", "password": "Password123"})
    assert r2.status_code in (200, 401)
    # ...third is throttled.
    r3 = client.post("/api/v1/auth/login", json={"email": "a@b.c", "password": "Password123"})
    assert r3.status_code == 429
    assert r3.headers.get("Retry-After") is not None


def test_market_endpoint_is_not_rate_limited(client: TestClient):
    # Market-data routes are outside the auth/sync rate-limit scope.
    for _ in range(5):
        r = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 10})
        assert r.status_code == 200


def test_weak_password_is_rejected_with_422():
    client = TestClient(create_app(Settings(provider="sample", store_backend="memory")))
    r = client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "short", "displayName": "A"},
    )
    assert r.status_code == 422


def test_invalid_email_is_rejected_with_422():
    client = TestClient(create_app(Settings(provider="sample", store_backend="memory")))
    r = client.post(
        "/api/v1/auth/register",
        json={"email": "not-an-email", "password": "Password123", "displayName": "A"},
    )
    assert r.status_code == 422


def test_rate_limiting_can_be_disabled_via_settings():
    client = TestClient(
        create_app(
            Settings(
                provider="sample",
                store_backend="memory",
                rate_limit_enabled=False,
            )
        )
    )
    for _ in range(5):
        r = client.get("/api/v1/sync/pull", params={"since": 0})
        # No 429 even without auth — rate limiter is off.
        assert r.status_code in (401, 200)
