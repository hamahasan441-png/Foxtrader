"""HTTP-level auth contract tests (require FastAPI + TestClient).

These assert the exact camelCase wire contract the Android client
(`SyncApi.kt` / `Auth.kt`) expects: register → login → refresh → logout, with
camelCase JSON keys, correct status codes, and token rotation.
"""

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed (offline sandbox)")

from app.api import create_app  # noqa: E402
from app.config import Settings  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture()
def client() -> TestClient:
    return TestClient(create_app(Settings(provider="sample")))


def test_register_returns_camelcase_auth_response(client: TestClient):
    r = client.post(
        "/api/v1/auth/register",
        json={"email": "trader@example.com", "password": "hunter2", "displayName": "Trader"},
    )
    assert r.status_code == 201
    body = r.json()
    assert set(body.keys()) == {"tokens", "user"}
    assert set(body["tokens"].keys()) == {
        "accessToken", "refreshToken", "accessExpiresAt", "refreshExpiresAt",
    }
    assert body["user"]["email"] == "trader@example.com"
    assert body["user"]["displayName"] == "Trader"
    assert body["tokens"]["accessExpiresAt"] < body["tokens"]["refreshExpiresAt"]


def test_duplicate_registration_is_409(client: TestClient):
    client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "pw", "displayName": "A"},
    )
    r = client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "pw", "displayName": "A"},
    )
    assert r.status_code == 409


def test_login_returns_tokens_and_user(client: TestClient):
    client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "pw", "displayName": "A"},
    )
    r = client.post("/api/v1/auth/login", json={"email": "a@b.c", "password": "pw"})
    assert r.status_code == 200
    body = r.json()
    assert body["user"]["id"]
    assert body["tokens"]["accessToken"]


def test_login_wrong_password_is_401(client: TestClient):
    client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "pw", "displayName": "A"},
    )
    r = client.post("/api/v1/auth/login", json={"email": "a@b.c", "password": "wrong"})
    assert r.status_code == 401


def test_refresh_rotates_tokens(client: TestClient):
    reg = client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "pw", "displayName": "A"},
    ).json()
    old_refresh = reg["tokens"]["refreshToken"]

    r = client.post("/api/v1/auth/refresh", json={"refreshToken": old_refresh})
    assert r.status_code == 200
    body = r.json()
    assert body["tokens"]["accessToken"] != reg["tokens"]["accessToken"]
    assert body["tokens"]["refreshToken"] != old_refresh

    # Old refresh token is now revoked.
    resp = client.post("/api/v1/auth/refresh", json={"refreshToken": old_refresh})
    assert resp.status_code == 401


def test_logout_revokes_access_token(client: TestClient):
    reg = client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "pw", "displayName": "A"},
    ).json()
    access = reg["tokens"]["accessToken"]

    r = client.post("/api/v1/auth/logout", headers={"Authorization": f"Bearer {access}"})
    assert r.status_code == 204
