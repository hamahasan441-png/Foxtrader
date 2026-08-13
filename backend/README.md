# FoxTrader Market-Data Backend

A small **FastAPI** service that fulfils the exact market-data contract the
FoxTrader Android client already expects. The client's `MarketApi` calls a
FoxTrader backend for non-crypto candles (forex / stocks / indices); until now
that backend did not exist, so those requests fell back to clearly-labelled
synthetic data. This service is the start of that backend.

> Scope note: this is an intentional first slice. It currently serves candles
> from an **offline, deterministic `sample` provider** (no API key, no network),
> so the endpoint works end-to-end today. Real upstreams (Twelve Data, Polygon,
> OANDA, …) plug into the same provider seam without touching the router.

## API

Base URL in the client defaults to `http://10.0.2.2:8000/` (Android emulator →
host loopback).

### `GET /api/v1/market/candles/{symbol}/{timeframe}`

Query params: `limit` (1–5000, default 500), `before` (epoch millis; return bars
strictly before this, for paging older history).

`timeframe` uses the client's labels: `1m 5m 15m 30m 1H 4H 1D 1W 1M`.

Response — matches the client's `CandlesResponse` / `CandleDto`:

```json
{
  "symbol": "EURUSD",
  "timeframe": "1H",
  "candles": [
    {"timestamp": 1700000000000, "open": 1.2, "high": 1.3, "low": 1.1, "close": 1.25, "volume": 1234.5}
  ]
}
```

Unsupported timeframe → `400`; `limit` outside 1–5000 → `422`.

### `GET /health`

```json
{"status": "ok", "service": "...", "version": "0.1.0", "provider": "sample", "store": "sqlite"}
```

## Hardening

- **Auth rate limiting** — the auth endpoints (`/api/v1/auth/*`) and
  `POST /api/v1/sync/push` are throttled per client IP (default 20/min) to blunt
  brute-force attempts; excess requests get `429` with `Retry-After`.
  Configure with `FOX_RATE_LIMIT_ENABLED`, `FOX_RATE_LIMIT_AUTH_PER_WINDOW`,
  `FOX_RATE_LIMIT_WINDOW_SECONDS`.
- **Structured request logging** — every request logs a single line with
  `method`, `path`, `status`, `duration_ms`, `client`. Tokens/bodies are never
  logged.
- **Input validation** — registration requires a valid email and a password of
  at least 8 characters (`422` otherwise).

## Architecture

```
app/
  api.py                 FastAPI app factory (health + routers + middleware)
  config.py              env-driven Settings (stdlib only)
  middleware.py          structured request logging + auth rate limiting
  logging_setup.py       console logger with structured fields
  routers/market.py      HTTP adapter over the pure service
  routers/auth.py        register/login/refresh/logout (camelCase contract)
  routers/sync.py        push/pull (Bearer-gated)
  core/                  PURE python — no FastAPI/pydantic imports
    timeframes.py        client label -> minutes
    candles.py           Candle value object + client-shaped response
    service.py           get_candles(): validate, clamp, delegate, assemble
    auth.py              password hashing + token lifecycle
    sync_store.py        last-write-wins sync merge
    persistence.py       pluggable AuthStore/SyncStore (SQLite + memory)
    ratelimit.py         fixed-window rate limiter
    providers/
      base.py            MarketDataProvider Protocol (the seam)
      sample.py          deterministic synthetic provider (default)
      registry.py        name -> provider
tests/                   pytest (core is fully covered offline)
```

The **core is framework-free and unit-tested offline**; the FastAPI layer is a
thin adapter. `tests/test_api_contract.py` exercises the real HTTP surface via
`TestClient` and is skipped automatically when FastAPI isn't installed.

## Auth & Cloud Sync

This backend implements the full auth + sync contract the Android client
(`SyncApi.kt` / `Auth.kt`) expects — previously client-contract-only, now live:

- `POST /api/v1/auth/register`   `{email, password, displayName}` → `AuthResponse`
- `POST /api/v1/auth/login`      `{email, password}` → `AuthResponse`
- `POST /api/v1/auth/refresh`    `{refreshToken}` → `AuthResponse`
- `POST /api/v1/auth/logout`     (Bearer) → `204`
- `POST /api/v1/sync/push`       `{items, lastSyncTimestamp, deviceId}` (Bearer) → `204`
- `GET  /api/v1/sync/pull`       `?since=&types=` (Bearer) → `SyncPullResponse`

`AuthResponse` / `SyncPullResponse` use the exact **camelCase** field names the
client's kotlinx.serialization expects (`tokens.{accessToken, refreshToken,
accessExpiresAt, refreshExpiresAt}`, `user.{id, email, displayName, createdAt,
deviceId}`, etc.).

### Storage model (durable by default)

Auth accounts, tokens, and sync items are persisted to **SQLite** by default
(`FOX_STORE=sqlite`, file at `FOX_DB_PATH`, WAL mode) — state survives process
restarts, so a restarted server still recognises logged-in users and their
synced data. For stateless/ephemeral deployments set `FOX_STORE=memory`
(the in-memory backend is also what unit tests use).

Persistence sits behind a pluggable store interface (`app.core.persistence`
`AuthStore` / `SyncStore`), so a future PostgreSQL/Redis backend is a one-line
swap in `app/api.py` without touching the routers.

- Passwords are hashed with PBKDF2-HMAC-SHA256 + per-user salt.
- Access tokens are opaque, short-lived (15 min); refresh tokens are rotated
  on every refresh.
- Sync merges last-write-wins on `updatedAt` (matching the client's merge).

## Run

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
uvicorn app.api:app --reload --port 8000
# GET http://localhost:8000/api/v1/market/candles/EURUSD/1H?limit=100
```

Or with Docker:

```bash
docker build -t foxtrader-backend backend/
docker run -p 8000:8000 foxtrader-backend
```

## Test

```bash
cd backend
pip install -r requirements-dev.txt
pytest
```

The pure-core suite runs with no third-party dependencies (only `pytest`); the
HTTP contract suite additionally needs `fastapi` + `httpx`.

## Adding a real provider

1. Implement `MarketDataProvider` (see `core/providers/base.py`) in
   `core/providers/<name>.py`, mapping the upstream response into `Candle`s
   (ascending, valid OHLC, `before_ms` honoured).
2. Register it in `core/providers/registry.py`.
3. Select it with `FOX_PROVIDER=<name>` (and its API key via env).

No router or client change is required — the contract stays identical.
