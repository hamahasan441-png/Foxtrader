"""HTTP-level contract tests.

These require FastAPI + starlette's TestClient. In an offline sandbox those
aren't installed, so the whole module is skipped; it runs in CI / any
environment where `pip install -r requirements.txt` has succeeded, giving real
end-to-end coverage of the router, status codes, and JSON contract.
"""

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed (offline sandbox)")

from app.api import create_app  # noqa: E402
from app.config import Settings  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture()
def client() -> TestClient:
    return TestClient(create_app(Settings(provider="sample")))


def test_health(client: TestClient):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["provider"] == "sample"


def test_candles_endpoint_matches_client_contract(client: TestClient):
    r = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 100})
    assert r.status_code == 200
    body = r.json()
    assert body["symbol"] == "EURUSD"
    assert body["timeframe"] == "1H"
    assert body["provider"] == "sample"
    assert body["source"] == "synthetic"
    assert len(body["candles"]) == 100
    first = body["candles"][0]
    assert set(first.keys()) == {"timestamp", "open", "high", "low", "close", "volume"}


def test_before_paging(client: TestClient):
    before = 1_700_000_000_000
    r = client.get(
        "/api/v1/market/candles/EURUSD/1H",
        params={"limit": 50, "before": before},
    )
    assert r.status_code == 200
    assert all(c["timestamp"] < before for c in r.json()["candles"])


def test_unknown_timeframe_is_400(client: TestClient):
    r = client.get("/api/v1/market/candles/EURUSD/3H", params={"limit": 10})
    assert r.status_code == 400


def test_limit_out_of_range_is_422(client: TestClient):
    # FastAPI query validation rejects limit above the declared maximum.
    r = client.get("/api/v1/market/candles/EURUSD/1H", params={"limit": 999_999})
    assert r.status_code == 422
