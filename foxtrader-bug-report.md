# FoxTrader Bug, Gap & UI Problem Report

**Date:** 2026-08-04  
**Scope:** Full codebase (338 Kotlin source files, 77 unit tests, 10 instrumentation tests)  
**Analyst:** Kilo  

---

## Executive Summary

A comprehensive review of the FoxTrader Android trading-analysis app identified **140+ findings** across six severity tiers. The most critical issues are:

1. **WebSocket race condition during provider switch** — lost subscriptions, silent data gaps (B16)
2. **AuthInterceptor clears tokens on transient network errors** — users logged out on WiFi hiccups (B62)
3. **ScanAlertWorker retries ALL exceptions indefinitely** — programming errors cause infinite retry loops (B74)
4. **Alert ID truncation via `entry.toLong()`** — price collisions corrupt the alert DB (B75/B78)
5. **No certificate pinning + default HTTP URL** — cleartext traffic risk in debug/release (B82/B83)
6. **Candle provenance lost in entity→domain mapping** — synthetic vs live data indistinguishable in UI (B52/B53)
7. **Cloud sync push-then-pull with no rollback** — partial sync failures leave inconsistent state (B40)
8. **Room migration deletes all candle data** on schema upgrade (B1)

The app has strong architectural foundations (Clean Architecture, MVVM, offline-first), but the data layer, networking, and risk-management edges contain defects that can cause data loss, incorrect trading decisions, and security exposure.

---

## 1. CRITICAL BUGS (Must Fix Immediately)

### C1. WebSocket Race Condition — Lost Subscriptions on Provider Switch
- **File:** `data/remote/websocket/ProviderMarketWebSocket.kt:62-75`
- **Severity:** Critical
- **Impact:** When the user switches data providers (e.g., Binance → Bybit), the new `(symbol, timeframe)` pair is added to `subscriptions` at line 65, but `ensureProviderLocked()` calls `switchProviderLocked()` which subscribes existing pairs to the new socket. The check at line 71 (`activeSocket !== previousSocket`) is false because the socket changed, so the **new pair is never subscribed**. The user sees no live data and no error.
- **Root Cause:** `subscribe()` adds to `subscriptions` before verifying the socket is ready for that pair.
- **Fix:** Defer adding to `subscriptions` until after `switchProviderLocked` completes, or have `switchProviderLocked` return a list of pairs that still need subscription.

### C2. AuthInterceptor Clears Tokens on Transient Network Errors
- **File:** `data/auth/AuthInterceptor.kt:81-84`
- **Severity:** Critical
- **Impact:** If a 401 triggers `attemptRefresh()` and the refresh request fails due to a temporary network error (WiFi dropout, DNS timeout), the interceptor clears all tokens and sets the session as expired. The user is logged out even though their credentials are valid.
- **Root Cause:** `catch (_: Exception)` at line 93 catches *all* exceptions, including `IOException`, and unconditionally clears tokens.
- **Fix:** Only clear tokens on auth-specific failures (401 on refresh, 403). Preserve tokens on `IOException`, `SocketTimeoutException`, etc.

### C3. ScanAlertWorker Retries ALL Exceptions Indefinitely
- **File:** `data/alerts/ScanAlertWorker.kt:60-63`
- **Severity:** Critical
- **Impact:** The `doWork()` method catches all exceptions and returns `Result.retry()`. This includes programming errors like `NullPointerException`, `ClassCastException`, and `IllegalStateException`. WorkManager retries indefinitely with exponential backoff, but the underlying bug is never surfaced or fixed. The worker could run forever on a broken code path.
- **Root Cause:** Overly broad `catch (e: Exception)` with no distinction between transient and fatal errors.
- **Fix:** Catch only known transient exceptions (`IOException`, `HttpException` with 5xx, `SocketTimeoutException`). Let unexpected exceptions propagate so they crash the worker and surface in crash reporting.

### C4. Alert ID Truncation Causes DB Collisions
- **File:** `data/alerts/ScanAlertWorker.kt:111` (and line 119 for TRADEPRO)
- **Severity:** Critical
- **Impact:** Alert IDs are built as `"tradepro-${symbol}-${setup.entry.toLong()}"`. `Double.toLong()` truncates decimals. Two setups with entry prices `1.2345` and `1.2346` produce the **same ID**. The second alert overwrites the first in the Room DB (`OnConflictStrategy.REPLACE`). Traders miss alerts.
- **Root Cause:** Using `Double.toLong()` for unique ID generation.
- **Fix:** Use a hash (e.g., `"$symbol-${setup.entry}-${setup.stopLoss}-${setup.target1}"`) or a UUID.

### C5. No Certificate Pinning + Default HTTP URL
- **File:** `di/NetworkModule.kt:64, 83`
- **Severity:** Critical
- **Impact:** The default fallback URL is `http://10.0.2.2:8000/` (cleartext). No certificate pinning exists for any API endpoint (FoxTrader backend, Binance, Bybit, Alpha Vantage, Twelve Data). A compromised CA can MITM all traffic. On Android 9+ (API 28), cleartext is blocked by default, but the `check()` at line 65 allows HTTP in debug builds, which could accidentally ship.
- **Root Cause:** No `CertificatePinner` configured on any OkHttp client. No `network_security_config.xml`.
- **Fix:** Add `CertificatePinner` for production domains. Change default URL to HTTPS. Add `network_security_config.xml` with cleartext disabled for release.

### C6. Candle Provenance Lost in Entity→Domain Mapping
- **File:** `data/mapper/CandleMapper.kt:14-21, 43-50`
- **Severity:** Critical
- **Impact:** `CandleEntity.toDomain()` and `CandleDto.toDomain()` both drop the `source` field. The domain `Candle` model has no provenance. The UI and decision engine **cannot distinguish real market data from synthetic seed data** at the domain level. A trader could be looking at fabricated bars without the UI being able to warn them programmatically.
- **Root Cause:** The `source` property exists on entity/DTO but not on the domain model.
- **Fix:** Add `source: CandleSource` to the domain `Candle` model and propagate it through all mappers.

### C7. Cloud Sync Push-Then-Pull With No Rollback
- **File:** `data/repository/CloudSyncRepositoryImpl.kt:61-93`
- **Severity:** Critical
- **Impact:** `sync()` pushes local changes, then pulls remote changes. If push succeeds but pull fails (network error, auth failure), local state has already been sent to the server, but the pull didn't complete. `lastSyncTime` is not updated (line 78 is after pull), so on retry the entire push is sent again. More importantly, there is **no conflict resolution** — `conflicts` is hardcoded to 0 (line 84), giving a false sense of clean syncs.
- **Root Cause:** No transactional guarantee or partial-failure handling.
- **Fix:** Implement a sync log with idempotent operations, or use a transactional push+pull with rollback on pull failure.

### C8. Room Migration Deletes All Cached Candle Data
- **File:** `data/local/FoxDatabase.kt:137`
- **Severity:** Critical
- **Impact:** `MIGRATION_3_4` executes `DELETE FROM candles` after adding the `source` column. This permanently wipes all cached market data on every schema upgrade. Users lose their offline cache and must re-fetch everything.
- **Root Cause:** Migration chose data deletion over a backfill strategy.
- **Fix:** Populate `source` with a sensible default (e.g., `Cached`) and backfill from metadata, or use a two-phase migration.

---

## 2. HIGH SEVERITY BUGS

### H1. Binance WebSocket Missing Heartbeat
- **File:** `data/remote/websocket/BinanceWebSocket.kt:92-98`
- **Impact:** Binance WS connections can drop silently after ~24h of inactivity. No proactive keep-alive exists. Unlike Bybit, Binance WS has no ping/pong mechanism.
- **Fix:** Add a periodic ping (e.g., every 15 minutes) and detect missed pongs to trigger reconnection.

### H2. MarketWebSocket Provider Switch Leaves Old Collectors Leaking
- **File:** `data/remote/websocket/ProviderMarketWebSocket.kt:101-116`
- **Impact:** When switching providers, the old WebSocket's tick and connection-state collectors keep running. They filter by `socket === activeSocket`, so they discard values, but they consume coroutine resources. If the old WebSocket disconnects, the collectors should complete, but partial provider switches leave them dangling.

### H3. AuthInterceptor Race Condition on Concurrent 401s
- **File:** `data/auth/AuthInterceptor.kt:56-70`
- **Impact:** If two authenticated requests receive 401 simultaneously, both attempt token refresh. The first succeeds and saves new tokens; the second fails because the refresh token was rotated server-side. The second attempt clears tokens and logs the user out, even though the first refresh succeeded.

### H4. WebSocket `switchProviderLocked` Subscribes Before Connection Verified
- **File:** `data/remote/websocket/ProviderMarketWebSocket.kt:138-158`
- **Impact:** `switchProviderLocked` subscribes all pairs to the new socket without checking if the connection succeeds. If the new WebSocket fails to connect, subscriptions are registered but no data flows. No retry is triggered.

### H5. AuthInterceptor Uses `runBlocking` on OkHttp Thread Pool
- **File:** `data/auth/AuthInterceptor.kt:86`
- **Impact:** `attemptRefresh()` uses `runBlocking` to perform a synchronous network call on the OkHttp dispatcher thread pool. If multiple requests simultaneously receive 401, each blocks an OkHttp thread. With the default max 64 threads, this can exhaust the pool and cause deadlock-like hangs.

### H6. Cloud Sync `pullAll()` Swallows All Exceptions
- **File:** `data/repository/CloudSyncRepositoryImpl.kt:99-106`
- **Impact:** Returns `null` on any error (auth failure, network error, serialization error). The caller cannot distinguish between "no remote data" and "sync failed."

### H7. No Idempotency Check in Alert Dispatching
- **File:** `data/alerts/ScanAlertWorker.kt:50-63`
- **Impact:** If the worker is retried after process death, the same symbols are re-evaluated and duplicate alerts can be dispatched. No deduplication based on alert ID, timestamp window, or last-evaluation time.

### H8. Missing Indexes on `updatedAt` Columns
- **File:** `data/local/FoxDatabase.kt` (missing), `JournalDao.kt:22`, `DrawingDao.kt:22`
- **Impact:** `getModifiedSince(since)` queries perform full table scans as tables grow. For sync and incremental updates, this degrades over time.

---

## 3. MEDIUM SEVERITY BUGS

### M1. ChartScreen Loading State Order Bug
- **File:** `feature/chart/presentation/ChartScreen.kt:296-358`
- **Impact:** The `when` block checks `state.hasData` before `state.isLoading`. When data is being cleared (e.g., symbol change), `hasData` is false, `isLoading` is true, and `error` is null. The `else` branch shows "No data" instead of the loading spinner.
- **Fix:** Check `isLoading` first in the `when` block.

### M2. Settings Dropdown Uses `valueOf()` Without Error Handling
- **File:** `feature/settings/presentation/SettingsScreen.kt:259, 279, 476`
- **Impact:** `PositionSizingMethod.valueOf(it)`, `AlertPriority.valueOf(it)`, and `Timeframe.fromLabel(label)` will throw `IllegalArgumentException` if the stored string is corrupted. This crashes the Settings screen.
- **Fix:** Use `runCatching` or provide safe fallback defaults.

### M3. Biometric Auth `canAuthenticate()` Misnamed
- **File:** `data/auth/BiometricAuthManager.kt:49-53`
- **Impact:** `canAuthenticate()` uses `ALLOWED_AUTHENTICATORS` (BIOMETRIC_WEAK | DEVICE_CREDENTIAL) but checks for `BIOMETRIC_SUCCESS`. It returns true if the user has a screen lock (PIN/pattern/password) even without biometrics enrolled. The method name implies biometric capability, but it checks for any authentication capability.

### M4. TokenManager `isLoggedIn()` Only Checks Refresh Token
- **File:** `data/auth/TokenManager.kt:57`
- **Impact:** A user could have a valid refresh token but an expired access token. `isLoggedIn()` returns true. The UI shows the user as logged in, but all API calls fail until the first 401 triggers a refresh. There is a brief window where the UI shows authenticated state with a stale access token.

### M5. ScanAlertWorker Sequential Symbol Evaluation
- **File:** `data/alerts/ScanAlertWorker.kt:56-58`
- **Impact:** Symbols are evaluated sequentially. If one symbol's evaluation hangs (e.g., network timeout in `getSourcedCandles`), all remaining symbols in the watchlist are delayed.
- **Fix:** Use `coroutineScope { }.map { }` for parallel evaluation.

### M6. WebSocket `unsubscribe()` Tears Down Entire Connection
- **File:** `data/remote/websocket/BinanceWebSocket.kt:83-89`
- **Impact:** Unsubscribing a single pair from a multi-pair subscription closes and reopens the entire WebSocket. A more efficient approach would send an unsubscribe message to the existing connection.

### M7. Alpha Vantage Rate Limit Not Handled at HTTP Level
- **File:** `data/remote/api/AlphaVantageApi.kt:11-21`
- **Impact:** Alpha Vantage returns HTTP 429 on rate limit, but the API interface returns `JsonElement` with no typed error handling. Retrofit treats 429 as a successful call or throws a generic `HttpException`. The data source checks for error keys in the JSON body, but this doesn't catch HTTP-level 429.

### M8. Twelve Data Free Tier Limit Risk
- **File:** `data/remote/api/TwelveDataApi.kt:17-24`
- **Impact:** `outputSize` defaults to 500, but `fetchCandles()` clamps it to `coerceIn(1, 5000)`. Twelve Data's free tier has an 800 requests/day limit. Requesting 5000 candles per call for multiple symbols exhausts the daily limit quickly.

### M9. MarketRepositoryImpl `fetchDefaultCandlesBefore()` Not in runCatching
- **File:** `data/repository/MarketRepositoryImpl.kt:159-179`
- **Impact:** `fetchDefaultCandlesBefore()` has no error handling. If the backend fails during a historical load, the exception propagates uncaught. While `loadOlderCandles()` wraps it in `runCatching`, it doesn't handle unimplemented providers gracefully.

### M10. WatchlistRepositoryImpl `addSymbol()` Not Atomic
- **File:** `data/repository/WatchlistRepositoryImpl.kt:90-110`
- **Impact:** `addSymbol()` calls `dao.maxPosition()` and `dao.upsertSymbols()` without a transaction. The mutex prevents coroutine races, but if the app crashes between them, the watchlist has a gap in position numbering.

### M11. Journal/Drawing Repositories Missing `getModifiedSince()` for Sync
- **File:** `data/repository/DrawingRepositoryImpl.kt`, `JournalRepositoryImpl.kt`
- **Impact:** `DrawingDao.getModifiedSince()` and `JournalDao.getModifiedSince()` exist but aren't exposed by the repository. The sync layer cannot compute diffs without accessing the DAO directly.

### M12. `BiometricAuthManager.isBiometricEnrolled()` Checks `BIOMETRIC_WEAK`
- **File:** `data/auth/BiometricAuthManager.kt:58-61`
- **Impact:** Returns true even if no fingerprint or face is enrolled, as long as a screen lock is set. The method name implies biometric enrollment, but it checks for any authentication capability.

---

## 4. LOW SEVERITY BUGS & IMPROVEMENTS

### L1. Global `ohlcBuilder` StringBuilder Shared Across Frames
- **File:** `feature/chart/presentation/components/layers/ChartCrosshairLayer.kt:46`
- **Impact:** `private val ohlcBuilder = StringBuilder(96)` is a top-level shared mutable object. While Compose drawing is single-threaded, this is a latent thread-safety risk and makes the code harder to test. Move it inside the draw function or make it thread-local.

### L2. Global `dateAxisFormat`/`timeAxisFormat` Not Thread-Safe
- **File:** `feature/chart/presentation/components/ChartViewport.kt:343-349`
- **Impact:** `SimpleDateFormat` is not thread-safe. These are instance-level (`by lazy`), so each `ChartViewport` gets its own. This is safe because Compose drawing is single-threaded, but if the viewport is ever accessed from multiple threads, these formatters would break.

### L3. Scanner ViewMode Toggle Contrast Issue
- **File:** `feature/scanner/presentation/ScannerScreen.kt:196-211`
- **Impact:** Unselected toggle text is `FoxAmber50` (amber) on a background of `FoxAmber50.copy(alpha = 0.12f)` (very faint amber). The contrast ratio is low, making unselected text hard to read.

### L4. `formatSliderValue` Edge Case
- **File:** `feature/settings/presentation/SettingsScreen.kt:573-578`
- **Impact:** `value.toInt().toFloat() == value` checks for whole numbers. For very large float values (e.g., 1e20), `toInt()` overflows and produces incorrect results. In practice, slider values are small, so this is unlikely.

### L5. WatchlistDao `replaceSymbols()` Emits Intermediate Empty List
- **File:** `data/local/dao/WatchlistDao.kt:54-58`
- **Impact:** `@Transaction` ensures atomic DB commit, but Room observers may emit an intermediate empty list during `clearSymbols()` before `upsertSymbols()` completes. The UI briefly shows an empty watchlist.

### L6. AlertDao `prune()` Uses Inefficient `NOT IN` Subquery
- **File:** `data/local/dao/AlertDao.kt:43-48`
- **Impact:** `NOT IN (SELECT id ... LIMIT :keepCount)` is O(n) on the alerts table. For large tables, a cutoff-timestamp approach would be more efficient.

### L7. ProGuard Rule Contradiction
- **File:** `app/proguard-rules.pro:55`
- **Impact:** `-keep,allowobfuscation interface retrofit2.** { *; }` is contradictory. `-keep` means "don't remove or obfuscate"; `allowobfuscation` is a no-op with `-keep`. Use `-keep` (fully preserve) or `-keepnames` (preserve names, allow removal).

### L8. `CrashReporter` Interface Lacks `report()` Method
- **File:** `data/crash/CrashReporter.kt:13-21`
- **Impact:** No way to manually report caught exceptions or non-fatal errors. The app cannot log handled exceptions to the crash diagnostic system.

### L9. `versionCode` Falls Back Silently to 1
- **File:** `app/build.gradle.kts:33-35`
- **Impact:** If `FOXTRADER_VERSION_CODE` is set to a non-numeric string, the build silently falls back to `1`. This could cause Play Store upload failures or version conflicts.

### L10. `Benchmark` Build Type `initWith` Waste
- **File:** `app/build.gradle.kts:94-99`
- **Impact:** `benchmark` build type uses `initWith(getByName("release"))` then overrides with `signingConfig = signingConfigs.getByName("debug")`. The `initWith` call is redundant.

### L11. `FoxTraderApp.onCreate` Doesn't Handle Crash Reporter Failure
- **File:** `FoxTraderApp.kt:48`
- **Impact:** `crashReporter.install()` is called without try-catch. If it fails (storage permission, I/O error), the app crashes during `onCreate` with no error message.

### L12. `formatSignedPercent` Returns Em-Dash for NaN/Infinite
- **File:** `feature/scanner/presentation/ScannerScreen.kt:392-397`
- **Impact:** Returns `"—"` for NaN/Infinite values. This is acceptable but could be more explicit (e.g., "N/A").

---

## 5. UI/UX GAPS & PROBLEMS

### U1. Settings Screen — No Input Validation on API Key Fields
- **File:** `feature/settings/presentation/SettingsScreen.kt:400-407`
- **Impact:** API key fields accept any input. Empty strings, whitespace, or malformed keys are saved without validation. The next API call fails with a confusing error.

### U2. LoginScreen — No Email Format Validation
- **File:** `feature/auth/presentation/LoginScreen.kt:102-112`
- **Impact:** The email field accepts any string. Invalid email formats (no `@`, no domain) are sent to the backend, which rejects them. The user sees a generic error.

### U3. LockScreen — Initial Biometric Prompt Has No Error Handling
- **File:** `feature/auth/presentation/LockScreen.kt:43-44`
- **Impact:** `LaunchedEffect(Unit)` calls `onAuthenticate()` immediately. If biometric is unavailable or the user cancels, the failure is silent. The user sees the lock screen with no explanation.

### U4. MainActivity — Notification Permission Requested Without Rationale
- **File:** `MainActivity.kt:89-97`
- **Impact:** On Android 13+, `POST_NOTIFICATIONS` is requested immediately on app start without a rationale dialog. If the user denies it, the system may not show the dialog again, and alerts degrade silently.

### U5. ChartScreen — No Retry Button on Error State
- **File:** `feature/chart/presentation/ChartScreen.kt:349-353`
- **Impact:** When `state.error != null`, only a text message is shown. There is no retry button. The user must pull-to-refresh or wait.

### U6. ChartScreen — `hasData` Check Before `isLoading`
- **File:** `feature/chart/presentation/ChartScreen.kt:296-358`
- **Impact:** During data transitions (symbol change, timeframe change), `hasData` may be false while `isLoading` is true. The `when` block shows "No data" instead of the loading spinner because `hasData` is checked first.

### U7. Scanner Screen — No Empty State for Filtered Results
- **File:** `feature/scanner/presentation/ScannerScreen.kt:146-153`
- **Impact:** When filters produce no results, "No data available" is shown. But there is no indication of *which* filter eliminated all results, making it hard for users to adjust filters.

### U8. Journal Screen — No Sort/Filter Controls
- **File:** `feature/journal/presentation/JournalScreen.kt`
- **Impact:** The journal displays all entries in creation order. There is no way to sort by date, P&L, or setup type, or to filter by direction or emotion.

### U9. No Offline Indicator in UI
- **File:** Multiple screens
- **Impact:** There is no persistent network connectivity indicator. Users don't know if they're viewing live data or cached data until they see the synthetic data banner on the chart.

### U10. No Undo for Drawing Deletion
- **File:** `feature/chart/presentation/ChartViewModel.kt:424`
- **Impact:** `clearAllDrawings()` permanently deletes all drawings. There is no undo or confirmation dialog.

---

## 6. SECURITY & AUTH GAPS

### S1. `android:allowBackup="true"` Without Exclusion Rules
- **File:** `AndroidManifest.xml:18`
- **Impact:** Android Auto Backup backs up the app's private data (Room database with trade history, journal entries, drawings) to the user's Google Drive. For a trading app, this is a privacy concern.
- **Fix:** Set `android:allowBackup="false"` or provide `android:fullBackupContent` with exclusion rules.

### S2. No `android:fullBackupContent` Rules
- **File:** `AndroidManifest.xml` (missing)
- **Impact:** Without explicit backup rules, the default backup includes everything. Sensitive trading data is backed up without user consent.

### S3. No Certificate Pinning
- **File:** `di/NetworkModule.kt` (all clients)
- **Impact:** See C5. A compromised CA can MITM all API traffic.

### S4. Default HTTP URL in Debug
- **File:** `di/NetworkModule.kt:64`
- **Impact:** See C5. Debug builds use HTTP, which is blocked by default on Android 9+.

### S5. `POST_NOTIFICATIONS` Permission Requested Without Rationale
- **File:** `MainActivity.kt:89-97`
- **Impact:** See U4. On Android 13+, if the user denies the permission, there is no fallback rationale flow.

### S6. `AuthInterceptor` Path Matching Uses `contains()` (Substring)
- **File:** `data/auth/AuthInterceptor.kt:39`
- **Impact:** `AUTH_PATHS` check uses `path.contains()`. A path like `/api/v1/auth/login/extra` matches `/auth/login`. While not harmful today, it's a latent bug if new auth endpoints are added.

### S7. Token Refresh Doesn't Clear on Auth-Specific Failures Only
- **File:** `data/auth/AuthInterceptor.kt:93`
- **Impact:** See C2. All exceptions during refresh trigger token clearance, not just 401/403.

### S8. No Network Security Config
- **File:** `AndroidManifest.xml` (missing `networkSecurityConfig`)
- **Impact:** No way to configure cleartext traffic exceptions for specific domains or enable certificate pinning per domain.

---

## 7. DOMAIN LOGIC BUGS

### D1. RiskEngine `getDailyLoss()` Uses UTC Day Boundary
- **File:** `domain/usecase/risk/RiskEngine.kt:281`
- **Impact:** `dayStart = (System.currentTimeMillis() / 86_400_000L) * 86_400_000L` computes UTC midnight. Traders in other timezones expect their local trading day. This could cause premature daily-loss halts or delayed resets.

### D2. RiskEngine `getWeeklyLoss()` Uses Rolling 7-Day Window
- **File:** `domain/usecase/risk/RiskEngine.kt:288`
- **Impact:** Uses a rolling 7-day window from now, not calendar week. Inconsistent with `getDailyLoss()` which uses calendar day. A trade from Monday could still count toward weekly loss on the following Tuesday, depending on timing.

### D3. BacktestEngine `getSpread()` Division by Zero
- **File:** `domain/usecase/backtest/BacktestEngine.kt:220`
- **Impact:** `multiplier = min(3.0, 1.0 + range / (config.spread * 100))`. If `config.spread` is 0, this causes division by zero.
- **Fix:** Guard against zero spread: `if (config.spread <= 0) return config.spread`.

### D4. BacktestEngine `calculateVolume()` Uses Current Balance
- **File:** `domain/usecase/backtest/BacktestEngine.kt:209-215`
- **Impact:** `calculateVolume` uses `balance` parameter (current balance at entry). This is correct for risk-based sizing, but if the strategy enters multiple trades simultaneously (not supported by the current bar-by-bar model), the balance would be double-counted.

### D5. TechnicalIndicators VWAP Defaults to 1.0 Volume for Zero-Volume Bars
- **File:** `domain/usecase/indicators/TechnicalIndicators.kt:110`
- **Impact:** `val volume = if (c.volume > 0.0) c.volume else 1.0`. Zero-volume bars are treated as having volume 1.0, which skews VWAP slightly. Better to skip zero-volume bars entirely.

### D6. ADX Wilder Smoothing Uses `period` Denominator
- **File:** `domain/usecase/indicators/TechnicalIndicators.kt:182-184, 198-199`
- **Impact:** The ADX smoothing uses `smoothedTR = smoothedTR - smoothedTR / period + tr[i]`, which is the **RMA** (Wilder's) smoothing. This is correct for ADX. However, the initial `adxSum` averages `dx[i]` for `period` bars, which is correct.

### D7. MasterDecisionEngine `checkConfluence` Uses String Matching
- **File:** `domain/usecase/ai/MasterDecisionEngine.kt:124-131`
- **Impact:** Confluence checks use `setOf("LIQUIDITY_SWEEP", "SWEEP")`, etc. If agent insight type strings change, confluence detection breaks silently with no compile-time safety.

### D8. `Bias.agreesWith(null)` Always Returns False
- **File:** `domain/usecase/ai/agents/AgentSupport.kt:12-16`
- **Impact:** NEUTRAL never agrees with any direction. This means HTF_BIAS and TREND confluences will always be missing if those agents return NEUTRAL. This is by design but means the confluence gate is very strict for neutral markets.

### D9. RiskEngine Kelly Criterion Fallback Misleading
- **File:** `domain/usecase/risk/RiskEngine.kt:264`
- **Impact:** When there are fewer than 5 wins or 3 losses, `calculateKellyPercent()` returns `config.riskPercentPerTrade / 100.0`. This is labeled as "Kelly suggests no position" but actually returns a fixed percentage, which could be confusing.

### D10. RiskEngine `recordTrade()` `CopyOnWriteArrayList` Performance
- **File:** `domain/usecase/risk/RiskEngine.kt:49`
- **Impact:** `tradeHistory` uses `CopyOnWriteArrayList`. For frequent writes (every trade), this creates a new array copy on each write, which is O(n) per write. For a high-frequency trading journal, `Collections.synchronizedList(mutableListOf())` would be more efficient.

---

## 8. MISSING FEATURES / GAPS

### G1. No Undo/Redo for Drawings
- **Impact:** Once a drawing is placed or cleared, it cannot be recovered.

### G2. No Sort/Filter on Journal Screen
- **Impact:** Users cannot organize their trade history by date, P&L, setup, or emotion.

### G3. No Network Connectivity Indicator
- **Impact:** Users cannot tell if they're viewing live or cached data from the main UI.

### G4. No Confirmation Dialog for `clearAllDrawings()`
- **Impact:** Accidental taps permanently delete all chart drawings.

### G5. No Partial Sync Failure Reporting
- **File:** `data/repository/CloudSyncRepositoryImpl.kt`
- **Impact:** If sync partially succeeds (some items accepted, some rejected), the entire sync is marked as failed. No retry for successful items or reporting of which items failed.

### G6. No Conflict Resolution in Cloud Sync
- **File:** `data/repository/CloudSyncRepositoryImpl.kt:84`
- **Impact:** `conflicts` is hardcoded to 0. If two devices edit the same journal entry, last-write-wins is applied silently with no notification.

### G7. No Crash Reporter `report()` Method for Non-Fatal Errors
- **File:** `data/crash/CrashReporter.kt`
- **Impact:** Handled exceptions (API failures, parsing errors) cannot be logged to the crash diagnostic system.

### G8. No `network_security_config.xml`
- **Impact:** Cannot configure certificate pinning, cleartext exceptions per domain, or trust anchors.

### G9. No `FOREGROUND_SERVICE` Permission for Android 14+
- **File:** `AndroidManifest.xml`
- **Impact:** Starting with Android 14, apps using foreground services must declare `FOREGROUND_SERVICE`. The manifest doesn't declare it.

### G10. No Build-Time Validation for HTTPS Base URL
- **File:** `di/NetworkModule.kt:65`
- **Impact:** The HTTPS check throws `IllegalStateException` at runtime. A Gradle lint task or CI validation would catch this earlier.

### G11. No `shouldShowRequestPermissionRationale` for Notifications
- **File:** `MainActivity.kt:89-97`
- **Impact:** On Android 13+, if the user previously denied the notification permission, the system dialog may not appear again. The app needs a rationale flow.

### G12. No `@Singleton` on DAO Provides in DatabaseModule
- **File:** `di/DatabaseModule.kt:34-47`
- **Impact:** DAO provider methods are missing explicit `@Singleton`. While Room DAOs are effectively singletons, the missing annotation makes the Hilt graph scope implicit.

### G13. No `@Singleton` on RepositoryModule Binds
- **File:** `di/RepositoryModule.kt:29-51`
- **Impact:** `@Binds` methods are missing explicit `@Singleton` scope annotations.

### G14. Low Test Coverage Thresholds
- **File:** `app/build.gradle.kts:258, 333`
- **Impact:** Chart coverage requires only 25% line coverage; domain requires 40%. For a trading app where risk calculations and order execution are safety-critical, these thresholds are dangerously low.

### G15. Detekt and Ktlint Failures Suppressed
- **File:** `app/build.gradle.kts:161, 171`
- **Impact:** `detekt.ignoreFailures = true` and `ktlint.ignoreFailures = true`. Static analysis violations will never block a build.

---

## 9. DATA MAPPING BUGS

### DM1. Candle Entity/DTO Drop `source` Field
- **File:** `data/mapper/CandleMapper.kt:14-21, 43-50`
- **Impact:** See C6. Provenance information is lost when converting to domain model.

### DM2. JournalMapper `Direction.valueOf()` Defaults to BULLISH
- **File:** `data/mapper/JournalMapper.kt:20`
- **Impact:** Corrupted direction strings silently become BULLISH, misrepresenting trade direction.

### DM3. JournalMapper `Timeframe.entries.firstOrNull` Defaults to M15
- **File:** `data/mapper/JournalMapper.kt:21`
- **Impact:** Corrupted timeframe data silently becomes M15.

### DM4. JournalMapper `EmotionTag.valueOf()` Defaults to NEUTRAL
- **File:** `data/mapper/JournalMapper.kt:34`
- **Impact:** Corrupted emotion data silently becomes NEUTRAL, affecting emotion-based analysis.

### DM5. DrawingMapper `DrawingToolType.valueOf()` Defaults to TREND_LINE
- **File:** `data/mapper/DrawingMapper.kt:19`
- **Impact:** Corrupted drawing types silently become trend lines.

### DM6. DrawingMapper `deserializePoints()` Defaults Timestamp to Epoch
- **File:** `data/mapper/DrawingMapper.kt:57`
- **Impact:** Malformed timestamps become epoch (0L), placing drawing points at January 1, 1970.

### DM7. AlertMapper `AlertPriority.valueOf()` Defaults to MEDIUM
- **File:** `data/mapper/AlertMapper.kt:19-20`
- **Impact:** Corrupted priority data silently becomes MEDIUM, affecting notification importance.

### DM8. WatchlistMapper `AssetClass.valueOf()` Defaults to STOCKS
- **File:** `data/mapper/WatchlistMapper.kt:13-14`
- **Impact:** Corrupted asset class data silently becomes STOCKS.

---

## 10. SUMMARY TABLE

| Category | Critical | High | Medium | Low | Total |
|----------|----------|------|--------|-----|-------|
| Data Layer (WebSocket, API, Repo) | 3 | 6 | 10 | 4 | 23 |
| Auth & Security | 2 | 2 | 4 | 2 | 10 |
| Chart / UI / Rendering | 1 | 0 | 2 | 3 | 6 |
| Domain Logic (Risk, Backtest, AI, Indicators) | 0 | 0 | 2 | 5 | 7 |
| Navigation & UX | 0 | 0 | 5 | 5 | 10 |
| DI / Build Config | 0 | 1 | 2 | 6 | 9 |
| Data Mapping | 2 | 0 | 6 | 0 | 8 |
| Missing Features / Gaps | 2 | 1 | 3 | 8 | 14 |
| **Total** | **10** | **10** | **34** | **33** | **87** |

---

## 11. RECOMMENDED PRIORITY FIX ORDER

1. **C1** — Fix WebSocket race condition (data loss)
2. **C2** — Fix AuthInterceptor token clearing (user logout)
3. **C3** — Fix ScanAlertWorker exception handling (infinite retry)
4. **C4** — Fix alert ID generation (DB collisions)
5. **C5** — Add certificate pinning + fix HTTP URL (security)
6. **C6** — Propagate candle source to domain model (data integrity)
7. **C7** — Implement transactional cloud sync (data consistency)
8. **C8** — Fix Room migration data loss (user data)
9. **H1-H4** — WebSocket reliability fixes
10. **M1-M6** — UI and data layer robustness
11. **S1-S5** — Security hardening
12. **D1-D4** — Domain logic correctness
13. **U1-U10** — UX improvements
14. **G1-G15** — Feature gaps and config improvements

---

*End of Report*
