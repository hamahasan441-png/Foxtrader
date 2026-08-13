"""Pure auth service: password hashing + opaque-token lifecycle.

Framework-free (no FastAPI/pydantic) so it can be unit-tested offline, matching
the existing `app.core` convention. Tokens are random opaque strings rather than
JWT — the Android client (`AuthRepositoryImpl` / `AuthInterceptor`) treats the
access/refresh tokens as opaque and only relies on the returned expiry
timestamps, so this faithfully implements the wire contract.

Persistence is delegated to an [AuthStore] (see `app.core.persistence`): the
default SQLite backend is durable across restarts; an in-memory backend is used
for tests and stateless deployments.

SECURITY NOTES:
- Passwords are hashed with PBKDF2-HMAC-SHA256 and a per-user random salt.
- Access tokens are short-lived (15 min); refresh tokens last 7 days and are
  rotated on every refresh.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from dataclasses import dataclass

from app.core.persistence import AuthStore, MemoryStore, StoredUser

#: Access-token lifetime in seconds (mirrors the client's 15-minute default).
ACCESS_TOKEN_TTL_SECONDS = 15 * 60
#: Refresh-token lifetime in seconds (mirrors the client's 7-day default).
REFRESH_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60

_PBKDF2_ITERATIONS = 100_000
_ACCESS_TOKEN_BYTES = 32
_REFRESH_TOKEN_BYTES = 48


class AuthError(Exception):
    """Base class for auth failures."""


class DuplicateEmailError(AuthError):
    """Registration attempted with an email that already exists."""


class InvalidCredentialsError(AuthError):
    """Login failed — unknown email or wrong password."""


class InvalidTokenError(AuthError):
    """A token is missing, invalid, expired, or already revoked."""


@dataclass(frozen=True)
class User:
    """A registered account."""

    id: str
    email: str
    password_hash: str
    password_salt: str
    display_name: str
    created_at: int
    device_id: str = ""


def hash_password(password: str) -> tuple[str, str]:
    """Return (password_hash, salt) for the given plaintext password."""
    salt = secrets.token_hex(16)
    return _derive(password, salt), salt


def verify_password(password: str, password_hash: str, salt: str) -> bool:
    """Constant-time comparison of a plaintext password against a stored hash."""
    return hmac.compare_digest(_derive(password, salt), password_hash)


def _derive(password: str, salt: str) -> str:
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        bytes.fromhex(salt),
        _PBKDF2_ITERATIONS,
    )
    return digest.hex()


def _to_domain_user(stored: StoredUser) -> User:
    return User(
        id=stored.id,
        email=stored.email,
        password_hash=stored.password_hash,
        password_salt=stored.password_salt,
        display_name=stored.display_name,
        created_at=stored.created_at,
        device_id=stored.device_id,
    )


def _to_stored_user(user: User) -> StoredUser:
    return StoredUser(
        id=user.id,
        email=user.email,
        password_hash=user.password_hash,
        password_salt=user.password_salt,
        display_name=user.display_name,
        created_at=user.created_at,
        device_id=user.device_id,
    )


class AuthService:
    """User + token lifecycle backed by an [AuthStore]."""

    def __init__(self, store: AuthStore | None = None) -> None:
        self._store: AuthStore = store or MemoryStore()

    # ------------------------------------------------------------------
    # Registration & login
    # ------------------------------------------------------------------

    def register(self, email: str, password: str, display_name: str) -> User:
        email = email.strip().lower()
        if not email or not password or not display_name:
            raise InvalidCredentialsError("Email, password and display name are required")
        if self._store.user_by_email(email) is not None:
            raise DuplicateEmailError(email)

        password_hash, salt = hash_password(password)
        user = User(
            id=secrets.token_hex(8),
            email=email,
            password_hash=password_hash,
            password_salt=salt,
            display_name=display_name.strip(),
            created_at=_now_ms(),
        )
        self._store.save_user(_to_stored_user(user))
        return user

    def login(self, email: str, password: str) -> User:
        stored = self._store.user_by_email(email.strip().lower())
        if stored is None or not verify_password(
            password, stored.password_hash, stored.password_salt
        ):
            raise InvalidCredentialsError("Invalid email or password")
        return _to_domain_user(stored)

    # ------------------------------------------------------------------
    # Token lifecycle
    # ------------------------------------------------------------------

    def issue_tokens(self, user_id: str) -> dict[str, int | str]:
        """Create a fresh (access, refresh) token pair for a user."""
        access = secrets.token_urlsafe(_ACCESS_TOKEN_BYTES)
        refresh = secrets.token_urlsafe(_REFRESH_TOKEN_BYTES)
        now = _now_ms()
        access_exp = now + ACCESS_TOKEN_TTL_SECONDS * 1000
        refresh_exp = now + REFRESH_TOKEN_TTL_SECONDS * 1000
        self._store.save_access(access, user_id, access_exp)
        self._store.save_refresh(refresh, user_id, refresh_exp)
        return {
            "access_token": access,
            "refresh_token": refresh,
            "access_expires_at": access_exp,
            "refresh_expires_at": refresh_exp,
        }

    def refresh(self, refresh_token: str) -> tuple[User, dict[str, int | str]]:
        """Validate a refresh token and rotate the whole pair.

        Returns (user, new token dict). The consumed refresh token is revoked.
        """
        entry = self._store.refresh_entry(refresh_token)
        if entry is None:
            raise InvalidTokenError("Invalid or expired refresh token")
        user_id, expires_at = entry
        if expires_at <= _now_ms():
            self._store.delete_refresh(refresh_token)
            raise InvalidTokenError("Refresh token has expired")
        self._store.delete_refresh(refresh_token)

        stored = self._store.user_by_id(user_id)
        if stored is None:
            raise InvalidTokenError("User no longer exists")
        return _to_domain_user(stored), self.issue_tokens(user_id)

    def authenticate_access(self, access_token: str) -> User | None:
        """Resolve an access token to a user, or None if invalid/expired."""
        entry = self._store.access_entry(access_token)
        if entry is None:
            return None
        user_id, expires_at = entry
        if expires_at <= _now_ms():
            self._store.delete_access(access_token)
            return None
        stored = self._store.user_by_id(user_id)
        return _to_domain_user(stored) if stored else None

    def revoke_access(self, access_token: str) -> None:
        self._store.delete_access(access_token)


def _now_ms() -> int:
    return int(time.time() * 1000)
