"""Test that logout revokes both access and refresh tokens (Task 4)."""

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed")

from app.api import create_app
from app.config import Settings
from fastapi.testclient import TestClient


@pytest.fixture()
def client() -> TestClient:
    settings = Settings(provider="sample", store_backend="memory", rate_limit_enabled=False)
    return TestClient(create_app(settings))


def test_logout_revokes_refresh_token(client: TestClient):
    # Register
    r = client.post(
        "/api/v1/auth/register",
        json={
            "email": "logout@test.com",
            "password": "Password123",
            "displayName": "Logout Tester",
        },
    )
    assert r.status_code == 201, r.text
    refresh = r.json()["tokens"]["refreshToken"]

    # Refresh should work before logout
    r_refresh_ok = client.post("/api/v1/auth/refresh", json={"refreshToken": refresh})
    assert r_refresh_ok.status_code == 200, r_refresh_ok.text
    # Use the rotated refresh for logout test? Actually we want to test original refresh revocation.
    # The refresh call above rotated and revoked the original refresh.
    # So we need a fresh login to get a new pair that we will logout.
    r_login = client.post(
        "/api/v1/auth/login",
        json={"email": "logout@test.com", "password": "Password123"},
    )
    assert r_login.status_code == 200
    login_data = r_login.json()
    access2 = login_data["tokens"]["accessToken"]
    refresh2 = login_data["tokens"]["refreshToken"]

    # Logout with both access (header) and refresh (body)
    r_logout = client.post(
        "/api/v1/auth/logout",
        headers={"Authorization": f"Bearer {access2}"},
        json={"refreshToken": refresh2},
    )
    assert r_logout.status_code == 204, r_logout.text

    # Attempt to refresh with the revoked refresh token -> expect 401
    r_refresh_after = client.post("/api/v1/auth/refresh", json={"refreshToken": refresh2})
    assert r_refresh_after.status_code == 401, (
        f"Expected 401 after logout, got {r_refresh_after.status_code}: "
        f"{r_refresh_after.text}"
    )

    # Access revocation is also checked through the authenticated sync route.
    # The backend's /sync/pull requires auth; test that revoked access fails.
    # Note: auth middleware for sync uses authenticate_access, so revoked access should 401.
    r_sync = client.get(
        "/api/v1/sync/pull",
        params={"since": 0},
        headers={"Authorization": f"Bearer {access2}"},
    )
    # Access token should be revoked after logout
    assert r_sync.status_code == 401, (
        f"Expected 401 for revoked access token, got {r_sync.status_code}"
    )


def test_logout_without_body_still_revokes_access(client: TestClient):
    r = client.post(
        "/api/v1/auth/register",
        json={
            "email": "logout2@test.com",
            "password": "Password123",
            "displayName": "Logout Tester 2",
        },
    )
    assert r.status_code == 201
    access = r.json()["tokens"]["accessToken"]

    # Logout with only access token (no body) — should still work and revoke access
    r_logout = client.post(
        "/api/v1/auth/logout",
        headers={"Authorization": f"Bearer {access}"},
    )
    assert r_logout.status_code == 204

    r_sync = client.get(
        "/api/v1/sync/pull",
        params={"since": 0},
        headers={"Authorization": f"Bearer {access}"},
    )
    assert r_sync.status_code == 401
