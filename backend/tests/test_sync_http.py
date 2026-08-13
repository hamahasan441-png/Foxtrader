"""HTTP-level sync contract tests (require FastAPI + TestClient).

Assert the exact camelCase wire contract the Android client
(`SyncApi.kt` / `Auth.kt`) expects for push/pull, plus that both endpoints
require a valid Bearer access token.
"""

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed (offline sandbox)")

from app.api import create_app  # noqa: E402
from app.config import Settings  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture()
def client() -> TestClient:
    return TestClient(create_app(Settings(provider="sample")))


def _register(client: TestClient) -> str:
    r = client.post(
        "/api/v1/auth/register",
        json={"email": "a@b.c", "password": "pw", "displayName": "A"},
    )
    return r.json()["tokens"]["accessToken"]


def _envelope(uid, type_, data, updated_at, deleted=False):
    return {
        "id": uid,
        "type": type_,
        "data": data,
        "version": 1,
        "updatedAt": updated_at,
        "deviceId": "dev-1",
        "deleted": deleted,
    }


def test_push_requires_bearer_token(client: TestClient):
    r = client.post(
        "/api/v1/sync/push",
        json={"items": [], "lastSyncTimestamp": 0, "deviceId": "dev-1"},
    )
    assert r.status_code == 401


def test_pull_requires_bearer_token(client: TestClient):
    assert client.get("/api/v1/sync/pull", params={"since": 0}).status_code == 401


def test_push_then_pull_roundtrip(client: TestClient):
    access = _register(client)
    headers = {"Authorization": f"Bearer {access}"}

    r = client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={
            "items": [_envelope("j1", "JOURNAL", '{"x":1}', 100)],
            "lastSyncTimestamp": 0,
            "deviceId": "dev-1",
        },
    )
    assert r.status_code == 204

    pull = client.get("/api/v1/sync/pull", headers=headers, params={"since": 0})
    assert pull.status_code == 200
    body = pull.json()
    assert set(body.keys()) == {"items", "serverTimestamp", "hasMore"}
    assert len(body["items"]) == 1
    item = body["items"][0]
    assert set(item.keys()) == {
        "id", "type", "data", "version", "updatedAt", "deviceId", "deleted",
    }
    assert item["id"] == "j1"
    assert item["type"] == "JOURNAL"
    assert body["serverTimestamp"] > 0


def test_pull_filters_by_type_and_since(client: TestClient):
    access = _register(client)
    headers = {"Authorization": f"Bearer {access}"}
    client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={
            "items": [
                _envelope("a", "JOURNAL", "{}", 100),
                _envelope("b", "DRAWINGS", "{}", 200),
            ],
            "lastSyncTimestamp": 0,
            "deviceId": "dev-1",
        },
    )

    only_drawings = client.get(
        "/api/v1/sync/pull", headers=headers, params={"since": 0, "types": "DRAWINGS"}
    ).json()
    assert [i["id"] for i in only_drawings["items"]] == ["b"]

    since_b = client.get("/api/v1/sync/pull", headers=headers, params={"since": 100}).json()
    assert [i["id"] for i in since_b["items"]] == ["b"]
