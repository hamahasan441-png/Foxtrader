"""Tests for trusted-proxy X-Forwarded-For handling in middleware.client_ip.

Proves the spoof fix:
(a) an untrusted peer's spoofed header is ignored,
(b) a trusted peer's header is honored.

Uses both the pure client_ip helper and the HTTP rate-limit path.
"""

import pytest

pytest.importorskip("fastapi", reason="FastAPI not installed (offline sandbox)")

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from app.api import create_app
from app.config import Settings
from app.middleware import client_ip


class _FakeClient:
    def __init__(self, host: str):
        self.host = host


class _FakeRequest:
    """Minimal Starlette-like request for direct client_ip unit testing."""

    def __init__(self, client_host: str, xff: str | None, trusted_proxies=None):
        from types import SimpleNamespace

        self.client = _FakeClient(client_host)
        self.headers = {}
        if xff is not None:
            self.headers["x-forwarded-for"] = xff
        # mimic request.app.state.settings.trusted_proxies via SimpleNamespace
        self.app = SimpleNamespace(
            state=SimpleNamespace(
                settings=SimpleNamespace(trusted_proxies=trusted_proxies or [])
            )
        )


def test_client_ip_ignores_spoofed_xff_when_peer_untrusted():
    # Peer is 10.0.0.1, not in trusted list, even though XFF says 1.2.3.4
    req = _FakeRequest(client_host="10.0.0.1", xff="1.2.3.4", trusted_proxies=[])
    assert client_ip(req) == "10.0.0.1"
    # Also with explicit trusted_proxies arg empty
    assert client_ip(req, trusted_proxies=[]) == "10.0.0.1"
    # Multiple XFF entries should still be ignored when untrusted
    req2 = _FakeRequest(client_host="10.0.0.1", xff="5.6.7.8, 10.0.0.1", trusted_proxies=[])
    assert client_ip(req2) == "10.0.0.1"


def test_client_ip_honors_xff_when_peer_trusted():
    # Peer is a known proxy; its XFF should be trusted, first entry wins
    req = _FakeRequest(
        client_host="10.0.0.1", xff="1.2.3.4", trusted_proxies=["10.0.0.1"]
    )
    assert client_ip(req) == "1.2.3.4"
    # Explicit arg as well
    assert client_ip(req, trusted_proxies=["10.0.0.1"]) == "1.2.3.4"

    # When XFF contains "client, proxy1, proxy2", first is original client per std
    req_multi = _FakeRequest(
        client_host="10.0.0.1",
        xff="9.9.9.9, 10.0.0.2, 10.0.0.1",
        trusted_proxies=["10.0.0.1"],
    )
    assert client_ip(req_multi) == "9.9.9.9"


def test_rate_limiter_does_not_allow_spoofed_bucket_when_untrusted():
    """Untrusted peer: spoofed XFF must NOT give a fresh bucket — 429 should still fire."""
    settings = Settings(
        provider="sample",
        store_backend="memory",
        rate_limit_enabled=True,
        rate_limit_auth_per_window=2,
        rate_limit_window_seconds=60,
        trusted_proxies=[],  # no trusted proxies
    )
    client = TestClient(create_app(settings))

    body = {"email": "a@b.c", "password": "Password123", "displayName": "A"}
    # First two requests from testclient IP consume budget
    client.post("/api/v1/auth/login", json={"email": "a@b.c", "password": "WrongPass1"})
    client.post("/api/v1/auth/login", json={"email": "a@b.c", "password": "WrongPass2"})
    # Third request WITH spoofed XFF should still be 429 if untrusted
    r3 = client.post(
        "/api/v1/auth/login",
        json={"email": "a@b.c", "password": "WrongPass3"},
        headers={"x-forwarded-for": "9.9.9.9"},
    )
    assert r3.status_code == 429, "Spoofed XFF bypassed rate limiter when it should not"


def test_rate_limiter_honors_xff_when_proxy_trusted():
    """Trusted proxy: XFF should determine the client IP bucket."""
    settings = Settings(
        provider="sample",
        store_backend="memory",
        rate_limit_enabled=True,
        rate_limit_auth_per_window=1,
        rate_limit_window_seconds=60,
        trusted_proxies=["testclient"],  # TestClient's host is \"testclient\"
    )
    client = TestClient(create_app(settings))

    # First request as real_ip=1.1.1.1 consumes its bucket
    r1 = client.post(
        "/api/v1/auth/login",
        json={"email": "a@b.c", "password": "WrongPass1"},
        headers={"x-forwarded-for": "1.1.1.1"},
    )
    assert r1.status_code in (200, 401)

    # Second request same XFF should be throttled (same IP bucket)
    r2 = client.post(
        "/api/v1/auth/login",
        json={"email": "a@b.c", "password": "WrongPass2"},
        headers={"x-forwarded-for": "1.1.1.1"},
    )
    assert r2.status_code == 429

    # Different XFF should have fresh bucket
    r3 = client.post(
        "/api/v1/auth/login",
        json={"email": "a@b.c", "password": "WrongPass3"},
        headers={"x-forwarded-for": "2.2.2.2"},
    )
    assert r3.status_code in (200, 401)
