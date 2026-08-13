# Critical Bug Fix Report

Scope: crashes, non-logic (silent numeric corruption) and concurrency defects in
the FoxTrader domain and presentation layers.

Method: the pure-domain layer was compiled standalone and **executed** against a
fuzz harness of degenerate inputs (empty/1/2-bar series, flat prices, zero
volume, zero and negative periods, zero-priced bars, 1e-9 and 1e12 prices,
duplicate timestamps, zero/negative balances, 8-thread concurrent writes).
Every issue below was reproduced as a real failure before being fixed, and the
harness re-run clean afterwards.

## Why these inputs are reachable

Indicator periods are **not** internal constants. They arrive from:

- the plugin SDK — `params["period"]?.toInt() ?: 14` in every builtin indicator,
  read straight from a user-authored script with no validation,
- indicator settings and strategy scripts.

Bucket/row counts, account balance, leverage and stop price are likewise
user-editable. "Impossible" values are routine.

---

## 1. Crashes (reproduced `ArrayIndexOutOfBounds` / `NegativeArraySize` / `IllegalArgument`)

| # | Site | Trigger | Failure |
|---|------|---------|---------|
| 1 | `TechnicalIndicators.calculateATRIncremental` | `period <= 0` | `atr[period - 1]` → index −1 |
| 2 | `TechnicalIndicators.calculateADXIncremental` | `period <= 0` | slips past the `len < period * 2` guard, then seeds from `tr[-1]` |
| 3 | `TechnicalIndicators.calculateRSI` | `period <= 0` | writes `rsi[period]` at a negative index |
| 4 | `TechnicalIndicators.calculateSMA` | `period <= 0` | `candles[i - period]` reads past the end |
| 5 | `TechnicalIndicators.calculateMomentum` | `period < 0` | loop starts negative, reads past the end |
| 6 | `SuperTrend.calculate` | `atrPeriod <= 0` | inherits the ATR crash |
| 7 | `SmcDetector.computeVolumeProfile` | `buckets <= 0` | `NegativeArraySizeException`, or empty `coerceIn(0, -1)` range |
| 8 | `MarketProfile.compute` | `rowSize <= 0` | same two failures |
| 9 | `RiskEngine.calculatePositionSize` | account balance `0.0` | `riskPercent` = Infinity trips `require(riskPercent >= 0.0)` inside `PositionSizeResult` and throws **out of the sizing call** |
| 10 | `ChartAiCoordinator.computeFingerprint` | empty series | `first()`/`last()` on an empty list |

Fix: periods are clamped once at the source via a documented `sanitizePeriod`
helper (and locally where an indicator has its own window math), so every
indicator is **total** — bad configuration degrades to a degenerate-but-valid
series instead of taking the chart down.

## 2. Silent numeric corruption (the dangerous class — no crash, wrong numbers)

| # | Site | Trigger | Consequence |
|---|------|---------|-------------|
| 11 | `calculateVolatility` | any close `== 0.0` | `0/0` = NaN; **one** NaN poisons mean+variance for the whole series. Feeds stop distances and volatility-based sizing |
| 12 | `calculateEMA` / `calculateMACD` | `period == -1` | `k = 2/0` = Infinity → every value NaN |
| 13 | `BollingerBands` / `Stochastic` / `Ichimoku` | `period == 0` | empty window → division by zero → NaN bands rendered as a garbage overlay |
| 14 | `RiskEngine.calculatePositionSize` | non-finite intermediate | `(Infinity * 100).roundToInt()` pins to `Int.MAX_VALUE` → a **~21 million lot** order that looks like a real number all the way downstream |
| 15 | `PositionCalculator` | `leverage == 0` | margin rendered as `"Infinity"` — the trade looks free |
| 16 | `BacktestEngine.computeReturns` | balance wiped to `0.0` mid-run | NaN Sharpe/Sortino |
| 17 | `BacktestEngine.calculateMetrics` | `initialBalance == 0` | `returnPercent` = Infinity, which also poisons the Calmar ratio |

Items 14–17 matter most: a backtest report is a decision-making artifact, and a
NaN Sharpe silently invalidates every ranking built on it (strategy comparison,
optimizer objective, analytics report).

## 3. Concurrency

**`RiskEngine.config` was not `@Volatile`** while `peakBalance`, `currentBalance`
and `haltReason` around it already were. It is written from the settings/UI
thread and read by background sizing/gating threads, so a worker could keep
serving a **stale risk configuration indefinitely** — still sizing against the
old risk-per-trade after the user lowered it. `RiskConfig` is an immutable data
class, so a volatile reference swap publishes the whole config atomically.

A dedicated 8-thread × 1 000-trade test asserts `recordTrade` loses no balance
updates (the balance is what every risk gate is evaluated against).

## 4. Structured concurrency — swallowed `CancellationException`

Eight ViewModels wrapped suspending work in `catch (e: Exception)`, which also
catches `CancellationException`. Two consequences: cancellation stops
propagating (breaking structured concurrency), and a routine screen close or
re-run surfaces as a **spurious on-screen error**.

Fixed in `BacktestLab`, `Strategies`, `AlertRules`, `Correlation`, `DailyPlan`,
`OpportunityBoard`, `TradeProRiskDashboard`, `TradeProSimulator` — each now
rethrows `CancellationException` first, matching the pattern `ScannerViewModel`
already used correctly.

---

## Verification

| Check | Result |
|---|---|
| Fuzz suite 1 — 15 datasets × ~45 domain entry points | 70 issues → **0** |
| Fuzz suite 2 — risk/sizing/backtest money paths | 10 issues → **0** |
| Fuzz suite 3 — incremental-vs-full parity, concurrency, indicator bounds | **pass** (no regressions introduced) |
| Fuzz suite 4 — negative/zero periods | 25 issues → **0** |
| **Existing domain unit tests** | **768 passed, 0 failed** |
| New regression tests | **35 passed** |
| Backend `pytest` | **23 passed** |

Incremental-recomputation parity (the live-tick hot path) was verified to still
match full recomputation to 1e-6 for EMA, ATR, VWAP, MACD and ADX, on both the
append and last-bar-update paths.

### Environment note

No Android SDK or JDK was present and Maven/Gradle mirrors were unreachable, so
a full `./gradlew` build could not be run here. Verification instead used a JVM
(`jdk4py`) plus the Kotlin compiler (`npm kotlin-compiler`) to compile and
execute the framework-free domain layer directly. All changed files were also
parse/type-checked. The UI-layer changes (`CancellationException` rethrows,
the `computeFingerprint` guard) are mechanical and were compile-verified against
a coroutines stub; they still warrant a CI run of `assembleDebug`.

## New test files

- `domain/usecase/indicators/IndicatorDegenerateInputTest.kt`
- `domain/usecase/risk/RiskEngineDegenerateStateTest.kt`
- `domain/usecase/calculator/PositionCalculatorDegenerateInputTest.kt`
- `domain/usecase/backtest/BacktestEngineDegenerateConfigTest.kt`

---

# Security & Correctness Hardening Pass — 2026-08-13

Scope: risk-gating, response lifecycle, backend CORS, data backup exposure,
CI/CD enforcement, provider fail-loud behaviour, and release-signing hygiene.
Method per item: trigger → failure/gap → fix → verification.

## 1. [CRITICAL] Paper-trading bypassed the risk gate

- **Trigger:** `PaperTradingSession.place()` sent the raw UI `volume` straight
  to `PaperBroker.placeOrder()`, never consulting `RiskEngine`.
- **Failure / gap:** A paper trade could open any position size regardless of
  the configured per-trade risk %, defeating the masterplan invariant that no
  order reaches a broker unless risk allows it. Two gating services
  (`RiskGatedOrderService`, `RiskGatedBrokerExecutor`) existed but neither was
  wired into paper trading.
- **Fix:** `PaperTradingSession` now delegates to `RiskGatedBrokerExecutor`
  (the broker-adapter gate, matching `RiskGatedBrokerExecutor.placeMarketOrder`).
  The free-typed UI volume is applied as a manual override, the engine's
  `calculatePositionSize` + `canOpenTrade` gate it, and a rejected trade never
  reaches `PaperBroker`. `buy()`/`sell()` now return `RiskGatedBrokerResult`
  carrying rejection reasons + the risk-adjusted `sizing.volume` (the actual
  filled volume). `PaperTradingViewModel`/`PaperTradingUiState`/`PaperTradingScreen`
  surface those reasons and the filled volume. The duplicate `RiskGatedOrderService`
  (+ its test) was deleted so there is exactly one gating code path.
- **Verification:** `PaperTradingSessionTest` extended with
  `trade exceeding the configured risk percent is rejected and never reaches the broker`
  and `accepted order surfaces the risk-computed filled volume`. All domain
  files + tests compile under `kotlinc` (stubbed coroutines/inject; see env note).

## 2. [CRITICAL] `AuthInterceptor` returned an already-closed `Response`

- **Trigger:** On a 401 the original `Response` was closed, then the
  refresh-failure branch (and the `getAccessToken() ?: return response` guard)
  fell through to `return response` — handing callers a closed body whose
  `read()` throws `IllegalStateException: closed`.
- **Fix:** A clean 401 with a readable `{"error":"Session expired"}` body is
  built via `response.newBuilder()` **before** closing the original, and every
  refresh-failure / missing-new-token branch returns that fresh response.
  Successful refresh still returns `chain.proceed(retryRequest)`.
- **Verification:** New `AuthInterceptorTest` (fake `SyncApi` throwing on
  refresh + a fake refresh that yields no new token) asserts the returned
  `Response` is not closed and its body reads back cleanly. Compiles under
  `kotlinc` with okhttp/mockk stubs.

## 3. [HIGH] Backend CORS wildcard + credentials

- **Trigger:** `create_app()` always set `allow_credentials=True`, while
  `Settings` defaulted `cors_origins` to `["*"]`.
- **Failure / gap:** Browsers reject credentialed requests against a wildcard
  origin; shipping both is broken and unsafe (origin echo would leak
  authenticated data).
- **Fix:** `Settings` gains an `allow_credentials` flag (`FOX_ALLOW_CREDENTIALS`).
  `create_app()` force-disables credentials whenever the origin list contains a
  wildcard and logs a hard warning (never silent).
- **Verification:** New `backend/tests/test_cors.py` asserts the middleware never
  receives `allow_credentials=True` with `["*"]`, and that the warning is
  logged. `pytest` = 26 passed; `ruff check` clean.

## 4. [HIGH] Room database exposed to cloud/device backup

- **Trigger:** `android:allowBackup="true"` with no exclusions — the user's
  journal/drawings/alerts (`foxtrader.db*`) could be copied off-device in
  plaintext via Auto Backup / device transfer.
- **Fix (option b — minimum viable):** Kept `allowBackup` true so the
  already-encrypted `EncryptedSharedPreferences` can still be restored, and
  added `@xml/backup_rules` (API 23–30) + `@xml/data_extraction_rules` (API 31+)
  excluding `foxtrader.db`, `-wal`, `-shm` from both cloud backup and
  device-to-device transfer. SQLCipher (option a) was not chosen because it
  requires a native dependency that could not be fetched/verified in this
  sandbox; option b fully closes the exposure with no runtime dependency.
- **Verification:** Manifest + XML reviewed; referenced resources resolve.

## 5. [HIGH] Turn on real CI/CD gates

- **Fix:** `detekt { ignoreFailures = false }` and `ktlint { ignoreFailures.set(false) }`
  in `app/build.gradle.kts`; `android.yml` runs `./gradlew detekt ktlintCheck`
  before the build and adds a non-blocking OWASP `dependencyCheckAnalyze` step.
  New `backend.yml` runs ruff + pytest on push/PR (required check) with a
  non-blocking `pip-audit` report. OWASP Dependency-Check plugin wired via
  version catalog.
- **Gap/note:** a fresh detekt baseline should be generated from a clean CI run
  (`./gradlew detektBaseline`) to cover any historical violations; my changed
  files are clean per static review.

## 6. [MEDIUM] `DukascopyAdapter` silently substituted providers

- **Trigger:** `fetchHistory()` returned `emptyList()`, whose KDoc claimed it
  "causes the repository to fall back to alternative providers".
- **Failure / gap:** Contradicted the fail-loud principle in `DataProvider.kt` —
  a caller could believe it was looking at Dukascopy data when it was actually
  Binance/synthetic.
- **Fix:** `fetchHistory()` now throws `ProviderNotImplementedException`
  (matching the rest of the codebase), and the KDoc no longer claims a silent
  fallback. `DataProvider.DUKASCOPY` already documents the stub.
- **Verification:** Compiles under `kotlinc`.

## 7. [MEDIUM] Missing auth/sync backend silently 404s

- **Fix:** Added clearly-flagged "⚠️ Not implemented yet — auth & cloud-sync
  endpoints" sections to `README.md` and `backend/README.md` listing
  `/api/v1/auth/*` and `/api/v1/sync/*` as client-contract-only (referenced by
  `SyncApi.kt`, no server implementation in this repo).

## 8. [LOW / hardening]

- **Certificate pinning:** the main backend `OkHttpClient` now applies a
  `CertificatePinner` driven by `FOXTRADER_CERT_PINS` (build-config), only
  active for non-local backend hosts; inert by default so local dev
  (`10.0.2.2`) is unaffected. Pinner logic compiles under `kotlinc`.
- **Release signing:** `release.yml` now FAILS the job with a clear error when
  the keystore secrets are absent, instead of silently producing a debug-signed
  artifact.

## Verification

| Check | Result |
|---|---|
| Backend `pytest backend/tests` | **26 passed** (23 baseline + 3 new CORS) |
| Backend `ruff check app tests` | **pass** |
| Workflow YAML (`android.yml`, `backend.yml`, `release.yml`) | parse OK |
| Kotlin domain/auth/provider changes (`kotlinc` + stubs) | compile-clean |
| `./gradlew :app:assembleDebug :app:testDebugUnitTest` | not runnable here — see env note |

### Environment note

As in the previous pass, this sandbox has **no Android SDK and no usable JDK
toolchain** (Gradle distribution, Maven Central, and GitHub release assets are
all network-blocked), so `./gradlew :app:testDebugUnitTest :app:assembleDebug`
could not be executed. Kotlin changes were instead **type-checked** with the
Kotlin compiler against stubbed coroutines/inject/serialization/okhttp/mockk,
and the backend suite was run for real. A CI run of the Android tasks is still
required to confirm the full `assembleDebug`/unit-test path and the
detekt/ktlint gates.

## New / changed test files

- `domain/usecase/orders/PaperTradingSessionTest.kt` (extended: risk-gate rejection, filled volume)
- `data/auth/AuthInterceptorTest.kt` (new)
- `backend/tests/test_cors.py` (new)
- removed `domain/usecase/orders/RiskGatedOrderServiceTest.kt` (service deleted)

---

# Backend Integration Pass — 2026-08-13

Scope: implement the backend auth + cloud-sync endpoints that the Android
client's `SyncApi.kt` referenced but which had no server implementation
(previously documented as "not implemented yet"); fix a broken CI step found
along the way. Method: trigger → gap → fix → verification.

## 1. Missing auth + sync backend now implemented

- **Trigger:** The Android client (`SyncApi.kt`, `AuthRepositoryImpl`,
  `CloudSyncRepositoryImpl`) calls `/api/v1/auth/*` and `/api/v1/sync/*`, but
  the FastAPI backend only served market candles — every login/sync request
  404'd.
- **Gap:** Login/register/refresh/logout and push/pull had **no server
  implementation**, so the app's auth and cloud-sync flows could never work.
- **Fix:**
  - `backend/app/core/auth.py` — pure `AuthService`: PBKDF2-HMAC-SHA256 password
    hashing with per-user salt; opaque access tokens (15-min TTL) + rotated
    refresh tokens (7-day TTL); duplicate-email / bad-credentials / invalid-token
    errors.
  - `backend/app/core/sync_store.py` — pure `SyncStore`: per-user
    last-write-wins merge on `updatedAt`, pull window (`since`) + type filter.
  - `backend/app/routers/auth.py` — `POST /register|/login|/refresh` returning
    the exact camelCase `AuthResponse` (`tokens`, `user`) the client's
    kotlinx.serialization expects; `POST /logout` revokes the access token (204).
  - `backend/app/routers/sync.py` — `POST /push` (204) and `GET /pull`, both
    gated on a valid `Authorization: Bearer <accessToken>`; camelCase
    `SyncPullResponse` (`items`, `serverTimestamp`, `hasMore`).
  - `backend/app/api.py` — wires the stores onto `app.state` and includes both
    routers.
- **Verification:** End-to-end smoke test confirms register → push → pull →
  refresh → token rotation → 401-on-reuse all return the client contract.
  Storage is in-memory (documented); durable persistence (PostgreSQL/Redis)
  remains on the roadmap.

## 2. Backend CI pytest path was broken

- **Trigger:** `backend.yml` (added in the prior pass) ran `pytest backend/tests`
  with `working-directory: backend`, resolving to a non-existent
  `backend/backend/tests`.
- **Fix:** Changed the step to `pytest tests` (pyproject already sets
  `testpaths = ["tests"]`).
- **Verification:** Workflow YAML re-validated; `pytest tests` from `backend/`
  runs the full 47-test suite.

## Verification

| Check | Result |
|---|---|
| `pytest backend/tests` | **47 passed** (26 prior + 21 new: auth core 6, sync core 4, auth http 6, sync http 5) |
| `ruff check app tests` | **pass** |
| OpenAPI route registration | all auth + sync + market + health paths present |
| End-to-end smoke (register→push→pull→refresh→logout) | camelCase contract verified |

## New backend files

- `backend/app/core/auth.py`
- `backend/app/core/sync_store.py`
- `backend/app/routers/auth.py`
- `backend/app/routers/sync.py`
- `backend/tests/test_auth_core.py`
- `backend/tests/test_sync_core.py`
- `backend/tests/test_auth_http.py`
- `backend/tests/test_sync_http.py`
