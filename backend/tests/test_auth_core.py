"""Pure, offline tests for the auth service (no FastAPI required)."""

import pytest
from app.core.auth import (
    AuthService,
    DuplicateEmailError,
    InvalidCredentialsError,
    InvalidTokenError,
    hash_password,
    verify_password,
)


def test_register_hashes_password_and_roundtrips_login():
    service = AuthService()
    user = service.register("  Trader@Example.com ", "hunter2", "Trader")

    assert user.email == "trader@example.com"
    assert user.display_name == "Trader"
    # Plaintext password must never be stored.
    assert user.password_hash != "hunter2"
    assert "hunter2" not in user.password_hash

    logged_in = service.login("trader@example.com", "hunter2")
    assert logged_in.id == user.id


def test_wrong_password_is_rejected():
    service = AuthService()
    service.register("a@b.c", "right", "A")
    with pytest.raises(InvalidCredentialsError):
        service.login("a@b.c", "wrong")


def test_duplicate_email_is_rejected():
    service = AuthService()
    service.register("a@b.c", "p1", "A")
    with pytest.raises(DuplicateEmailError):
        service.register("A@B.C", "p2", "B")


def test_issue_tokens_and_authenticate():
    service = AuthService()
    user = service.register("a@b.c", "pw", "A")
    tokens = service.issue_tokens(user.id)

    assert tokens["access_token"]
    assert tokens["refresh_token"]
    assert tokens["access_expires_at"] > tokens["refresh_expires_at"] - tokens["access_expires_at"]

    authed = service.authenticate_access(tokens["access_token"])
    assert authed is not None
    assert authed.id == user.id


def test_refresh_rotates_pair_and_revokes_old_token():
    service = AuthService()
    user = service.register("a@b.c", "pw", "A")
    first = service.issue_tokens(user.id)

    user2, second = service.refresh(first["refresh_token"])

    assert user2.id == user.id
    assert second["access_token"] != first["access_token"]
    assert second["refresh_token"] != first["refresh_token"]
    # Old refresh token is consumed.
    with pytest.raises(InvalidTokenError):
        service.refresh(first["refresh_token"])
    # Old access token may still be valid until expiry; the new one is valid now.
    assert service.authenticate_access(second["access_token"]) is not None


def test_expired_refresh_token_is_rejected(monkeypatch):
    service = AuthService()
    user = service.register("a@b.c", "pw", "A")
    first = service.issue_tokens(user.id)

    # Freeze time far in the future so the token is past its TTL.
    monkeypatch.setattr("app.core.auth._now_ms", lambda: 10**15)
    with pytest.raises(InvalidTokenError):
        service.refresh(first["refresh_token"])


def test_password_hash_is_verifiable_by_verify_password():
    password_hash, salt = hash_password("s3cret")
    assert verify_password("s3cret", password_hash, salt) is True
    assert verify_password("wrong", password_hash, salt) is False
