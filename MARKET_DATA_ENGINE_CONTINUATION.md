# FoxTrader — Real-Time Market Data Engine: CONTINUATION HANDOFF

> **How to resume:** Read this top-to-bottom, then implement the next
> un-checked **Block** under §6. Each block lists the files to create, the tests
> to write, and acceptance criteria. Commit per block with a Conventional Commit.
> When the user says **"continue"**, pick up at the first unchecked block.
>
> **Last updated:** 2026-07-28 · **Branch:** `arena/019fa759-foxtrader`
> **Base:** `e04c97e` (main) · **Governing docs:** `DEVELOPMENT.md` (bible),
> `ENHANCEMENT_MASTERPLAN.md`, `.kiro/steering/foxtrader-directive.md`.

---

## 1. Mission (Sprint 1 — Enterprise Real-Time Market Data Engine)

Build an institutional-grade, modular, scalable, testable, Android-optimized
real-time market-data engine in this existing repo (no new project, no docs-only).

Required capabilities:
- **WebSocket Engine:** auto-reconnect, heartbeat, ping/pong, exponential
  backoff, failover, connection-state management.
- **Tick Engine:** tick-by-tick processing, buffering, aggregation, compression,
  replay.
- **Candle Engine:** build M1 M2 M3 M5 M10 M15 M30 H1 H4 D1 W1 MN from ticks.
  **No repainting.**
- **Providers:** abstraction; support Dukascopy, Binance, future providers; swap
  without changing business logic.
- **Offline-first:** local cache, synchronization, gap filling, missing-candle
  recovery, versioning.
- **Performance:** 60–120fps-capable pipeline, minimal allocations, background
  processing, coroutines/Flow, object pooling where appropriate.
- **Testing:** unit, integration, stress, reconnect, offline, large-dataset.

**Hard rules:** no placeholder code, no TODO comments, keep the repo compiling,
refactor when necessary, implement feature-by-feature.

---

## 2. Sandbox + workflow constraints (IMPORTANT)

- **No JDK / Android SDK in the sandbox.** `./gradlew` cannot run here. apt/Debian
  repos are unreachable; npm registry works.
- Therefore verify changes by:
  1. **JS algorithm ports** (Node is available) for any non-trivial logic and for
     test expectations (off-by-one checks especially).
  2. **Rigorous inspection**: match existing imports/conventions exactly, check
     brace balance, confirm every `R.string.*`/resource ref resolves, confirm
     Kotlin version supports the APIs (project uses **Kotlin 2.0.20**,
     **kotlinx-serialization 1.7.3**, **coroutines 1.9.0** — so `data object`,
     enum `entries`, `Flow`/`StateFlow`/`SharedFlow` are all fine).
  3. **GitHub CI** is the authoritative compile/test runner — it has the full
     Android toolchain.
- **Bias toward framework-light, pure-Kotlin code with JUnit4 tests** (testable,
  low compile risk). Keep coroutine/OkHttp/Room code canonical and minimal.
- **Push is currently blocked** (see §4). Commits stay local but file changes are
  also preserved in the workspace snapshot each turn.

### Test/style conventions (match these)
- JUnit4: `org.junit.Test`, `org.junit.Assert.*`, backtick test names, a KDoc on
  the test class explaining the trap being guarded.
- Test root: `app/src/test/java/...`. Coroutines tests use
  `kotlinx.coroutines.test.runTest` + `Flow.toList()/asFlow()`.
- Conventional Commits, one logical change per commit.
- Every sprint pass should also append an Improvement-Log appendix to
  `DEVELOPMENT.md` (Appendix AG onward), evidence-first style.

---

## 3. Architecture invariants (do NOT violate)

1. **No repainting.** A sealed candle is emitted exactly once and never mutated.
   Late/out-of-order ticks for a sealed bucket are rejected, not folded. Gaps are
   *not* fabricated into empty candles. (See `CandleBuilder`.)
2. **Keep the shared `Timeframe` enum untouched.** The engine owns
   `MarketTimeframe` (a superset adding M2/M3/M10) and bridges losslessly via
   `toChartTimeframe()`/`fromChart()`. Adding entries to `Timeframe` would break
   ~6 exhaustive `when(timeframe)` blocks in the data-source layer.
3. **Provenance.** Synthetic data must stay distinguishable (`CandleSource`,
   `SourcedCandles`). Don't conflate synthetic with real in the cache.
4. **Allocation-free hot path.** Tick ingest uses pooled `MutableTick`; the
   multi-TF fan-out is inline + array-backed; buffers are bounded rings.
5. **Provider abstraction.** Business logic depends only on `MarketDataProvider`
   + `TickDecoder` + `WebSocketTransport` interfaces — never a concrete feed.
6. **Bounded memory.** No unbounded buffers/queries (masterplan finding A5).

---

## 4. PUSH BLOCKER (resolve before CI can run)

GitHub rejects the push:
> refusing to allow a GitHub App to create or update workflow
> `.github/workflows/android.yml` without `workflows` permission

Commit `9d9e028` (Sprint 10 CI hardening) touches `.github/workflows/`, and the
GitHub App lacks the **`workflows`** scope, so the *entire* branch push is
rejected (the workflow commit is an ancestor of everything after it).

**Fix:** grant the GitHub App the `workflows` permission (or reconnect GitHub in
Arena with that scope). Then:
```
git push origin arena/019fa759-foxtrader
```
All queued commits push at once, and the `arena/**` trigger added in `9d9e028`
will immediately build the branch + run the unit tests in CI.

### Queued (unpushed) commits on this branch
1. `9d9e028` ci(sprint-10): harden CI matrix (arena branches, release R8 gate, Gradle cache)
2. `e25c06c` refactor(sprint-10): externalize Alerts inbox strings + content descriptions
3. `4c49d2d` feat(market-data): engine foundation (tick, candle, connection, provider, cache)
4. `ca710db` feat(market-data): transport seam, reconnect orchestrator, JSON tick decoder
5. *(this handoff commit)*

---

## 5. What is ALREADY implemented (`com.foxtrader.app.data.market.*`)

21 production + 16 test files, all unit-tested, pure-Kotlin (compiles cleanly by
inspection; logic verified via JS ports).

- **model/** — `MarketTimeframe` (12 TFs; UTC floor-aligned intraday, Monday-aligned
  weeks, calendar months via java.time), `Tick`/`TickSide`, `AggregatedTick`
  (→`Candle`), `CandleUpdate`.
- **tick/** — `MutableTick` + `TickPool` (object pooling), `TickBuffer` (bounded
  ring, zero-alloc steady state, replay via `snapshot()`/`drainTo()`),
  `TickAggregator` (time-interval compression, no-repaint).
- **candle/** — `CandleBuilder` (single-TF, hard no-repaint),
  `MultiTimeframeCandleEngine` (inline array-backed fan-out to all 12 TFs),
  `CandleFlow` (`Flow<Tick>.buildCandles(...)` → `Flow<CandleUpdate>`, sealed bars only).
- **connection/** — `ReconnectPolicy` (exp backoff + bounded jitter + GIVE_UP
  sentinel), `HeartbeatMonitor` (ping/pong, injectable clock),
  `ConnectionStateMachine` (guarded FSM over existing `ConnectionState`),
  `FailoverRouter`, `ReconnectOrchestrator` (composed decision engine:
  `Retry(endpoint,delay)` / `Failover(endpoint)` / `GiveUp`; fresh ladder per endpoint).
- **transport/** — `WebSocketTransport` interface + `TransportEvent`
  (Opened/Text/Closed/Failed; connect returns a cold `Flow<TransportEvent>`).
- **decode/** — `TickDecoder` (fun interface), `JsonTickDecoder` +
  `TickFieldMapping` (defaults to Binance `aggTrade`; string-or-number fields;
  event-type filter; fallback symbol; configurable side polarity; malformed → null).
- **provider/** — `MarketDataProvider` interface + `ProviderCapability`.
- **cache/** — `CandleCache` (TreeMap-backed, versioned, gap-detecting
  `missingBucketStarts()`, no-repaint upserts → `InsertResult`).

**Existing repo code to integrate with (don't duplicate):**
`data/remote/websocket/{MarketWebSocket,BinanceWebSocket,BybitWebSocket,ProviderMarketWebSocket}.kt`,
`data/remote/api/{BinanceDataSource,BybitDataSource,AlphaVantageDataSource}.kt`,
`domain/sdk/provider/{DataProviderAdapter,DukascopyAdapter(stub)}.kt`,
`data/repository/MarketRepositoryImpl.kt`, `data/local/dao/CandleDao.kt`,
`data/local/entity/CandleEntity.kt`, `di/{NetworkModule,WebSocketModule,DatabaseModule}.kt`,
`domain/model/{Candle,Timeframe,ConnectionState,CandleSource,DataProvider}.kt`.

---

## 6. CONTINUATION BLOCKS (do in order; check off as completed)

### ☐ Block 1 — `RealtimeConnection` (the WebSocket Engine driver)
The coroutine driver that turns the transport seam + orchestrator + heartbeat
into a resilient connection.
- **Create** `data/market/transport/RealtimeConnection.kt`:
  - Ctor: `endpoints: List<String>`, `transportFactory: (String) -> WebSocketTransport`,
    `decoder: TickDecoder`, `orchestrator: ReconnectOrchestrator`,
    `scope: CoroutineScope`, heartbeat interval/timeout, ping frame text,
    injectable `delayFn: suspend (Long) -> Unit` and `clock: () -> Long` for tests.
  - Expose `state: StateFlow<ConnectionState>` and `ticks: Flow<Tick>` (and/or raw
    `messages: SharedFlow<String>`).
  - Loop: `orchestrator.beginConnect()` → collect `transport.connect(url)`;
    on `Opened` → `orchestrator.onConnected()`, start heartbeat watchdog;
    on `Text` → `decoder.decode` → emit tick; on `Closed(abnormal)`/`Failed` →
    `orchestrator.onDisconnected()` → execute the `Decision`
    (`Retry`→`delayFn(delay)` then reconnect; `Failover`→switch endpoint;
    `GiveUp`→terminal). Heartbeat watchdog: periodic `transport.send(ping)` +
    `HeartbeatMonitor`; on timeout, tear down the socket to force reconnect.
  - `connect()` / `disconnect()` lifecycle; idempotent.
- **Create** `data/market/transport/FakeWebSocketTransport.kt` (test double:
  scriptable events, records sent frames, controllable close/failure). Place under
  `app/src/test/.../transport/` (or `src/main` if reused by integration tests).
- **Tests** `RealtimeConnectionTest` (runTest + virtual time): connects → CONNECTED
  + emits decoded ticks; drop → RECONNECTING → reconnect after backoff; repeated
  failure → failover to next endpoint; total failure → terminal; heartbeat timeout
  forces reconnect; clean close stops without reconnect.
- **Acceptance:** all green by inspection/JS-port where logic is non-trivial;
  no TODOs; canonical coroutines only.

### ☐ Block 2 — `OkHttpWebSocketTransport`
- **Create** `data/market/transport/OkHttpWebSocketTransport.kt`: implements
  `WebSocketTransport` over `okhttp3.WebSocket` + `WebSocketListener`, bridging
  OkHttp callbacks into the `Flow<TransportEvent>` via `callbackFlow`. Map
  `onOpen`→`Opened`, `onMessage(String)`→`Text`, `onClosing/onClosed`→`Closed`,
  `onFailure`→`Failed`. `send()`→`webSocket.send(text)`. `close()`→`webSocket.close(code,reason)`.
- Use the existing OkHttp client from `di/NetworkModule` (inject `OkHttpClient`).
- **Tests:** unit-test the callback→event mapping with a fake `WebSocket` if
  practical; otherwise keep the adapter thin and rely on Block 1's tests + CI.
- **Acceptance:** OkHttp never leaks above `WebSocketTransport`.

### ☐ Block 3 — Concrete providers behind `MarketDataProvider`
- **Binance:** `data/market/provider/BinanceMarketDataProvider.kt` — live ticks via
  `RealtimeConnection` against the Binance aggTrade WS URL + `JsonTickDecoder.binanceAggTrade()`;
  `fetchCandles` via REST (reuse `BinanceDataSource`/klines mapping) for gap-fill.
  Reuse existing `BinanceWebSocket` URL/subscription logic where possible.
- **Dukascopy:** `data/market/provider/DukascopyMarketDataProvider.kt` — implement
  the real binary tick-history fetch (Dukascopy serves per-day gzipped binary tick
  files), replacing the `DukascopyAdapter` stub. Add a `DukascopyTickDecoder`
  (binary → `Tick`). Historical-first provider (no live WS) →
  `capabilities.supportsLiveTicks = false`.
- **Registry:** `MarketDataProviderFactory` mapping `DataProvider` enum → concrete
  provider, so switching is a binding change.
- **Tests:** decoder/parsing unit tests; provider tests with fakes; a test proving
  identical business logic runs against Binance and Dukascopy fakes.
- **Acceptance:** `DataProvider.DUKASCOPY.implemented` can move toward true once
  the fetch path exists (update the enum + Settings gating accordingly).

### ☐ Block 4 — Offline-first persistence + sync
- Persist `CandleCache` to Room (extend `CandleEntity`/`CandleDao`; honor the
  `source` provenance column from Sprint 6). Add `MarketDataSync` use case:
  - read cache → `missingBucketStarts()` → `provider.fetchCandles(...)` → upsert
    (gap fill) → bump version.
  - merge live ticks (from Block 1) into cache + emit confirmed candles.
  - **missing-candle recovery** on reconnect: detect discontinuities, backfill.
- **Versioning:** expose cache `version` to observers; persist a schema-version/
  series-version marker.
- **Tests:** gap-fill round-trip, recovery after simulated drop, version monotonicity,
  provenance preserved (no synthetic laundered as live), large-dataset sync.
- **Acceptance:** offline open shows cached bars; reconnect backfills exactly the gaps.

### ☐ Block 5 — `RealtimeMarketDataEngine` + DI + integration
- `data/market/RealtimeMarketDataEngine.kt`: the façade tying
  provider → `RealtimeConnection` → ticks → `MultiTimeframeCandleEngine` →
  `CandleCache`/Room → `Flow<CandleUpdate>` per (symbol, timeframe). Manages
  lifecycle, symbol/timeframe switching, and provenance labeling.
- **DI:** Hilt modules binding `OkHttpWebSocketTransport`, providers,
  `RealtimeMarketDataEngine`. Wire into the existing chart/`MarketRepository`
  path so the chart can consume live, no-repaint candles.
- **Tests:** engine-level integration tests with fakes; ensure synthetic fallback
  stays gated (masterplan A4) — engine never silently feeds synthetic to the AI.

### ☐ Block 6 — Performance proof + UI surfaces
- Allocation audit of the ingest path; ensure pooled/zero-alloc claims hold.
- Macrobenchmark journey for tick→frame latency (reuse the `:benchmark` module).
- Surface live connection state + multi-timeframe live bars in the chart UI;
  externalize any new strings (Sprint 10.3 convention).

### ☐ Block 7 — (Optional) Finish Sprint 10 hygiene
- Broaden detekt/ktlint from chart-only to app-wide (ratchet gradually; baseline
  existing violations). Widen Jacoco coverage toward the 80% `domain/` floor.
- Continue string externalization (Settings, Scanner, Portfolio, Backtest, Journal).
- These are independent of the engine and can interleave.

---

## 7. Definition of done (per masterplan working agreements)

- Every block ends with a green `:app:assembleDebug` **and**
  `:app:testDebugUnitTest` (enforced by CI once the push blocker is cleared).
- Every feature ships with unit tests; every screen a UI test (Sprint 9+).
- Conventional Commits, one logical change per commit.
- Append a `DEVELOPMENT.md` appendix (AG, AH, …) per pass, evidence-first.
- No placeholder code, no TODOs. Refactor before adding.

---

## 8. Quick resume checklist for the next session

1. `git log --oneline -8` and `git status` — confirm branch + queued commits.
2. Read this file (§5 done, §6 next block) and `DEVELOPMENT.md` Appendix AF (pointer).
3. Resolve/confirm the push blocker (§4) if CI visibility is needed.
4. Implement the first unchecked Block in §6; verify per §2; commit; append appendix.
5. Repeat on each "continue".
