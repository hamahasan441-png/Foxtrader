# FoxTrader Phase 2 — Live Data + Chart Signal Wiring

## Scope completed in this phase

Phase 2 focuses on the runtime path that was blocking the chart in the supplied screenshot: real market data routing, live/near-live updates, honest freshness state, and chart-visible strategy signals without re-introducing default clutter.

### 1. Symbol-aware provider router

Added `MarketProviderRouter` + `MarketSymbolClassifier`.

- Crypto symbols automatically fall back to Binance public data when the selected provider is incompatible.
- FX / metals / supported index symbols automatically fall back to Dukascopy.
- A compatible explicitly selected provider still wins when its credentials are ready.
- MT4 live readiness checks the MetaApi token + account id instead of incorrectly looking for a generic provider API key.
- Routing is resolved per symbol rather than applying one global provider blindly to every asset class.

### 2. Dukascopy FX market-data path fixed

`DukascopyDataSource` now receives the dedicated `@PublicMarketDataClient`.

This is important because the unqualified OkHttp client is the FoxTrader-backend client and carries the dynamic backend URL/auth interceptors. Public Dukascopy requests must not be rewritten to the backend and must not receive FoxTrader auth headers.

Historical `.bi5` hours are now downloaded in bounded parallel batches instead of one-by-one. The small live polling request uses a smaller batch so it does not repeatedly download unnecessary hours.

### 3. Free near-real-time FX transport

Added `DukascopyPollingWebSocket`, implementing the existing `MarketWebSocket` contract.

- M1 polling: 10 s
- M5/M15: 15 s
- M30/H1: 30 s
- H4: 60 s
- D1/W1/MN: 120 s

Only a changed latest candle is emitted. Old data does not remain labelled LIVE: the transport marks itself STALE when the newest bar is beyond the timeframe freshness window.

Dukascopy polling is not represented as a native tick WebSocket. The UI reports freshness independently so delayed/archive data is never intentionally presented as current merely because the network request succeeded.

### 4. Per-symbol live socket routing

`ProviderMarketWebSocket` was changed from a single globally selected socket to a subscription router.

Each `(symbol,timeframe)` is assigned its effective provider/socket. Binance, Bybit, Polygon, MT4 and Dukascopy can therefore be routed correctly without one incompatible global selection preventing live mode.

Provider preference changes re-route existing subscriptions.

### 5. Historical repository routing

`MarketRepositoryImpl` now resolves the effective historical provider through the same market-aware router for both initial and older-history requests.

OKX and KuCoin older-history branches are also wired instead of falling through to an unrelated default path.

The Phase 1 synthetic/real separation remains intact: a successful real fetch clears synthetic rows before real candles are written. Phase 2 also clears a synthetic seed before persisting the first real live tick, so a live update cannot coexist with a generated random-walk series and recreate the apparent duplicate candle track.

### 6. Honest LIVE / DELAYED / CACHED / SIMULATED state

Added `MarketDataFreshness` and `MarketDataFreshnessResolver`.

The chart no longer equates “provider selected” or “socket connected” with fresh prices. It combines:

- candle provenance,
- connection state,
- timeframe,
- newest candle timestamp.

The top status and OHLC badge can now distinguish `LIVE`, `DELAYED`, `CACHED`, and `SIMULATED`.

### 7. Live mode automatically becomes usable where a free route exists

`ChartViewModel` now computes live availability for the active symbol. Compatible free routes can enable automatically; a manual user OFF action is respected until the user enables live again.

Changing symbol/timeframe resets stale freshness metadata and re-subscribes the live route when enabled.

### 8. Strategy / indicator signal rendering

The existing strategy pipeline was retained and connected to a clearer chart surface:

`Indicator panel -> LiveStrategyEngine / LIT X / TradePro / SMT -> SignalComputer -> CandleChart`.

Unified chart signals now render as:

- green upward arrows for bullish signals,
- amber/yellow downward arrows for bearish signals,
- a confirmation dot for the current live signal,
- a source letter (`L`, `T`, `S`, `X`).

To keep the chart clean, historical arrows are hidden by default. Turning on **Signal History** exposes historical signals; current/live confirmations remain visible without enabling history.

### 9. Tests / checks added

Added tests for:

- market symbol classification,
- market-data freshness classification.

Static checks performed in the delivery environment:

- Android resource XML parsing: PASS
- market classifier standalone Kotlin compile/logic check: PASS
- freshness resolver standalone Kotlin compile/logic check: PASS
- Dukascopy polling transport isolated Kotlin compile check: PASS
- provider WebSocket router isolated Kotlin compile check: PASS
- ZIP integrity will be checked before delivery.

## Full Android build status

A full Gradle Android build/unit-test run could not be completed in this execution environment because the Gradle wrapper distribution (`gradle-8.9-bin.zip`) is not locally cached and DNS/network access to `services.gradle.org` is unavailable. The command was attempted; this is an environment limitation, not a reported passing build.

## Phase 3 recommended next scope

- confirmed-closed-bar mode for all signal engines (not only historical no-lookahead guarantees),
- provider health/failover scoring and rate-limit budgets,
- deeper true-streaming FX provider/broker integration where credentials are available,
- Strategy Builder -> validation -> backtest -> live activation workflow hardening,
- indicator presets for Scalping / Intraday,
- chart object-budget controls and visual priority rules for TradePro / LIT X / SMT.
