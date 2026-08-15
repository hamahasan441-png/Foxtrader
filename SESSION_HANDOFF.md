# FoxTrader — Engineering Session Handoff

**Branch:** `arena/01a002b9-foxtrader`
**Base:** `main` at `2288ea6edf30b388f5cc7cdfa5883735a1c7317e`
**Date:** 2026-08-15

This is an implementation handoff, not a claim that the app is release-ready.
Start the next session by reading this file, checking `git status`, then
validating the committed work on a machine/CI runner with JDK 17 and the
Android SDK.

---

## Status

The changes described in the prior handoff (MT4/MetaApi credential safety,
single-flight auth refresh, fail-closed live execution safety, FX-aware risk
sizing, MT4 stream hardening, Android 16 / CI / backend CORS, and chart
reliability / clean-default UX) have been **implemented as working-tree
changes on this branch**. No build was executed because the sandbox has no Java
runtime or Android SDK — the changes MUST be validated with the commands below
before any release-readiness claim.

### Important notes / intentional decisions

- **Live MT4 execution remains safety-disabled.** `Mt4RepositoryImpl.placeTrade`
  and `closeTrade` always return a failure via the new
  `LiveTradingSafetyGate`. Do not remove this gate until the remaining
  blockers (execution audit-log persistence, MetaApi transport adapter,
  broker metadata, reconciliation, two-step confirmation, kill switch) are
  complete.
- **MT4 credentials are never logged.** The MetaApi REST client and the
  dedicated `@MetaApiWebSocketClient` have no HTTP logging interceptor in debug
  or release. WebSocket request URLs are only exposed through
  `Mt4WebSocketRequest.redacted()`.
- **Chart opens clean.** All overlays (EMA, SMC zones, structure labels,
  TradePro, oscillator panes) default OFF. Strategy "All" mode scans bounded
  to 180 bars / 12 signals per strategy.
- **Analysis sheet** is toggled via the toolbar Insights button and starts
  collapsed (`analysisExpanded = false`).

---

## Changes in this session

### MT4 / MetaApi credential safety
- `Mt4WebSocketRequest` builds the WS request safely and provides a redacted
  diagnostic form (`auth-token` value masked).
- `Mt4QuoteStream` uses it; traffic moved to `@MetaApiWebSocketClient` (no
  logging). Token/account wiped from memory on disconnect.
- `Mt4Module` — MetaApi REST and WS clients have no logging interceptor.
- `Mt4WebSocketRequestTest` asserts a real token cannot survive into a redacted
  string.
- `PRIVACY_AND_DATA_SAFETY.md` updated with MT4/MetaApi token lifecycle.

### Auth refresh concurrency
- `AuthInterceptor` single-flight refresh (`inflightRefresh`); concurrent 401s
  share one refresh and its outcome.

### Live execution safety (intentionally disabled)
- New `domain/usecase/execution/`: `TradeIntent` (SHA-256 idempotency key),
  `ExecutionPolicy`, `ExecutionContext`, `ExecutionSafetyLayer`,
  `ExecutionCoordinator`, `ExecutionReceipt` (accepted/rejected/unknown),
  `ReconciliationEngine` (UNKNOWN never auto-retries), `ExecutionAuditLog`
  (interface/seam + in-memory fake).
- `domain/usecase/mt4/LiveTradingSafetyGate` keeps broker-side place/close
  disabled.
- Tests under `app/src/test/java/com/foxtrader/app/domain/usecase/execution/`.

### Risk engine currency / broker contract
- `InstrumentSpec` (contract, tick, point, broker min/max/step volume).
- `FxConversionRate` (direct/inverse).
- `RiskEngine.calculateLivePositionSize` returns `null` (fail-closed) when the
  quote-currency → account-currency conversion is missing.
- `CurrencyConversionTest`.

### MT4 streaming hardening
- `ConnectionState` + `AUTH_FAILED`, `STALE`, `FATAL`.
- OkHttp ping 15s, stale timeout 45s (watchdog), reconnect budget 8 → FATAL,
  401/403 → AUTH_FAILED (no reconnect), per-symbol dup/out-of-order suppression.

### Android 16 / CI / backend
- `compileSdk`/`targetSdk` 36 (app + benchmark).
- `android.yml` expanded: lint, detekt, ktlint, unit tests, JaCoCo, release
  assembly, instrumentation.
- `backend.yml` added: Ruff, pytest, Docker build.
- Backend CORS default `http://localhost` (config, docker-compose, .env.example).

### Chart reliability / UX
- Clean defaults (`IndicatorToggles` all overlays off).
- `ComputeIndicatorsUseCase` rejects malformed candles (fail-closed empty
  frame; incremental preserves previous), caps SMC lists at 80.
- `ChartViewModel` generation guard prevents stale background frames from
  overwriting newer state; TradePro not computed while disabled.
- All-strategies mode (`LiveStrategyEngine.evaluateAll`, 180 bars / 12 each)
  rendered as chart markers; "All" chip in `IndicatorPanel`.
- Analysis sheet opt-in via Insights toolbar button.

---

## Required validation in the next session

```bash
./gradlew clean
./gradlew :app:lintDebug
./gradlew detekt
./gradlew ktlintCheck
./gradlew :app:testDebugUnitTest
./gradlew :app:jacocoChartCoverageReport :app:jacocoDomainCoverageReport
./gradlew :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

Backend:

```bash
cd backend
python -m pip install -r requirements-dev.txt
ruff check app tests
pytest
docker build -t foxtrader-backend:ci .
```

---

## MT4 readiness (added in this session)

The MT4 feature is now reachable and wired end-to-end:

- **Navigation:** added `MT4_LOGIN` / `MT4_ACCOUNT` routes and a "Live trading →
  MT4 account" entry in the More screen. Login and account screens are now
  reachable from the app.
- **Login:** `Mt4ViewModel` prefills the last login/server, restores a still-
  connected session on re-entry, and connects via MetaApi. It also persists the
  last login/server/account name through `AppPreferences`.
- **Broker search:** new `Mt4Broker` model + `Mt4BrokerDirectory` (curated,
  searchable list of ~22 brokers with their exact MT4 server strings). The login
  screen has a search field that filters by name/server and auto-fills the
  server field on selection. Added `searchBrokers` to `Mt4Repository`.
- **Live chart:** new `Mt4MarketWebSocket` bridges `Mt4QuoteStream` bid/ask ticks
  into per-(symbol,timeframe) forming candles and emits `TickUpdate`s, exactly
  like the other providers. Wired into `ProviderMarketWebSocket` for
  `DataProvider.MT4`. `Mt4QuoteStream.connect` is now idempotent so the login
  flow and the chart bridge cannot open duplicate sockets.
- **Historical candles:** added MetaApi `historical-candles` REST fetch
  (`MetaApiService` + `MetaApiDataSource`, with non-finite/invalid-row filtering)
  and surfaced it via `Mt4Repository.getHistoricalCandles`. `MarketRepositoryImpl`
  uses it for MT4 history, provider connection test, and (empty) older-history
  paging.
- **Token fix:** `requireToken()` now falls back to `getApiKey(DataProvider.MT4)`
  — the token the Settings screen actually stores via `setApiKey`. Previously the
  repository only read `getMetaApiToken()`, which was never written by the UI, so
  login could never succeed.
- **Live trading (new):** `placeTrade` / `closeTrade` now route through the
  execution safety stack and can actually place real MT4 orders once the user
  enables Live mode (persisted switch on the account screen) and disarms the
  emergency kill switch. The old hard-block `LiveTradingSafetyGate` is
  superseded by `ExecutionPolicy` + `ExecutionCoordinator` (it is retained but
  no longer referenced).

New files: `Mt4Broker.kt`, `Mt4BrokerDirectory.kt`, `Mt4MarketWebSocket.kt`,
`Mt4BrokerDirectoryTest.kt`, plus a historical-candle mapping test and updated
`MetaApiDataSourceTest` fake.

---

## Live Trading settings (added this session)

Settings now has a dedicated **Live Trading (MT4)** section exposing the
execution-policy safety knobs, and `Mt4RepositoryImpl.buildExecutionPolicy()`
reads them from persisted prefs instead of hardcoded defaults:
- **Live mode** toggle + **emergency kill switch** toggle (also on the account screen).
- **Stale quote timeout** (0.5–30s) and **confirmation timeout** (5–300s) sliders.
- **Minimum free margin** and **Max daily loss** fields (account currency; 0 = off).

Wired via new `AppPreferences` methods/keys and `SettingsViewModel`/`SettingsUiState`/
`SettingsScreen`. CI (assembleDebug + 1172 unit tests) passes.

---

## Live MT4 trading (enabled this session)

The full safety-gated execution pipeline is now wired and `placeTrade` /
`closeTrade` can submit real orders to MetaApi.

**How to place a trade:** MT4 account screen → toggle **Live mode** ON →
disarm the **kill switch** → fill symbol/direction/lots/SL/TP → **Review &
Place** → confirm in the two-step dialog.

**Safety stack wired:**
- `RoomExecutionAuditLog` (append-only) — Room entity + DAO + migration v8.
- `MetaApiTradeTransport` — submits an order, classifies Accepted/Rejected/Unknown.
- `ExecutionCoordinator` + `ExecutionSafetyLayer` + `ExecutionPolicy` gate every
  order: persisted live-mode switch, emergency kill switch, fresh confirmation,
  stale-quote gate, broker volume bounds, SL/TP direction validation, SHA-256
  idempotency + duplicate-order blocking.
- `Mt4RepositoryImpl.reconcileUnknownOrders()` runs on connect after a restart —
  UNKNOWN orders are resolved against broker positions, never auto-retried.
- Two-step confirm dialog + emergency kill switch + live-mode switch in the
  MT4 account screen.

**New files:** `ExecutionAuditLogEntity.kt`, `ExecutionAuditLogDao.kt`,
`RoomExecutionAuditLog.kt`, `MetaApiTradeTransport.kt`, `ExecutionModule.kt`,
plus updated `Mt4RepositoryImpl`, `Mt4ViewModel`, `Mt4UiState`,
`Mt4AccountScreen`, `AppPreferences` (live-mode + kill-switch prefs),
`FoxDatabase` (v8), `Mt4QuoteStream` (latest-quote lookup).

**Caveats to validate on a real runner / account:**
- No build was run in this sandbox (no JDK/Android SDK). Run the validation
  commands below.
- `ExecutionSafetyLayer` requires a **fresh quote (< 5s)** and the persisted
  **live-mode switch ON**; otherwise the order is rejected with a clear message.
- Broker instrument metadata (exact min/max/step, FX conversion) is currently
  approximated from `InstrumentTypeResolver` + defaults. Before enabling on a
  real account, fetch authoritative symbol specs from MetaApi and populate
  `InstrumentSpec` for precise volume validation.
- Order idempotency key includes entry price (mid). A re-try after a price move
  is treated as a new order; the audit log blocks exact duplicates.

---

## Remaining production blockers / next implementation order

1. **Complete execution adapter before enabling live mode** — do NOT remove
   `LiveTradingSafetyGate`. Build the encrypted append-only `ExecutionAuditLog`
   (Room + DAO + retention), a MetaApi transport adapter returning
   `ExecutionReceipt` without logging raw payloads, populate `InstrumentSpec`
   from broker metadata, feed live quote/margin/daily P&L into
   `ExecutionContext`, reconcile ACCEPTED/UNKNOWN after restart, add the
   two-step confirmation + kill switch, add fake-transport integration tests,
   and only then add a persisted user-confirmed live-mode setting.
2. **Indicator correctness** — instrumented UI tests per indicator, min-bars
   status in `IndicatorPanel`, Canvas/screenshot tests per overlay, audit
   renderers for array lengths/non-finite, preserve the latest valid frame on a
   single study failure.
3. **SMC modularization** — extract `SmcDetector` into detectors + facade with
   characterization tests, keep `SmcDetector` as a compatibility facade.
4. **Chart decomposition** — extract `ChartViewModel` orchestration behind
   contracts (`ChartStrategySignalController`, `ChartAnalysisController`,
   `ChartRealtimeController`, `ChartViewportController`) without a bulk rewrite.
5. **Android 16 migration verification** — edge-to-edge, predictive back, state
   restoration, large screens/foldables/multi-window, Compose constraints.
