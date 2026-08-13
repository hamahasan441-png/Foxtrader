"""Authentication routes.

Thin FastAPI adapter over the pure `app.core.auth.AuthService`. The request /
response shapes match the Android client's Retrofit contract (`SyncApi.kt`) and
the domain models in `Auth.kt` exactly — including camelCase field names, which
the client's kotlinx.serialization expects:

    POST /api/v1/auth/register   {email, password, displayName}
    POST /api/v1/auth/login      {email, password}
    POST /api/v1/auth/refresh    {refreshToken}
    POST /api/v1/auth/logout     (Authorization: Bearer <access token>)

Login/register/refresh return an AuthResponse:
    {tokens: {accessToken, refreshToken, accessExpiresAt, refreshExpiresAt},
     user: {id, email, displayName, createdAt, deviceId}}
"""

from __future__ import annotations

import re

from fastapi import APIRouter, Header, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.core.auth import (
    AuthService,
    DuplicateEmailError,
    InvalidCredentialsError,
    InvalidTokenError,
    User,
)

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])

PASSWORD_MIN_LENGTH = 8
_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def _validate_email(value: str) -> str:
    if not _EMAIL_RE.match(value):
        raise ValueError("Invalid email address")
    return value


def _validate_password(value: str) -> str:
    if len(value) < PASSWORD_MIN_LENGTH:
        raise ValueError(f"Password must be at least {PASSWORD_MIN_LENGTH} characters")
    return value


class RegisterIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    email: str
    password: str
    display_name: str = Field(..., alias="displayName", min_length=1, max_length=60)

    _email = field_validator("email")(_validate_email)
    _password = field_validator("password")(_validate_password)


class LoginIn(BaseModel):
    email: str
    password: str

    _email = field_validator("email")(_validate_email)


class RefreshIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    refresh_token: str = Field(..., alias="refreshToken")


class TokenOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    access_token: str = Field(alias="accessToken")
    refresh_token: str = Field(alias="refreshToken")
    access_expires_at: int = Field(alias="accessExpiresAt")
    refresh_expires_at: int = Field(alias="refreshExpiresAt")


class UserOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    id: str
    email: str
    display_name: str = Field(alias="displayName")
    created_at: int = Field(alias="createdAt")
    device_id: str = Field(alias="deviceId", default="")


class AuthResponseOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    tokens: TokenOut
    user: UserOut


@router.post("/register", response_model=AuthResponseOut, status_code=status.HTTP_201_CREATED)
def register(request: Request, body: RegisterIn) -> AuthResponseOut:
    service: AuthService = request.app.state.auth
    try:
        user = service.register(body.email, body.password, body.display_name)
    except DuplicateEmailError:
        raise HTTPException(
            status_code=409,
            detail="An account with this email already exists",
        ) from None
    return _auth_response(service, user)


@router.post("/login", response_model=AuthResponseOut)
def login(request: Request, body: LoginIn) -> AuthResponseOut:
    service: AuthService = request.app.state.auth
    try:
        user = service.login(body.email, body.password)
    except InvalidCredentialsError:
        raise HTTPException(status_code=401, detail="Invalid email or password") from None
    return _auth_response(service, user)


@router.post("/refresh", response_model=AuthResponseOut)
def refresh(request: Request, body: RefreshIn) -> AuthResponseOut:
    service: AuthService = request.app.state.auth
    try:
        user, tokens = service.refresh(body.refresh_token)
    except InvalidTokenError:
        raise HTTPException(status_code=401, detail="Invalid or expired refresh token") from None
    return AuthResponseOut(tokens=TokenOut(**tokens), user=_user_out(user))


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(request: Request, authorization: str | None = Header(default=None)) -> None:
    if authorization and authorization.lower().startswith("bearer "):
        service: AuthService = request.app.state.auth
        service.revoke_access(authorization.split(" ", 1)[1].strip())
    return None


def _auth_response(service: AuthService, user: User) -> AuthResponseOut:
    tokens = service.issue_tokens(user.id)
    return AuthResponseOut(tokens=TokenOut(**tokens), user=_user_out(user))


def _user_out(user: User) -> UserOut:
    return UserOut(
        id=user.id,
        email=user.email,
        display_name=user.display_name,
        created_at=user.created_at,
        device_id=user.device_id,
    )
