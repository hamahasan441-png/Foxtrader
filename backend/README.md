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
{"status": "ok", "service": "...", "version": "0.1.0", "provider": "sample"}
```

## Architecture

```
app/
  api.py                 FastAPI app factory (thin: health + router + CORS)
  config.py              env-driven Settings (stdlib only)
  routers/market.py      HTTP adapter over the pure service
  core/                  PURE python — no FastAPI/pydantic imports
    timeframes.py        client label -> minutes
    candles.py           Candle value object + client-shaped response
    service.py           get_candles(): validate, clamp, delegate, assemble
    providers/
      base.py            MarketDataProvider Protocol (the seam)
      sample.py          deterministic synthetic provider (default)
      registry.py        name -> provider
tests/                   pytest (core is fully covered offline)
```

The **core is framework-free and unit-tested offline**; the FastAPI layer is a
thin adapter. `tests/test_api_contract.py` exercises the real HTTP surface via
`TestClient` and is skipped automatically when FastAPI isn't installed.

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

Or Docker Compose (from `backend/`):

```bash
docker compose up --build
# real data: FOX_PROVIDER=twelvedata FOX_TWELVEDATA_API_KEY=xxxx docker compose up --build
```

## CI

`.github/workflows/backend.yml` runs `ruff` + the full `pytest` suite (including
the FastAPI `TestClient` HTTP-contract tests) on every push/PR that touches
`backend/`. It is independent of the Android Gradle workflow.

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
