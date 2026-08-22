# FOX Trader — Phase 3 Completion & Full-App Audit

Date: 2026-08-21

## Executive status

The Phase 3 chart-validation workflow is now completed for the scope requested in this iteration: strategies can be backtested from the live chart, historical real-data ranges can be prefetched, completed signals/trades are projected back onto the chart, and the chart reports wins, losses, breakevens and professional performance metrics. The audit also hardened market-data provenance, authentication, release networking/signing, telemetry privacy and lifecycle cleanup.

A full Android Gradle build/lint/instrumented-test claim is intentionally **not** made in this environment because the project wrapper requires Gradle 8.9 and `services.gradle.org` cannot be resolved here. Backend tests, XML parsing, standalone domain execution and Kotlin structural syntax checks were run instead; see Validation Matrix.

## Phase 3 completed work

### On-chart backtesting

- Dedicated Backtest panel directly on the chart.
- Built-in strategies and saved visual Strategy Builder blueprints are selectable.
- Range selector: `Loaded`, `1M`, `3M`, `6M`, `1Y`.
- Real historical data is prefetched backwards in bounded pages before a ranged run.
- 20,000-bar safety cap prevents an accidental small-timeframe research run from exhausting memory.
- If the provider cannot cover the entire requested period, the result is explicitly marked `PARTIAL RANGE` rather than pretending the period is complete.
- A symbol/timeframe consistency guard cancels a historical prefetch if the chart context changes while a provider request is in flight, preventing cross-symbol/cross-timeframe candle contamination.

### Signal/trade projection on chart

- Bullish/bearish entry arrows are rendered on the originating bar.
- Entry-to-exit connector is rendered for a completed backtest trade.
- `W`, `L`, `B` badges identify winner, loser and breakeven exits.
- Backtest markers can be independently toggled without disabling live strategy signals.
- Timestamp fallback prevents marker drift after older candles are prepended.
- Results become visibly stale (`NEW BARS — RERUN`) if the source series changes after a run.
- Replay mode hides completed future outcomes so a historical W/L result cannot leak future information into candle-by-candle replay.

### Backtest statistics on chart

The chart result state now exposes:

- total executed signals/trades;
- wins / losses / breakevens;
- win rate;
- net P/L;
- profit factor;
- return percentage;
- maximum drawdown percentage;
- expectancy;
- average R multiple;
- tested closed-bar count;
- tested-from timestamp;
- equity curve / mini sparkline;
- requested history range and coverage completeness.

### No-repaint / data-integrity rules

- Executable backtests reject synthetic/simulated market data.
- Backtests use time bars, not derived Renko/Heikin-Ashi execution prices.
- The currently forming time candle is excluded from research runs.
- Strategy functions receive only the prefix available at each historical bar; future candles are not provided.
- The same `StrategyFunction` contract is shared by chart strategy signals and the backtester.
- LIT X strategy resolution now carries the **actual symbol and chart/backtest timeframe** instead of hard-coding H1, removing a live/backtest context mismatch.

## Critical and high-impact audit fixes

### 1. Market-data provenance: synthetic backend data could be mislabeled LIVE — fixed

The backend previously returned candles without source metadata. The Android default backend path then persisted a successful response as `LIVE`. Because the backend development default is the `sample` provider, generated data could be presented/persisted as live market data in some fallback routes.

Fix:
- Backend candle payload now returns `provider` and `source`.
- `sample` maps to `source=synthetic`; real configured providers map to `source=live`.
- Android DTO preserves provenance.
- Ambiguous legacy backend payloads fail closed to synthetic rather than being assumed live.
- A backend response marked sample/synthetic is rejected as a successful live refresh.
- Backend connection test no longer reports a sample-data server as a valid real-data connection.
- Older-history paging also preserves source, so Phase 3 prefetch cannot silently ingest sample history into an executable backtest.

### 2. Historical prefetch race — fixed

A provider request could finish after the user changed symbol/timeframe. The prefetch now snapshots symbol/timeframe and verifies the context before and after each remote page before merging data.

### 3. Chart lifecycle WebSocket cleanup ANR risk — fixed

`ChartViewModel.onCleared()` no longer performs a blocking WebSocket disconnect on the lifecycle/main thread. Cleanup is launched on an independent dispatcher with a bounded timeout.

### 4. Concurrent authentication refresh race — fixed

Single-use refresh tokens could be consumed twice when multiple HTTP requests received 401 concurrently. Before refreshing, the interceptor now checks whether another request already installed a newer access token and retries with it. Refresh remains single-flight.

### 5. Release backend-host bearer-token redirection — fixed

A mutable Settings backend URL could previously redirect an authenticated request to another HTTPS host in a release build, outside the compiled certificate-pinning origin. Release now refuses cross-host dynamic overrides. Changing the production host requires a newly signed build. Debug remains flexible for development.

### 6. Release signing fallback — fixed

Release no longer silently falls back to the debug key. Production signing is configured only when the keystore exists and all required credential values are nonblank; otherwise release remains unsigned and cannot be accidentally shipped as debug-signed.

### 7. Cleartext traffic policy — hardened

- Main/release manifest: `usesCleartextTraffic=false`.
- Debug manifest explicitly permits cleartext for local emulator/development workflows.
- Release backend base URL validation requires HTTPS.

### 8. HTTP and crash telemetry privacy — hardened

- Backend HTTP logging is `BASIC` in debug and `NONE` in release; body logging is not enabled.
- Authorization/cookie-sensitive logging is not exposed through body logs.
- Crash exception reporting sanitizes exception messages.
- Breadcrumb telemetry no longer forwards arbitrary caller-controlled raw text; it records bounded event length and a sanitized category.
- No-op/local crash logging does not print breadcrumb content.

## Security/static audit summary

Checked production sources/manifests for common high-risk patterns:

- custom trust-all `X509TrustManager`: not found;
- permissive `HostnameVerifier`: not found;
- WebView JavaScript bridge (`addJavascriptInterface`): not found;
- `Runtime.exec` / `ProcessBuilder`: not found;
- dynamic dex/class loading: not found;
- world-readable Android storage: not found;
- direct MD5/SHA-1 digest use: not found;
- destructive Room migration call: not found (the only textual occurrences are explanatory comments/tests);
- launcher activity is the intended exported component; startup provider remains non-exported;
- main manifest permissions remain limited to Internet, network state and notifications for this app surface.

No hard-coded production private key/API credential was identified by the source scan. Real secrets must still be supplied through the configured build/deployment secret mechanism and never committed.

## Validation matrix

| Check | Result |
|---|---|
| Backend `python -m compileall -q .` | PASS |
| Backend `pytest` | PASS — 82/82 tests |
| Android/benchmark XML parse | PASS — 13 parsed XML files, 0 errors |
| Main Kotlin `R.string` reference audit | PASS — 217 referenced names, 0 missing from app string resources |
| Standalone real `BacktestEngine` compile + run with minimal dependency stubs | PASS — `BACKTEST_SMOKE_PASS` |
| Modified Kotlin structural/parser smoke via `kotlinc` | PASS for syntax — 0 structural syntax diagnostics; full types cannot resolve without Android/Gradle classpath |
| Full Android Gradle unit/build/lint | BLOCKED by environment — wrapper tries Gradle 8.9 download and fails with `UnknownHostException: services.gradle.org` |
| ZIP integrity | Must be run after final packaging |

## Remaining limitations / production checklist

These are not hidden or presented as completed:

1. **MT4 historical paging:** older-history paging for the MT4 path is not implemented. A ranged chart backtest on MT4 can therefore be partial and should remain visibly marked partial.
2. **20k history cap:** a full year on small timeframes can exceed 20,000 candles (for example M15). The chart intentionally stops at the cap and labels incomplete coverage instead of risking memory pressure.
3. **Backtest methodology:** the current engine models spread/slippage/commission according to its existing tested contract, but the equity curve is realized-balance based rather than full mark-to-market floating-equity drawdown. Intratrade drawdown can therefore be understated. Treat this as a research-methodology limitation, not a broker-grade execution simulator.
4. **Certificate pins:** production pins are supported, but `FOXTRADER_CERT_PINS` must be populated for a pinned production deployment.
5. **Backend provider:** the backend defaults to `sample` for safe development. Production must explicitly configure a real provider and credentials. Android now prevents sample from masquerading as live.
6. **Multi-worker backend:** market cache/rate limiting are process-local. A horizontally scaled deployment should move shared state to Redis or an equivalent shared store.
7. **Static quality gate:** Detekt is configured with `ignoreFailures=true` during the current rollout. On a connected CI/build host, baseline the current findings and then make Detekt blocking before release.
8. **Visual custom Indicator Builder UI:** the app has an Indicator SDK/registry and configurable indicator/chart panel, but it does not yet contain a full visual formula/composition builder comparable to the existing Strategy Builder. This is a product feature gap, not a hidden crash defect.

## Required connected-build release gate

Before a Play/production release, run on a machine that can resolve the Gradle wrapper/dependencies:

```bash
./gradlew clean testDebugUnitTest lintDebug detekt ktlintCheck assembleDebug
./gradlew bundleRelease
```

Then perform device/instrumented migration tests and an end-to-end real-provider smoke on at least EURUSD, BTCUSDT and one stock/index route, verifying source labels, live timestamps, chart indicators, strategy signals, ranged backtest W/L/B markers and stale/partial-range guards.
