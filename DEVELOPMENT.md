# FoxTrader Engineering Bible

**The official, permanent engineering reference for the FoxTrader platform.**

> **Version:** 2.0 (Engineering Bible)
> **Applies to:** FoxTrader app `v0.1.0+` (`applicationId = com.foxtrader.app`)
> **Audience:** Every current and future FoxTrader engineer, and every AI agent that reads, writes, or reviews FoxTrader code.
> **Status:** Authoritative. When this document and a comment or ticket disagree, this document wins until it is formally amended via a pull request.

---

## How to read this document

This is not a tutorial and it is not a summary. It is the **specification of record** for how FoxTrader is designed, built, rendered, tested, secured, and shipped. It is written so that a senior engineer who has never seen the repository could rebuild any subsystem from this text alone, and so that an AI agent can make correct changes without guessing.

Conventions used throughout:

- **`RULE`** — a normative requirement. Violating a RULE is a defect and must block a pull request.
- **`WARNING`** — a known trap. Ignoring it will cause bugs, crashes, or performance regressions.
- **`PERF`** — a performance-critical note tied to a measurable budget.
- **`SECURITY`** — a security-critical note.
- **`NOTE`** — clarifying context or rationale.
- **`TROUBLESHOOT`** — a known failure mode and its fix.
- Code blocks are real, compilable Kotlin (or the reference TypeScript that Kotlin is being ported from) unless explicitly marked as pseudocode.

The word **"must"** is normative. **"Should"** is a strong recommendation. **"May"** is optional.

---

## Master Table of Contents

1. [Project Vision](#1-project-vision)
2. [Complete System Architecture](#2-complete-system-architecture)
3. [Android Engineering Standards](#3-android-engineering-standards)
4. [Chart Engine Bible](#4-chart-engine-bible)
5. [Trading Engine](#5-trading-engine)
6. [Institutional Trading Concepts](#6-institutional-trading-concepts)
7. [AI Architecture](#7-ai-architecture)
8. [Data Layer](#8-data-layer)
9. [Performance Engineering](#9-performance-engineering)
10. [Security](#10-security)
11. [UI Design System](#11-ui-design-system)
12. [Developer Workflow](#12-developer-workflow)
13. [QA Bible](#13-qa-bible)
14. [Deployment](#14-deployment)
15. [Future Roadmap](#15-future-roadmap)
- [Appendix A: Environment Setup & Prerequisites](#appendix-a-environment-setup--prerequisites)
- [Appendix B: Dependency Version Catalog](#appendix-b-dependency-version-catalog)
- [Appendix C: Troubleshooting Reference](#appendix-c-troubleshooting-reference)
- [Appendix D: Glossary](#appendix-d-glossary)

---

# 1. Project Vision

## 1.1 Mission

FoxTrader exists to put **institutional-grade market analysis in the pocket of the individual trader**. The mission is to compress the analytical capability of a professional trading desk — smart-money structure reading, multi-timeframe confluence, disciplined risk management, rigorous backtesting, and AI-assisted reasoning — into a single native Android application that runs at desktop-terminal quality on a phone.

FoxTrader is an **analysis and decision-support platform**, not a get-rich promise. Every feature is built to make the trader *more disciplined and better informed*, never to guarantee an outcome.

> **Disclaimer (canonical wording — reproduce verbatim in product copy):** FoxTrader is an educational and analytical tool. It helps you analyze markets and evaluate strategies using historical and simulated data. It does **not** promise future results or guaranteed profit.

## 1.2 Goals

**Product goals**

1. **A chart that never stutters.** A custom, GPU-accelerated candlestick engine that holds a 120 FPS target (and never drops below 60 FPS) with 100,000+ candles loaded, on mid-range 2022+ hardware.
2. **Non-repainting analysis.** Every analytical output — market structure, SMC objects, indicators, signals — is computed using only confirmed past data. A signal, once printed, never changes.
3. **Institutional concept coverage.** First-class support for SMC / ICT / LIT concepts: order blocks, fair value gaps, liquidity pools, market structure shifts, sessions/kill zones, premium/discount, OTE, AMD.
4. **Disciplined risk by default.** A risk engine that sizes positions, sets stops, and *refuses* trades that violate loss/drawdown/consecutive-loss limits.
5. **Trustworthy backtesting.** Bar-by-bar simulation with spread, slippage, and commission modeling, and a full metrics suite (Sharpe, Sortino, Calmar, profit factor, expectancy, R-multiples).
6. **AI as a reasoning partner.** A multi-agent AI layer that explains *why* a setup is or is not valid, gated by a decision engine that requires multi-factor confluence.
7. **Offline-first.** The app is fully functional without a network connection; the local database is the single source of truth.

**Engineering goals**

- **Zero-ambiguity architecture.** Clean layering (domain / data / presentation) with the domain layer completely free of Android/framework dependencies.
- **Deterministic, testable core.** All analytical engines are pure Kotlin, unit-testable without an emulator.
- **Reproducible builds.** Committed Gradle wrapper, a single version catalog, CI that produces a verifiable installable APK.
- **Reviewability.** Small, single-responsibility units; immutable state; explicit data flow.

## 1.3 Long-Term Roadmap

The roadmap is organized into horizons. Horizon numbers are strategic groupings, not calendar commitments.

| Horizon | Theme | Representative deliverables |
|---------|-------|-----------------------------|
| **H1 — Foundation (current)** | Native Android core | GPU chart engine, market structure, indicators, risk engine, backtester, scanner, alerts, journal, replay, drawing tools, SMC detector |
| **H2 — Live & Intelligent** | Real data + AI | Live WebSocket feeds (Binance/Bybit), full SMC/ICT/LIT engines ported from the reference, multi-agent AI orchestration + decision engine, MTF confluence overlay, push alerts |
| **H3 — Platform & Backend** | Cloud & sync | FastAPI backend (PostgreSQL + TimescaleDB + Redis), cloud sync + conflict resolution, encrypted cloud backup, authentication + biometrics |
| **H4 — Ecosystem** | Extensibility | Plugin SDK, Indicator SDK, Drawing SDK, scripting engine, broker SDK, marketplace |
| **H5 — Ubiquity** | Multi-surface | Desktop sync, Wear OS companion, Android Auto glanceable alerts, tablet/foldable optimized layouts, deeper AI evolution |

`NOTE` The `reference/typescript-src/` tree is the **design source of truth** for H2+ features. It is a complete TypeScript/WebGL2 implementation (~34k lines) that is being *ported* to native Kotlin. It is not compiled into the app. When porting, the Kotlin implementation must preserve the reference's *behavior* and *non-repainting guarantees*, while adapting idioms to Kotlin/Compose.

## 1.4 Design Philosophy

1. **The domain is sacred and pure.** Business logic (structure detection, indicators, risk, backtest, SMC) lives in `domain/` and imports *nothing* from Android, Compose, Room, Retrofit, or Hilt (except `javax.inject.Inject` for constructor wiring). This is what makes the core testable, portable, and correct.
2. **State is immutable and unidirectional.** UI is a pure function of an immutable `UiState`. Events flow down as function calls; state flows up as `StateFlow`. There is exactly one `UiState` per screen.
3. **One source of truth.** For market data, the local Room database is the single source of truth. The UI observes the database; the network writes to the database. The UI never reads the network directly.
4. **Explicit over implicit.** Dependencies are injected, not discovered. Dispatchers are injected, not hard-coded. Coordinate transforms live in one class, not scattered across the renderer.
5. **Correctness over cleverness.** Non-repainting is non-negotiable. A "smarter" algorithm that peeks at future bars is a bug, not an optimization.
6. **Fail soft, never blank.** If the network fails, cached data serves the UI; if the cache is empty, synthetic sample data seeds it so the chart is never empty.

## 1.5 Performance Philosophy

Performance is a **feature with a budget**, not an afterthought.

- **Frame budget is law.** 60 FPS = 16.67 ms/frame; 120 FPS = 8.33 ms/frame. The chart's draw pass must fit inside the budget for the active refresh rate. The `PerformanceProfiler` measures every frame; the `AdaptiveQualityController` degrades detail before the budget is blown.
- **Bound the work, not the data.** Rendering cost must be proportional to *what is visible*, not to how much data is loaded. Viewport culling makes a 100,000-candle series cost the same to draw as a 120-candle series.
- **Zero allocation in hot paths.** No object, list, or lambda allocation inside a draw loop or a coordinate transform. Allocation causes GC pauses, and GC pauses are dropped frames.
- **Measure, then optimize.** Every performance claim in this document maps to a measurable metric in [Section 9](#9-performance-engineering). "It feels fast" is not evidence.

## 1.6 Institutional Quality Principles

These principles define what "institutional-grade" means for FoxTrader and are enforced across every engine:

1. **No repainting, ever.** At bar index `i`, an engine may read only candles `[0..i]`. A confirmed signal at bar `i` must never change when bar `i+1` arrives. Enforced structurally in the backtester via `strategy(candles.subList(0, i + 1), i)`.
2. **No single-indicator signals.** A trade signal requires *confluence*. The reference Master Decision Engine requires a minimum of 5 of 9 confluences (liquidity sweep, BOS/CHOCH, FVG, order block, SMT, session, HTF bias, trend, volume) plus a confidence threshold before any signal is approved.
3. **Risk gates trades.** The risk engine can *block* a trade regardless of how good the setup looks (daily/weekly loss, drawdown, consecutive losses, exposure). Risk and psychology blocks override everything.
4. **Realistic simulation.** Backtests model variable spread, slippage, and commission. A backtest that ignores costs is marketing, not engineering.
5. **Explainability.** Every AI decision produces a human-readable narrative listing which confluences are present and which are missing.

---

# 2. Complete System Architecture

## 2.1 Architectural Style

FoxTrader is a **single-module, single-activity, offline-first Android application** built on **MVVM + Clean Architecture**, with Jetpack Compose for UI, Hilt for dependency injection, Room for persistence, Retrofit/OkHttp for REST, an OkHttp WebSocket for streaming, and Kotlin Coroutines + Flow for all asynchrony.

There is one Gradle module today (`:app`) with strict *package-level* layering that is enforced by convention and review (and is designed to be split into Gradle modules later without code changes — see [2.11](#211-module-split-readiness--plugin-architecture)).

```
┌──────────────────────────────────────────────────────────────────────┐
│                            PRESENTATION                                │
│  feature/**/presentation  ·  ui/theme  ·  ui/navigation                │
│  Composables · ViewModels · UiState · Chart renderer · Nav graph       │
│  DEPENDS ON: domain                                                    │
├──────────────────────────────────────────────────────────────────────┤
│                               DOMAIN                                    │
│  domain/model · domain/repository · domain/usecase                     │
│  Pure Kotlin. Models, engines, use cases, repository INTERFACES.       │
│  DEPENDS ON: nothing (only javax.inject + kotlinx.coroutines)          │
├──────────────────────────────────────────────────────────────────────┤
│                                DATA                                     │
│  data/local (Room) · data/remote (Retrofit + WebSocket) · data/mapper  │
│  data/repository (impl) · data/alerts                                  │
│  DEPENDS ON: domain (implements its interfaces)                        │
├──────────────────────────────────────────────────────────────────────┤
│                                 DI                                      │
│  di/**  — Hilt modules that wire data implementations to domain        │
│  interfaces and provide framework singletons (DB, Retrofit, WS)        │
└──────────────────────────────────────────────────────────────────────┘
```

`RULE` The dependency arrow points **inward**. Presentation depends on Domain. Data depends on Domain. Domain depends on nothing. Any `import android.*`, `androidx.compose.*`, `androidx.room.*`, or `retrofit2.*` inside `domain/` is a defect.

## 2.2 Module / Package Map

The real package structure (from `com.foxtrader.app`):

```
com.foxtrader.app
├── FoxTraderApp.kt                 # @HiltAndroidApp — DI root
├── MainActivity.kt                 # Single ComponentActivity, edge-to-edge, hosts FoxNavHost
│
├── ui/
│   ├── theme/                      # Fox Design System: Color.kt, Type.kt, Theme.kt
│   └── navigation/                 # FoxNavHost.kt (NavHost + bottom bar + routes)
│
├── di/                             # Hilt modules (SingletonComponent)
│   ├── DatabaseModule.kt           # Room DB + DAO providers
│   ├── NetworkModule.kt            # Json, OkHttp, Retrofit, MarketApi
│   ├── WebSocketModule.kt          # binds MarketWebSocket -> BinanceWebSocket
│   ├── RepositoryModule.kt         # binds MarketRepository -> MarketRepositoryImpl
│   └── DispatcherModule.kt         # @IoDispatcher, @DefaultDispatcher qualifiers
│
├── domain/                         # PURE KOTLIN — the analytical core
│   ├── model/                      # Candle, Timeframe, MarketStructure, SmcConcepts,
│   │                               #   Risk, Backtest, Alert, Scanner, Drawing, Journal,
│   │                               #   Replay, News, OrderManagement, MultiTimeframe,
│   │                               #   CandlePattern, DataProvider, WebSocketState
│   ├── repository/                 # MarketRepository (interface)
│   └── usecase/                    # Engines & use cases (see below)
│       ├── AnalyzeMarketStructureUseCase.kt
│       ├── MultiTimeframeAnalysisUseCase.kt
│       ├── indicators/             # TechnicalIndicators, Bollinger, Ichimoku, PSAR,
│       │                           #   Pivots, Stochastic, SuperTrend, Volume, Channels
│       ├── smc/                    # SmcDetector (OB, FVG, liquidity, volume profile)
│       ├── analysis/               # Divergence, Fibonacci, MarketProfile, RiskReward,
│       │                           #   Seasonality, SupportResistance, Wyckoff
│       ├── patterns/               # CandlePatternDetector, HarmonicPatternDetector
│       ├── risk/                   # RiskEngine
│       ├── backtest/               # BacktestEngine
│       ├── scanner/                # ScannerUseCase
│       ├── alerts/                 # AlertEngine, SmartAlertEngine
│       ├── mtf/                    # ConfluenceEngine
│       ├── replay/                 # ReplayEngine
│       ├── drawing/                # DrawingEngine
│       ├── journal/                # JournalEngine
│       ├── orders/                 # OrderManager
│       ├── calculator/             # PositionCalculator
│       ├── correlation/            # CorrelationMatrix
│       ├── heatmap/                # MarketHeatmap
│       ├── news/                   # NewsEngine
│       ├── sessions/               # SessionDetector
│       ├── sync/                   # CloudSyncEngine
│       ├── watchlist/              # WatchlistManager
│       ├── chart/                  # MultiChartManager
│       ├── performance/            # PerformanceProfiler, AdaptiveQualityController
│       └── preferences/            # AppPreferences
│
├── data/                           # Android/framework implementations
│   ├── local/                      # FoxDatabase, dao/CandleDao, entity/CandleEntity
│   ├── remote/
│   │   ├── api/                     # MarketApi (Retrofit)
│   │   ├── dto/                     # CandleDto
│   │   └── websocket/               # MarketWebSocket (interface), BinanceWebSocket
│   ├── mapper/                      # CandleMapper (DTO<->Domain, Entity<->Domain)
│   ├── repository/                  # MarketRepositoryImpl, SampleData
│   └── alerts/                      # AlertDispatcher (Android notifications)
│
└── feature/                        # One package per screen (vertical slices)
    ├── chart/presentation/          # ChartScreen, ChartViewModel, ChartUiState,
    │                                #   components/ (CandleChart, ChartViewport,
    │                                #   DrawingRenderer, DrawingToolbar, IndicatorPanel,
    │                                #   ReplayOverlay, SmcRenderer, SymbolPickerDialog)
    ├── scanner/presentation/
    ├── strategies/presentation/
    ├── journal/presentation/
    └── settings/presentation/
```

## 2.3 Dependency Graph

```
                          ┌───────────────────────┐
                          │      MainActivity      │
                          │  (@AndroidEntryPoint)  │
                          └───────────┬───────────┘
                                      │ setContent
                                      ▼
                          ┌───────────────────────┐
                          │       FoxNavHost       │
                          │  Chart│Scanner│Strat…  │
                          └───────────┬───────────┘
                                      │ hiltViewModel()
              ┌───────────────────────┼───────────────────────┐
              ▼                       ▼                       ▼
      ┌───────────────┐       ┌───────────────┐       ┌───────────────┐
      │ ChartViewModel│       │ScannerViewModel│      │ …ViewModels    │
      └───────┬───────┘       └───────┬───────┘       └───────┬───────┘
              │ inject                │ inject                │
     ┌────────┴───────────┬──────────┴─────────┐             │
     ▼                    ▼                    ▼             ▼
┌──────────┐   ┌────────────────────┐  ┌──────────────┐  (use cases)
│ Use cases│   │  MarketRepository  │  │ AppPreferences│
│ (domain) │   │    (interface)     │  │  (singleton) │
└──────────┘   └─────────┬──────────┘  └──────────────┘
                         │ Hilt @Binds
                         ▼
              ┌────────────────────────┐
              │  MarketRepositoryImpl   │
              └───────┬─────────┬──────┘
                      ▼         ▼
                ┌─────────┐ ┌─────────┐
                │CandleDao│ │MarketApi│
                │ (Room)  │ │(Retrofit)│
                └────┬────┘ └────┬────┘
                     ▼           ▼
                ┌─────────┐ ┌──────────────┐
                │FoxDatabase│ │  OkHttp +   │
                └─────────┘ │  Retrofit    │
                            └──────────────┘

  BinanceWebSocket ──implements──▶ MarketWebSocket   (bound in WebSocketModule)
  PerformanceProfiler ◀── AdaptiveQualityController  (both @Singleton, domain)
```

`RULE` ViewModels depend on **domain abstractions** (use cases + repository interfaces), never on `MarketRepositoryImpl`, Room, or Retrofit directly.

## 2.4 Architecture Decision Records (ADRs)

ADRs capture *why* the architecture is what it is. New significant decisions must be appended here in the same format.

### ADR-001: Custom Compose Canvas chart engine (no third-party charting library)
- **Status:** Accepted.
- **Context:** No off-the-shelf Android chart library meets the 120 FPS + 100k-candle + infinite-zoom + SMC-overlay requirements with acceptable memory and control.
- **Decision:** Build a bespoke renderer on Compose `Canvas` (`CandleChart.kt`) with a dedicated camera (`ChartViewport.kt`), viewport culling, zero per-frame allocation, and a layered draw pass.
- **Consequences:** Full control over performance and visuals; we own all rendering complexity (grid, axes, crosshair, overlays, gestures). Requires the performance discipline in [Section 4](#4-chart-engine-bible).

### ADR-002: MVVM + Clean Architecture with a framework-free domain
- **Status:** Accepted.
- **Decision:** Three layers; domain is pure Kotlin. Repository interfaces owned by domain; implementations in data (Dependency Inversion).
- **Consequences:** Engines are unit-testable on the JVM without instrumentation; the analytical core is portable and reviewable.

### ADR-003: Offline-first with Room as the single source of truth
- **Status:** Accepted.
- **Decision:** UI observes Room via `Flow`. Network refresh writes into Room, which pushes updates to observers. Empty cache is seeded with `SampleData`.
- **Consequences:** The app is never blank and works offline; there is exactly one place that owns "current data."

### ADR-004: Hilt for dependency injection
- **Status:** Accepted.
- **Decision:** Hilt (Dagger) with `SingletonComponent` modules; dispatchers injected via qualifiers.
- **Consequences:** Compile-time-verified DI graph; testable via dispatcher swapping. **Configuration cache disabled** in Gradle for KSP/Hilt CI reliability (see ADR-007).

### ADR-005: Kotlin Coroutines + Flow for all async
- **Status:** Accepted.
- **Decision:** `StateFlow` for UI state, `SharedFlow` for tick streams, structured concurrency scoped to ViewModels; no RxJava, no callbacks in domain.
- **Consequences:** Uniform, cancellation-aware async model integrated with Compose lifecycle.

### ADR-006: TypeScript reference implementation as design source
- **Status:** Accepted.
- **Decision:** Keep the complete TS/WebGL2 platform under `reference/` as the behavioral spec for the Kotlin port; do not build it into the app.
- **Consequences:** Feature parity target is explicit; porting preserves proven algorithms and the non-repainting contract.

### ADR-007: Disable Gradle configuration cache
- **Status:** Accepted.
- **Context:** The Hilt Gradle plugin + KSP aggregating tasks report configuration-cache problems that fail cold CI builds.
- **Decision:** `org.gradle.configuration-cache=false`; CI runs with `--no-configuration-cache --no-daemon`.
- **Consequences:** Slightly slower cold builds; reliable CI. Revisit when the Hilt/KSP toolchain fully supports configuration cache.

### ADR-008: Single Gradle module now, package-enforced layering, module-split-ready
- **Status:** Accepted.
- **Decision:** Ship one `:app` module but keep boundaries strict enough that `:core-domain`, `:core-data`, `:feature-*` can be extracted later without rewrites.
- **Consequences:** Fast iteration now; clear extraction path for build-time parallelism and a future plugin SDK.

## 2.5 Communication Flow (Unidirectional Data Flow)

```
        ┌─────────────────────────── USER ───────────────────────────┐
        │ gesture / tap / timeframe chip / pull-to-refresh            │
        ▼                                                             │
  Composable (ChartScreen)                                            │
        │ viewModel.onEvent(Event)                                    │
        ▼                                                             │
  ViewModel                                                           │
        │ calls use case / repository                                 │
        ▼                                                             │
  Use case (domain)  ──────────────▶  Repository (interface)          │
                                            │                         │
                                            ▼                         │
                                   RepositoryImpl (data)              │
                                     ├─ read/write Room               │
                                     └─ fetch Retrofit / WS           │
                                            │                         │
                            Room emits Flow<Entity>                   │
                                            │ map -> domain           │
                                            ▼                         │
  ViewModel transforms -> immutable UiState (StateFlow)               │
        │ emit                                                        │
        ▼                                                             │
  Composable.collectAsStateWithLifecycle() ── recompose ─────────────┘
```

`RULE` Events go **down** as function calls (`onEvent`). State comes **up** as a single `StateFlow<UiState>`. Never expose mutable state or multiple state flows from a ViewModel.

## 2.6 Data Flow (Market Data Path)

```
Remote source                Mapping            Persistence (SSOT)        Reactive UI
────────────                 ───────            ─────────────────         ───────────
MarketApi (REST)   ─▶ CandleDto ─▶ toDomain ─▶ toEntity ─▶ CandleDao.upsertAll ─┐
BinanceWebSocket   ─▶ TickUpdate ─▶ Candle  ─▶ upsertCandle (forming bar)  ─────┤
SampleData (seed)  ─▶ Candle ─────────────────▶ upsertAll (empty-cache fallback)┤
                                                                                ▼
                                              Room `candles` table  (PK: symbol,timeframe,timestamp)
                                                                                │
                                              CandleDao.observe(...) : Flow<List<CandleEntity>>
                                                                                │ map { toDomain() }
                                                                                ▼
                                              MarketRepository.observeCandles : Flow<List<Candle>>
                                                                                │
                                              ViewModel → ChartUiState → Compose
```

Key properties:
- **Idempotent upserts.** `CandleEntity` has composite PK `(symbol, timeframe, timestamp)` with `OnConflictStrategy.REPLACE`, so re-fetching or receiving a forming bar update never duplicates rows.
- **Ascending time order** is guaranteed by DAO `ORDER BY timestamp ASC`.
- **Failure recovery** (`MarketRepositoryImpl.refreshCandles`): on network failure, if the cache is empty, seed `SampleData`; otherwise rethrow so the caller can surface the error while cached data continues to serve the UI.

## 2.7 Rendering Flow

The chart renders in a **single Canvas pass** composed of ordered layers (see [Section 4](#4-chart-engine-bible) for full detail). Summary:

```
Gesture (pan/pinch/long-press)
      │ mutate ChartViewport (plain class, not snapshot state)
      ▼
invalidateTick++ (mutableIntStateOf) ── triggers Canvas redraw
      ▼
Canvas draw pass (ordered layers, all viewport-culled):
  L0  grid (nice 1-2-5 price steps + time divisions)
  L0.5 session backgrounds
  L0.7 order blocks + fair value gaps (behind candles)
  L1  candles (culled to [startIndex, startIndex+visibleBars])
  L1.5 liquidity pools
  L1.7 volume profile (right-aligned histogram)
  L2  indicator overlays (EMA/Bollinger/SuperTrend/PSAR/VWAP)
  L3  market structure annotations (BOS/CHoCH/MSS/IDM)
  L4  live price reference line
  L4.5 user drawings
  L5  crosshair (when active)
  L6  price scale (Y axis)
  L7  time axis (X axis)
```

`PERF` The viewport is a **plain `@Stable` class**, not `mutableStateOf`. Gesture handlers mutate it directly and bump an `Int` tick to force a redraw. This avoids per-field Compose snapshot invalidation storms during a drag.

## 2.8 Threading Model

FoxTrader uses **structured concurrency** with three logical execution contexts, all injected (never hard-coded) so tests can substitute deterministic dispatchers.

| Context | Dispatcher | Used for | Provided by |
|---------|-----------|----------|-------------|
| **Main** | `Dispatchers.Main` (implicit via Compose/ViewModel) | UI state emission, recomposition, gesture handling | Compose runtime |
| **IO** | `Dispatchers.IO` (`@IoDispatcher`) | Room reads/writes, Retrofit calls, WebSocket I/O | `DispatcherModule` |
| **Default** | `Dispatchers.Default` (`@DefaultDispatcher`) | CPU-bound analysis (indicators, SMC, backtests, MTF) | `DispatcherModule` |

Rules and patterns:
- `RULE` Never call `Dispatchers.IO`/`Default` literally inside a class that does real work. Inject a `CoroutineDispatcher` via qualifier so it can be replaced in tests.
- Repository suspend functions wrap their body in `withContext(io)` (see `MarketRepositoryImpl`).
- Long-lived streaming components (`BinanceWebSocket`, `ReplayEngine`) own a `CoroutineScope(SupervisorJob() + Dispatchers.X)` so one failing child does not cancel siblings; they expose `StateFlow`/`SharedFlow` and clean up in their `stop()`/`disconnectAll()`.
- ViewModels use `viewModelScope`; work is cancelled automatically when the ViewModel clears.
- `WARNING` The rendering draw pass runs on the RenderThread/UI path. Heavy analysis (SMC detection, indicator arrays, volume profile) must be computed off the Main thread (Default) in the ViewModel and passed to the chart as ready-to-draw arrays/lists — never computed inside `Canvas { }`.

## 2.9 Offline-First Strategy

FoxTrader is offline-first by construction:

1. **The DB is the source of truth.** UI observes `CandleDao.observe(...)`. It has no code path that reads the network directly.
2. **Refresh is a background write.** `refreshCandles` fetches and writes to Room; observers update automatically. A failed refresh does not break the UI.
3. **Empty-cache seeding.** If a fetch fails and the cache is empty, `SampleData.generate(...)` produces synthetic-but-plausible candles so the chart, scanner, and analysis are always functional. Real data overwrites seed data on the next successful fetch (same composite PK).
4. **Provider awareness.** `AppPreferences.canGoLive()` reports whether the selected `DataProvider` supports live streaming and has any required API key, so the UI can decide between static and streaming modes.

`NOTE` The default `NetworkModule.BASE_URL` is `http://10.0.2.2:8000/` — the host machine from the Android emulator — pointing at the future FastAPI backend. Until that backend exists, the offline seeding path keeps the app fully usable.

## 2.10 Synchronization & Conflict Resolution Strategy

Cloud sync is designed in the domain layer today (`CloudSyncEngine`) with the network transport to be added in the data layer (H3).

- **Syncable types:** journal entries, chart drawings, settings (JSON).
- **Upload diff:** `calculateUploadDiff` selects locally-modified items since `lastSyncTime` (items whose `entryTime > lastSyncTime`).
- **Merge / conflict resolution:**
  - Journal entries: **last-write-wins** by `entryTime`. On matching IDs, the newer timestamp wins and the conflict is counted.
  - Drawings: **union by ID** (add remote drawings not present locally).
- **Status machine:** `IDLE → SYNCING → {SUCCESS | FAILED | CONFLICT}`.

`WARNING` Last-write-wins can silently drop a concurrent edit. For H3, journal edits should carry a monotonically increasing `revision` in addition to `entryTime`, and true three-way merges should be considered for high-value records. Document any change to the conflict policy as a new ADR.

## 2.11 Repository Strategy

- **Contract in domain, implementation in data.** `MarketRepository` (domain interface) ↔ `MarketRepositoryImpl` (data), bound by `RepositoryModule` via Hilt `@Binds`.
- **Reactive + one-shot APIs.** `observeCandles(...)` returns a `Flow` for reactive screens; `getCandles(...)` returns a snapshot for one-shot consumers (e.g., the scanner scanning many symbols).
- **Result-typed refresh.** `refreshCandles(...)` returns `Result<Unit>` so callers can surface errors without exceptions crossing layers.
- `RULE` Only `RepositoryImpl` may touch DAOs, DTOs, mappers, or the API. Use cases and ViewModels talk to the repository interface.
- **Future repositories** (journal, drawings, alerts, settings) follow the exact same pattern: domain interface → data impl → Hilt binding → Room DAO + optional remote.

## 2.11 Module-Split Readiness & Plugin Architecture

The single module is intentionally structured for a future split and a plugin ecosystem.

**Planned Gradle module graph (H4):**

```
:app  (Android entry, DI wiring, navigation)
  ├─ :feature-chart, :feature-scanner, :feature-strategies, :feature-journal, :feature-settings
  ├─ :core-ui       (theme, design system, shared composables)
  ├─ :core-data     (Room, Retrofit, WebSocket, repositories)
  └─ :core-domain   (models, use cases/engines, repository interfaces)   ← no Android deps

  Plugin surface (H4+):
  :sdk-indicator   (Indicator interface + registry)
  :sdk-drawing     (DrawingTool interface + registry)
  :sdk-broker      (BrokerAdapter interface + registry)
  :sdk-script      (scripting runtime)
```

**Plugin architecture principles** (mirrors the reference `brokers/broker-interface.ts` + `broker-registry.ts` pattern):
- Each extension point is a **narrow interface** in a `:sdk-*` module (e.g., `Indicator`, `DrawingTool`, `BrokerAdapter`, `DataProviderInterface`).
- A **registry** discovers and instantiates implementations at runtime; core code depends only on the interface, never on a concrete plugin.
- New indicators, drawing tools, brokers, or data providers are added **without touching core code** — the essence of the Open/Closed Principle applied at platform scale.
- Plugins are sandboxed: they receive read-only, non-repainting candle access and may not mutate app state directly; they return descriptive outputs (series, annotations, orders) that the core renders/executes.


---

# 3. Android Engineering Standards

This section is the normative style and architecture guide for all Android/Kotlin code in FoxTrader.

## 3.1 Jetpack Compose Rules

1. `RULE` **Screens are pure functions of state.** A `@Composable` screen receives its state and emits events. It contains no business logic and performs no I/O.
   ```kotlin
   @Composable
   fun ChartScreen(viewModel: ChartViewModel = hiltViewModel()) {
       val state by viewModel.uiState.collectAsStateWithLifecycle()
       ChartContent(state = state, onEvent = viewModel::onEvent)
   }
   ```
2. `RULE` **Hoist state.** Stateless composables receive `value` + `onValueChange`. Stateful wrappers exist only at the screen boundary (ViewModel-backed).
3. `RULE` **Collect lifecycle-aware.** Always `collectAsStateWithLifecycle()` (not `collectAsState()`) so collection pauses when the UI is not visible — this stops off-screen work and battery drain.
4. `RULE` **Stable, immutable inputs.** Pass `data class` / immutable lists (`List`, not `MutableList`) into composables. Mark long-lived non-Compose holders `@Stable` (as `ChartViewport` is) so the runtime can skip unnecessary recomposition.
5. `RULE` **No allocation-heavy work in composition.** Do not build lists, format strings in loops, or run analysis in the composable body. Precompute in the ViewModel; `remember` derived values keyed correctly.
6. `RULE` **`remember` with correct keys.** `remember(candles.size) { ... }` recomputes only when the key changes. A missing/incorrect key is a stale-state bug.
7. **Previews without Hilt.** ViewModels cannot be constructed in `@Preview`. Provide stateless content composables (`ChartContent(state, onEvent)`) and preview those with hand-built sample state.
   ```kotlin
   @Preview
   @Composable
   private fun ChartContentPreview() =
       FoxTraderTheme { ChartContent(state = ChartUiState(candles = sampleCandles()), onEvent = {}) }
   ```
8. **Theming.** Read colors/typography from `MaterialTheme.colorScheme` / `MaterialTheme.typography`, mapped from the Fox Design System (see [Section 11](#11-ui-design-system)). Do not hard-code raw `Color(0x...)` in feature UI except inside the chart renderer, where semantic palette tokens (`FoxBullish`, `FoxAmber50`, …) are used directly for draw calls.
9. **Edge-to-edge.** `MainActivity` calls `enableEdgeToEdge()`. Screens must honor insets via `Scaffold` padding (the `FoxNavHost` scaffold provides `innerPadding`).

`WARNING` Never trigger recomposition on every gesture frame by writing chart camera fields into `mutableStateOf`. Use the `invalidateTick` pattern (see [4.6](#46-touch--gesture-engine)).

## 3.2 MVVM Rules

- `RULE` **One ViewModel per screen.** No shared ViewModels across features. Cross-feature state lives in an injected singleton (e.g., `AppPreferences`) or is passed via navigation.
- `RULE` **One immutable `UiState` per screen**, exposed as a single `StateFlow<UiState>`.
  ```kotlin
  @HiltViewModel
  class ChartViewModel @Inject constructor(
      private val repository: MarketRepository,
      private val analyzeStructure: AnalyzeMarketStructureUseCase,
      @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
  ) : ViewModel() {
      private val _uiState = MutableStateFlow(ChartUiState())
      val uiState: StateFlow<ChartUiState> = _uiState.asStateFlow()

      fun onEvent(event: ChartEvent) { /* handle intents */ }
  }
  ```
- `RULE` **Events, not setters.** The UI calls `onEvent(ChartEvent.SelectTimeframe(tf))`; it does not mutate ViewModel fields.
- `RULE` **ViewModels expose state, not framework types.** No `Context`, `Cursor`, `Response`, or Room entities cross the ViewModel boundary — only domain models and UI state.
- **State updates are atomic:** `_uiState.update { it.copy(isLoading = true) }`.

## 3.3 Clean Architecture Rules

| Layer | May import | May NOT import |
|-------|-----------|----------------|
| `domain` | `kotlin.*`, `kotlinx.coroutines.*`, `javax.inject.*` | anything `android.*`, `androidx.*`, `retrofit2.*`, `androidx.room.*`, Hilt annotations other than `@Inject` |
| `data` | `domain`, Android, Room, Retrofit, OkHttp, kotlinx.serialization | `feature/**` / presentation |
| `presentation` (`feature`, `ui`) | `domain`, Compose, Hilt, Navigation | `data` implementation classes (only via injected interfaces) |
| `di` | everything (it is the wiring layer) | — |

- `RULE` The domain never knows how data is stored or fetched. It defines `MarketRepository`; data implements it.
- `RULE` Mappers (`data/mapper`) are the *only* place DTO/Entity ↔ domain conversion happens, and they are **pure functions**.

## 3.4 SOLID Applied

- **S — Single Responsibility.** Each use case/engine does one thing (`SmcDetector`, `RiskEngine`, `BacktestEngine`, `ConfluenceEngine`). Each exposes a small surface (`invoke()` or a few named methods).
- **O — Open/Closed.** New indicators are new functions/classes; new data providers implement `DataProvider`/provider interfaces; the plugin registries ([2.11](#211-module-split-readiness--plugin-architecture)) extend behavior without modifying core.
- **L — Liskov Substitution.** `BinanceWebSocket` is fully substitutable for `MarketWebSocket`; `MarketRepositoryImpl` for `MarketRepository`. Any future provider must honor the same contracts (including connection-state semantics).
- **I — Interface Segregation.** `MarketRepository` exposes only what consumers need (observe / refresh / upsert / snapshot). Scanner uses the one-shot `getCandles`; chart uses the reactive `observeCandles`.
- **D — Dependency Inversion.** High-level policy (domain) depends on abstractions; low-level detail (Room/Retrofit) depends on those same abstractions.

## 3.5 Dependency Injection (Hilt)

- **DI root:** `@HiltAndroidApp class FoxTraderApp`. Activities are `@AndroidEntryPoint`. ViewModels are `@HiltViewModel` and obtained via `hiltViewModel()`.
- **Modules** (all `@InstallIn(SingletonComponent::class)`):

| Module | Kind | Provides / Binds |
|--------|------|------------------|
| `DatabaseModule` | `@Provides` | `FoxDatabase` (`fallbackToDestructiveMigration()` for now), `CandleDao` |
| `NetworkModule` | `@Provides` | `Json` (lenient, ignore-unknown, coerce), `OkHttpClient` (15s connect/30s read, logging in debug), `Retrofit`, `MarketApi` |
| `WebSocketModule` | `@Binds` (abstract) | `MarketWebSocket` → `BinanceWebSocket` (`@Singleton` — one shared connection) |
| `RepositoryModule` | `@Binds` | `MarketRepository` → `MarketRepositoryImpl` |
| `DispatcherModule` | `@Provides` | `@IoDispatcher` → `Dispatchers.IO`, `@DefaultDispatcher` → `Dispatchers.Default` |

- `RULE` Engines that hold state and are shared (`RiskEngine`, `ReplayEngine`, `PerformanceProfiler`, `AdaptiveQualityController`, `AppPreferences`, `CloudSyncEngine`) are `@Singleton` with `@Inject constructor`. Pure stateless engines (`SmcDetector`, `BacktestEngine`, `ConfluenceEngine`) use `@Inject constructor` and may be non-singleton.
- `RULE` Use `@Binds` (abstract module) for interface→impl bindings; use `@Provides` only when you must construct the object (framework types, builders).

`TROUBLESHOOT` *"Unresolved reference" after adding a Hilt module/binding* → KSP has not regenerated Dagger components. Run `./gradlew :app:kspDebugKotlin`, then rebuild.

## 3.6 Navigation

- **Single-activity, Compose Navigation.** `FoxNavHost` hosts one `NavHost` with a `Scaffold` bottom bar. Routes are string constants in `FoxRoutes` (`chart`, `scanner`, `strategies`, `journal`, `settings`). Start destination is `chart`.
- **Bottom-nav behavior:** `popUpTo(FoxRoutes.CHART) { saveState = true }`, `launchSingleTop = true`, `restoreState = true` — a single-entry back stack with per-tab state restoration.
- `RULE` Screens do not receive `NavController`. Navigation intents are events handled at the graph level or via typed callbacks, keeping screens testable and preview-able.
- **Future:** migrate route strings to type-safe Navigation (Kotlin serialization routes) when adding argument-bearing destinations (e.g., `chart/{symbol}/{timeframe}`).

## 3.7 State Management

- **Source:** `StateFlow<UiState>` in the ViewModel; `MutableSharedFlow` only for one-shot effects (navigation, snackbars) or high-rate streams (WebSocket ticks).
- **Immutability:** `UiState` is a `data class` of immutable fields; updates via `copy`.
- **Derived state:** compute in the ViewModel (`stateIn`, `combine`) so the UI stays declarative.
- **Chart camera** is the deliberate exception: it is imperative (`ChartViewport`) for performance and lives outside Compose state; redraws are triggered explicitly.

## 3.8 Memory Management

- `PERF` **No per-frame allocation** in the chart draw loop or `ChartViewport` transforms. Reuse `android.graphics.Paint` objects via `remember` (as `CandleChart` does for label paints).
- Prefer primitive arrays (`DoubleArray`, `IntArray`) for indicator series over `List<Double>` — this is the existing convention (`calculateEMA` returns `DoubleArray`).
- Avoid retaining large candle lists in multiple places; the DB is the source of truth and the ViewModel holds the current window.
- `WARNING` Do not leak `CoroutineScope`s. Streaming engines must cancel their scopes/jobs on `stop()`. ViewModels rely on `viewModelScope` auto-cancellation.
- Bitmaps/screenshots (journal, future) must be recycled or scoped; never hold `Bitmap` in `UiState`.

## 3.9 Lifecycle Rules

- `MainActivity` declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"` — it handles configuration changes itself (Compose recomposes; the chart viewport survives via `remember`). `RULE` Do not rely on Activity recreation for state; keep durable state in ViewModel/DataStore/DB.
- Use `collectAsStateWithLifecycle()` so flows are collected only in `STARTED`.
- Streaming (WebSocket) should subscribe when the chart is visible and unsubscribe/close when it is not, to conserve battery and sockets.

## 3.10 Background Work

- Current app is foreground-first. For H2+ (push alerts, scheduled scans, sync):
  - Use **WorkManager** for deferrable, guaranteed work (periodic scans, cloud sync, alert evaluation) with appropriate constraints (network, charging).
  - Use a **foreground service** only if truly continuous live streaming with notifications is required, and justify it against battery cost.
  - `SECURITY`/`PERF` Never poll in a tight loop from a background thread; respect Doze and App Standby.

## 3.11 Coroutines

- `RULE` **Structured concurrency only.** Every coroutine is launched in a scope tied to a lifecycle (`viewModelScope`) or an owned `SupervisorJob` scope that is explicitly cancelled.
- `RULE` **Injected dispatchers.** No literal `Dispatchers.IO/Default` in business classes. Use `@IoDispatcher` / `@DefaultDispatcher`.
- **Cancellation cooperation:** long CPU loops (backtests over large series) should check `isActive` / use `ensureActive()` so they cancel promptly.
- **Error handling:** repository suspend funcs return `Result<T>`; streaming components catch and route errors to a `ConnectionState`/status flow rather than throwing across boundaries. `BinanceWebSocket.parseAndEmit` silently drops malformed frames — it never crashes the feed.

## 3.12 Flow

- `StateFlow` for state (always has a value, conflated). `SharedFlow` for events/streams (`BinanceWebSocket.ticks` uses `extraBufferCapacity = 64` with `tryEmit`).
- Transform with `map`, `combine`, `flatMapLatest`; convert cold flows to hot state with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`.
- `RULE` Do map/filter/analysis operators on `@DefaultDispatcher` via `flowOn` when they are CPU-bound; keep the collector on Main.
- `WARNING` `SharingStarted.WhileSubscribed(5_000)` (5s stop timeout) prevents restart storms during rotation while still stopping upstream when nothing observes.

## 3.13 Testing Strategy (Android specifics)

Full QA detail is in [Section 13](#13-qa-bible). Android-layer rules:
- **Domain** engines: plain JUnit on the JVM (no Robolectric, no emulator). This is where most tests live because the domain is pure.
- **ViewModels:** JUnit + `kotlinx-coroutines-test` (`runTest`, test dispatchers injected via the dispatcher qualifiers) + **Turbine** for `Flow` assertions + **MockK** for repository/use-case fakes.
- **Mappers:** plain JUnit round-trip tests.
- **UI:** Compose UI tests (`androidx.compose.ui.test.junit4`) + Espresso for instrumented flows (H2+).
- `RULE` Every new domain engine ships with unit tests. Every bug fix adds a regression test.

---

# 4. Chart Engine Bible

The chart is FoxTrader's crown jewel: a **bespoke, GPU-accelerated candlestick renderer** built directly on Compose `Canvas`, with no third-party charting library (ADR-001). This section specifies it completely.

Primary sources: `feature/chart/presentation/components/CandleChart.kt` (renderer) and `ChartViewport.kt` (camera/coordinate system). The `reference/typescript-src/engine/webgl/` tree (WebGL2 candle renderer, shaders, scheduler, worker pool) is the design source for future GPU-shader acceleration.

## 4.1 Rendering Architecture

The renderer is a **single-pass, multi-layer** pipeline. One `Canvas { }` block draws all layers in z-order every frame. Each layer is a private `DrawScope` extension function and is independently viewport-culled.

```
CandleChart(candles, overlays…)                  [Composable]
│
├─ remember ChartViewport (survives recomposition; @Stable)
├─ remember Paint objects (price/time/crosshair/structure labels)  ← zero-alloc reuse
├─ remember(candles.size) { init viewport window + autoScale }
│
├─ Modifier.pointerInput → detectTransformGestures  (pan + pinch, unified)
├─ Modifier.pointerInput → detectTapGestures        (long-press crosshair, tap dismiss)
│
└─ Canvas draw pass (reads invalidateTick to subscribe):
     L0    drawGridLayer            (price 1-2-5 steps + time divisions)
     L0.5  drawSessionBackgrounds   (clipped to chart area)
     L0.7  drawOrderBlocks / drawFairValueGaps
     L1    drawCandleLayer          (culled: start..end only)
     L1.5  drawLiquidityPools
     L1.7  drawVolumeProfile
     L2    drawIndicatorLayer / Bollinger / VWAP / SuperTrend / ParabolicSar
     L3    drawStructureLayer       (BOS/CHoCH/MSS/IDM, confirmed only)
     L4    drawLivePriceLine        (dashed last-close reference)
     L4.5  drawChartDrawings        (user trend lines, fibs)
     L5    drawCrosshairLayer       (when active)
     L6    drawPriceScale           (Y axis, right)
     L7    drawTimeAxis             (X axis, bottom)
```

`RULE` Layer order is contractual. Background context (sessions, OB/FVG) draws *behind* candles; annotations and axes draw *in front*. New overlays must declare their layer explicitly and respect clipping.

## 4.2 The Viewport (Camera)

`ChartViewport` is the single source of truth for every coordinate transform. It is a plain `@Stable` class (not Compose state) holding:

- `startIndex: Float` — left edge of the visible window (fractional for smooth pan).
- `visibleBars: Float` — window width in bars (zoom level).
- `priceHigh` / `priceLow: Double` — auto-scaled price bounds of the visible range.
- Layout margins: `priceScaleWidth` (Y-axis gutter), `timeAxisHeight` (X-axis gutter).
- Crosshair + fling state.

**Coordinate transforms (allocation-free, pure math):**

```kotlin
fun xForIndex(index, chartAreaWidth) = (index - startIndex) / visibleBars * chartAreaWidth
fun yForPrice(price, chartAreaHeight) = ((priceHigh - price) / (priceHigh - priceLow)) * chartAreaHeight
fun indexForX(x, chartAreaWidth)      = startIndex + (x / chartAreaWidth) * visibleBars
fun priceForY(y, chartAreaHeight)     = priceHigh - (y / chartAreaHeight) * (priceHigh - priceLow)
fun barWidthPx(chartAreaWidth)        = chartAreaWidth / visibleBars
```

`PERF` These four transforms are the hottest code in the app (called for every candle, grid line, and overlay every frame). They must remain **branch-light, allocation-free, and inline-friendly**. Never add logging, object creation, or collection operations here.

**Layout model:**
```
┌────────────────────────────────┬──────────┐
│           CHART AREA           │  PRICE   │  chartWidth  = totalWidth  - priceScaleWidth
│  (candles, overlays, grid)     │  SCALE(Y)│  chartHeight = totalHeight - timeAxisHeight
├────────────────────────────────┴──────────┤
│                TIME AXIS (X)               │
└────────────────────────────────────────────┘
```

## 4.3 Auto-Scaling & Nice Grids

- **Auto-scale** (`autoScale`): scans only the visible candle range for hi/lo, adds `pad` (default 8%) headroom, and sets `priceHigh`/`priceLow`. Recomputed after every pan/zoom.
- **Nice price step** (`niceStep`): classic **1-2-5 progression** scaled to the visible range, producing human-readable grid levels regardless of instrument price magnitude.
- **Nice time step** (`niceTimeStep`): snaps label spacing to `{1,2,5,10,15,20,30,60,120,240,480,1000}` bars based on visible-bar count.
- **Adaptive price formatting** (`formatPrice`): decimal places scale with magnitude (0 dp ≥ 10,000; 2 dp ≥ 100; 4 dp ≥ 1; 5 dp for sub-1 forex pairs).

`NOTE` This gives institutional-looking axes: prices snap to round numbers, and forex pairs get 5-digit precision while indices get whole numbers, automatically.

## 4.4 Candle Rendering & Viewport Culling

```kotlin
val start = max(0, viewport.startIndex.toInt())
val end   = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
val barWidth  = viewport.barWidthPx(cw)
val bodyWidth = (barWidth * 0.68f).coerceAtLeast(1f)
val wickWidth = max(1f, barWidth * 0.1f)
for (i in start until end) { /* wick line + body rect, colored bull/bear */ }
```

- `PERF` **Culling is the core scalability guarantee.** The loop only touches `[start, end)` — the visible window (~10–500 bars). A series of 10 or 100,000 candles costs the *same* per frame. This is what lets FoxTrader hold 120 FPS with huge datasets.
- Body width is 68% of bar width; wick is ~10% (min 1px). Body height has a 1px floor so doji bars remain visible.
- Colors are semantic tokens: `FoxBullish` (green) / `FoxBearish` (red).
- `RULE` All chart-area layers wrap their draw calls in `clipRect(right = cw, bottom = ch)` so nothing bleeds into the axis gutters.

## 4.5 GPU & Hardware Acceleration

- The app manifest sets `android:hardwareAccelerated="true"` and requires **OpenGL ES 3.0** (`uses-feature glEsVersion 0x00030000 required`).
- Compose `Canvas` draw commands are recorded into a display list and executed on the **RenderThread** against the GPU. Simple primitives (`drawLine`, `drawRect`, `drawCircle`, `drawPath`) are hardware-accelerated.
- `PERF` **Hardware-accelerated-friendly drawing:** prefer many simple primitives over complex `Path` where possible; avoid per-frame `Path` allocation (build small `Path`s only for markers like structure diamonds); avoid `drawText` in tight loops beyond axis labels.
- **Future (H2):** the `reference/engine/webgl` design (instanced candle geometry, custom GLSL shaders in `shaders.ts`, a frame `scheduler`, and a `worker-pool` for off-thread analysis) is the blueprint for a `SurfaceView`/`GLSurfaceView` shader path if Compose Canvas ever becomes the bottleneck at extreme zoom-out. Until profiling proves it necessary, the Canvas renderer is the supported engine (YAGNI + ADR-001).

## 4.6 Touch & Gesture Engine

Two independent `pointerInput` modifiers, keyed on `candles.size`:

1. **Pan + Zoom (unified):** a single `detectTransformGestures { _, pan, zoom, _ -> ... }`.
   - Horizontal pan: `startIndex -= pan.x * (visibleBars / chartWidth)`.
   - Pinch zoom **toward viewport center**: `visibleBars /= zoom`, re-center `startIndex`.
   - After mutation: `clamp(...)`, `autoScale(...)`, `invalidateTick++`.
2. **Crosshair:** `detectTapGestures(onLongPress = { activate + position }, onTap = { dismiss })`.

`WARNING` **The drift bug.** A previous implementation used *both* a transform handler and a separate drag handler; both applied pan to a single-finger drag, doubling/fighting the movement. **Do not reintroduce a separate `detectDragGestures` for pan.** `detectTransformGestures` natively reports single-pointer pan. This is a hard-won invariant — keep pan and zoom in one handler.

`PERF` The redraw trigger is `var invalidateTick by remember { mutableIntStateOf(0) }`. Gestures mutate the plain `ChartViewport` and bump the tick; the `Canvas` reads the tick to subscribe. This yields exactly one recomposition/redraw per gesture frame with no snapshot-state thrash.

## 4.7 Crosshair

- Activated by long-press; deactivated by tap or any pan/zoom.
- Draws dashed vertical + horizontal lines, plus filled amber readout labels: **price** on the right scale (via `priceForY` + `formatPrice`) and **time** on the bottom axis (snapped to nearest bar via `indexForX` + `formatTime`).
- `RULE` Crosshair coordinates are clamped to the chart area; the snapped bar index is `coerceIn(0, candles.size - 1)`.

## 4.8 Infinite Scrolling & Infinite Zoom

- **Scroll bounds** (`clamp`): `startIndex ∈ [0, total - visibleBars]`. Panning past the last bar is prevented; panning before bar 0 is prevented.
- **Zoom bounds** (`clamp`): `visibleBars ∈ [10, 100_000]`. Minimum 10 bars (max zoom-in) prevents degenerate single-bar views; maximum 100k bars (max zoom-out) bounds cull-loop cost.
- **"Infinite" semantics:** within a loaded dataset, scroll/zoom is continuous and fractional. True *infinite history* (paging older bars on demand) is an H2 data-layer feature: when `startIndex` approaches 0, request an older page from the repository and prepend into Room; the viewport index space shifts accordingly.

`WARNING` When prepending older candles (H2), you must **offset `startIndex` by the number of prepended bars** or the viewport will appear to jump. Keep the visible window anchored to timestamps, not raw indices, across paging.

## 4.9 Animation & Frame Scheduling

- Current interactions are **direct-manipulation** (1:1 with the finger) — no easing needed for pan/zoom, which is correct for a trading chart (predictability over flourish).
- **Fling** state exists in the viewport (`velocityBarsPerSec`, `isFling`). When implemented, fling animates `startIndex` with a decelerating curve driven by a Compose `Animatable`/`withFrameNanos` loop, bumping `invalidateTick` per frame and stopping when velocity ≈ 0 or a bound is hit.
- **Frame scheduling:** the `PerformanceProfiler` brackets each frame (`beginFrame()` / `endFrame(durationMs)`), enabling FPS/percentile tracking and adaptive quality. The reference `engine/scheduler.ts` is the model for a future explicit frame scheduler that coalesces data updates + redraws to the display refresh (via `Choreographer`/`withFrameNanos`).

## 4.10 Overlay & Drawing Engines

- **SMC overlays** (`SmcRenderer`, driven by `SmcDetector` outputs): order blocks and FVGs as translucent zones behind candles; liquidity pools as levels; volume profile as a right-aligned histogram (POC/VAH/VAL).
- **Indicator overlays:** EMA short/long, Bollinger bands, VWAP, SuperTrend (color by direction), Parabolic SAR (dots). Each is a viewport-culled polyline via `drawLineSeries`/`drawEmaLine`.
- **Structure annotations:** confirmed `StructureBreak`s render a dashed level, a small diamond `Path`, and a colored label (`BOS`/`CHoCH`/`MSS`/`IDM`).
- **User drawing engine** (`DrawingEngine` + `DrawingRenderer` + `DrawingToolbar`): trend lines, rectangles, fibs, etc., persisted as `ChartDrawing` domain models (syncable). Drawings render in L4.5.
- `RULE` Overlays receive **precomputed** data (arrays/lists) from the ViewModel. The renderer never runs detection/indicator math inside the draw pass.

## 4.11 Replay Rendering

- `ReplayEngine` (domain, `@Singleton`) feeds candles one at a time (`visibleCandles = allCandles.subList(0, currentIndex)`), exposing a `StateFlow<ReplayState>`.
- The chart simply renders the current `visibleCandles` sublist — **the same renderer, fewer candles**. No special replay code path in `CandleChart`.
- Controls: play/pause, step ±1, jump, speed (0.25×–16× via `ReplaySpeed.delayMs`); playback loop runs on the engine's `Dispatchers.Default` scope.
- `ReplayOverlay` composable renders the transport controls and progress.
- `RULE` Replay is a *practice/hindsight-free* tool: it must never reveal future bars. The sublist mechanism structurally enforces this.

## 4.12 Chart Caching & Object Pooling

- **Paint pooling:** `Paint` objects are created once via `remember` and reused every frame (already implemented for the four label paints). `RULE` Never allocate `Paint` in the draw loop.
- **Series caching:** indicator arrays (`DoubleArray`) are computed in the ViewModel and cached until inputs change; they are not recomputed per frame.
- **Path reuse (future):** for repeated marker shapes, cache a `Path` and translate it rather than rebuilding, once profiling shows marker allocation matters.
- **Bitmap layer caching (future):** static layers (grid, completed candles when idle) could be cached to an offscreen layer and only the live edge redrawn — apply only if the profiler shows the full pass exceeding budget at extreme bar counts.

## 4.13 Memory Optimization (Chart)

- Primitive arrays for all series; `List<Candle>` windows kept small.
- No boxing in transforms (`Float`/`Double` math, not `Number`).
- `PERF` Volume profile buckets and cluster lists are computed once per visible range, not per frame.

## 4.14 Adaptive Quality & 120 FPS Optimization

FoxTrader targets **120 FPS** (8.33 ms/frame) and guarantees **≥ 60 FPS** (16.67 ms/frame). Two domain singletons enforce this:

**`PerformanceProfiler`** — measures every frame:
- Rolling 120-frame history (2 s at 60 fps); tracks FPS, avg/worst frame time, budget usage %, dropped frames (over budget), and spikes (> 32 ms).
- `getPerformanceTier()` → `EXCELLENT (<50%)`, `GOOD (50–75%)`, `ACCEPTABLE (75–100%)`, `DEGRADED (100–150%)`, `CRITICAL (>150%)`.
- Emits a `PerformanceSnapshot` (used by the debug FPS overlay).

**`AdaptiveQualityController`** — degrades detail before frames drop:
- Quality ladder: `ULTRA → HIGH → MEDIUM → LOW → MINIMAL`.
- **Downgrade fast** (CRITICAL → immediate step down; DEGRADED → after 5 bad frames). **Upgrade slow** (EXCELLENT sustained for 60 frames), with **hysteresis** to prevent oscillation.
- Each level toggles features via `QualitySettings`: grid lines, volume profile, indicators, sessions, structure annotations, anti-alias, and `maxVisibleIndicatorPoints`. `MINIMAL` = candles only (emergency).

```
Frame → profiler.endFrame(ms) → tier
         │
         ▼
 AdaptiveQualityController.evaluate() → QualitySettings
         │  (e.g. drop volumeProfile at MEDIUM, sessions+annotations at LOW)
         ▼
 CandleChart reads settings and skips disabled layers next frame
```

### 4.15 Benchmark Targets (Chart)

| Metric | Target | Floor (must not breach) |
|--------|--------|-------------------------|
| Sustained frame rate (interaction) | 120 FPS on 120 Hz devices | 60 FPS |
| Frame time (avg) | ≤ 8.33 ms (120) / ≤ 16.67 ms (60) | budget usage < 100% |
| Dropped-frame rate during a 5 s pan | 0% | < 1% |
| Cold chart first-draw | < 1 frame after data ready | < 2 frames |
| Candles loaded without FPS loss | ≥ 100,000 | 100,000 |
| Per-frame heap allocation (draw pass) | 0 bytes | 0 bytes |

### 4.16 Chart Performance Optimization Checklist

- [ ] Draw loop bounded by viewport cull (`start..end`), never the full list.
- [ ] Zero allocations inside `Canvas { }` and inside `ChartViewport` transforms.
- [ ] `Paint` and reusable objects hoisted via `remember`.
- [ ] Camera mutations use `invalidateTick`, not `mutableStateOf` per field.
- [ ] All analysis/indicators precomputed off the Main thread.
- [ ] Every chart-area layer clipped to `(cw, ch)`.
- [ ] `PerformanceProfiler` active in debug; no CRITICAL tiers during interaction.
- [ ] Single unified pan/zoom gesture handler (no separate drag pan).

`TROUBLESHOOT` *Chart doesn't respond to touch* → a parent composable (e.g., `verticalScroll`) is consuming gestures. Ensure `CandleChart` owns its `pointerInput` modifiers and the parent does not wrap it in a competing scroll container.


---

# 5. Trading Engine

The "trading engine" is the constellation of pure-domain engines under `domain/usecase/` that turn candles into analysis, signals, risk decisions, and performance metrics. Every engine obeys the **non-repainting** contract ([1.6](#16-institutional-quality-principles)) and is unit-testable on the JVM.

## 5.1 Trading Engine Architecture

```
                        ┌──────────────────────────────┐
                        │        Candle stream          │
                        │  (Room SSOT → domain models)   │
                        └───────────────┬───────────────┘
                                        ▼
             ┌──────────────────────────────────────────────────┐
             │                 ANALYSIS TIER                     │
             │  AnalyzeMarketStructure · SmcDetector · Indicators │
             │  Patterns · Divergence · SR · Wyckoff · Fibonacci  │
             │  MarketProfile · Sessions                          │
             └───────────────┬──────────────────────────────────┘
                             ▼
             ┌──────────────────────────────────────────────────┐
             │               CONFLUENCE TIER                     │
             │  MultiTimeframeAnalysis · ConfluenceEngine        │
             │  CorrelationMatrix · MarketHeatmap                │
             └───────────────┬──────────────────────────────────┘
                             ▼
             ┌──────────────────────────────────────────────────┐
             │               DECISION TIER                       │
             │  Strategy signals → (AI agents + Master Decision) │
             │  → RiskEngine gating → OrderManager               │
             └───────────────┬──────────────────────────────────┘
                             ▼
             ┌──────────────────────────────────────────────────┐
             │             EVALUATION / FEEDBACK TIER            │
             │  BacktestEngine · ReplayEngine · ScannerUseCase   │
             │  AlertEngine · JournalEngine                      │
             └──────────────────────────────────────────────────┘
```

`RULE` Data flows one way through the tiers. Lower tiers never call back into higher tiers. The Risk tier can **veto** anything.

## 5.2 Strategy Engine

- **Contract:** a strategy is a pure function `typealias StrategyFunction = (candles: List<Candle>, index: Int) -> StrategySignal?`.
- `RULE` The function receives `candles[0..index]` only — enforced by the backtester passing `candles.subList(0, i + 1)`. Returning a signal that depends on `candles[index+1..]` is impossible by construction.
- A `StrategySignal` carries: `direction`, `entry`, `stopLoss`, `takeProfit`, `index`, `timestamp`, optional `volume`, and `setupType`.
- **Composition:** strategies are built from analysis-tier outputs (structure bias, SMC objects, indicator values). The `strategies/presentation` feature is the UI for selecting/parameterizing strategies and viewing their backtest results.
- **Example (RSI mean-reversion skeleton):**
  ```kotlin
  val rsiReversion: StrategyFunction = { candles, i ->
      if (i < 50) null else {
          val rsi = TechnicalIndicators.calculateRSI(candles, 14)
          val atr = TechnicalIndicators.calculateATR(candles, 14).last()
          if (rsi[i] < 30.0) StrategySignal(
              direction = Direction.BULLISH,
              entry = candles[i].close,
              stopLoss = candles[i].low - atr,
              takeProfit = candles[i].close + 2 * atr,
              index = i, timestamp = candles[i].timestamp,
          ) else null
      }
  }
  ```

## 5.3 Signal Engine

- **Signals originate** from strategies and from institutional detectors (structure breaks, sweeps, OB/FVG mitigation).
- **Confluence-gated:** a raw signal is a *candidate*. Approval requires the confluence/decision tier ([Section 7](#7-ai-architecture)) — no single detector may authorize a trade ([1.6](#16-institutional-quality-principles)).
- **Non-repainting guarantee:** once a signal prints at bar `i` with `confirmed = true`, it is immutable. UIs render only `confirmed` structure breaks (`drawStructureLayer` filters `if (!brk.confirmed) continue`).

## 5.4 Risk Engine

`RiskEngine` (`@Singleton`) is the institutional risk core. It sizes positions, sets stops, gates trades, tracks outcomes, and can auto-halt.

**Position sizing** (`calculatePositionSize`) — six methods (`PositionSizingMethod`):

| Method | Formula (essence) |
|--------|-------------------|
| `FIXED_LOTS` | `volume = fixedLots` |
| `FIXED_RISK` | `volume = fixedRiskAmount / (stopDistance × 100_000)` |
| `PERCENTAGE_RISK` | `risk = balance × riskPct%`; `volume = risk / (stopDistance × 100_000)` |
| `KELLY` | `risk = balance × kelly% × kellyFraction` (half-Kelly by default) |
| `ATR_BASED` | stop distance = `ATR(14) × atrStopMultiplier`; volume from % risk |
| `VOLATILITY` | stop distance = `volatility × volatilityStopMultiplier`; volume from % risk |

- Volume floored at `0.01` lots and rounded to 2 dp. Warnings accumulate (zero stop distance, insufficient data → graceful fallback to percentage risk, negative Kelly edge).

**Stop-loss calculation** (`calculateStopLoss`) — `FIXED` (0.5% default), `ATR`, `VOLATILITY`, `STRUCTURE` (caller-provided structural level, falling back to fixed).

**Pre-trade gating** (`canOpenTrade`) returns a `RiskCheckResult { allowed, reasons[...] }`. A trade is blocked when any of these trip, using `RiskConfig` thresholds:

| Guard | Default threshold (`RiskConfig`) |
|-------|----------------------------------|
| Trading halted flag | — |
| Daily loss limit | `maxDailyLossPercent = 3%` of balance |
| Weekly loss limit | `maxWeeklyLossPercent = 6%` |
| Consecutive losses | `maxConsecutiveLosses = 4` |
| Max drawdown | `maxDrawdownPercent = 15%` |
| Portfolio exposure | `maxPortfolioExposurePercent = 500%` (position tracking is H2) |

**Kelly estimation** (`calculateKellyPercent`): from trade history, `kelly = winRate − (1 − winRate)/winLossRatio`, clamped to `[0, 0.25]`. Requires ≥ 5 wins and ≥ 3 losses before it overrides the configured per-trade risk.

**Outcome tracking & auto-halt:** `recordTrade(pnl, symbol)` updates balance/peak and calls `checkAutoHalt()`, which halts on max drawdown, consecutive-loss, or daily-loss breach.

`WARNING` `RiskEngine` holds mutable state (`tradeHistory`, `currentBalance`, `peakBalance`, `tradingHalted`). It is `@Singleton` and its mutators must be called from a single logical owner. When multi-position tracking arrives (H2), confirm thread-safety or confine mutations to one dispatcher.

`RULE` The Risk Engine has veto power. In the decision pipeline ([7.5](#75-master-decision-engine)), a risk (or psychology) block overrides an otherwise-approved signal.

## 5.5 Backtesting Engine

`BacktestEngine` runs a strategy **bar-by-bar with no look-ahead** and produces a full institutional metrics suite.

**Execution loop** (`invoke(candles, strategy, symbol, timeframe)`):
1. For each bar `i`: if a trade is open, check intra-bar exit (**SL checked before TP** — conservative); else, evaluate `strategy(candles.subList(0, i+1), i)` for a new entry (only when flat).
2. Record an `EquityPoint` every bar (balance, drawdown, drawdown %).
3. Close any open trade at the last candle (`ExitReason.END`).

**Cost modeling:**
- **Variable spread** (`getSpread`): base `spread` widened up to 3× as bar range grows — models volatility-driven spread widening.
- **Slippage** applied at SL fills; **commission** = `commissionPerLot × volume` deducted from gross P&L.

**Metrics** (`BacktestMetrics`): net/gross profit, win rate, **profit factor**, **expectancy**, avg/largest win/loss, **max drawdown** (abs + %), **Sharpe** (annualized ×√252), **Sortino** (downside deviation), **Calmar** (return% / maxDD%), **recovery factor**, avg holding bars, max consecutive wins/losses, final balance, return %, total commission, plus an **equity curve** and per-trade `R-multiple`.

`RULE` Any new backtester feature must preserve the `subList(0, i+1)` no-look-ahead invariant and must keep SL-before-TP exit ordering (never resolve TP first when both are touched intra-bar, absent tick data).

`WARNING` Bar-level exits are an approximation: when a single bar touches both SL and TP, real fill order is unknown. FoxTrader assumes SL-first (pessimistic). For higher fidelity, use tick data (Dukascopy) in the replay/backtest path (H2).

## 5.6 Replay Engine

See [4.11](#411-replay-rendering). `ReplayEngine` (`@Singleton`) exposes a `StateFlow<ReplayState>` and reveals candles progressively via `subList(0, currentIndex)`. Controls: play/pause/step/jump/speed (0.25×–16×). Guards: needs ≥ 2 candles; index coerced to `[1, size]`. It reuses the standard chart renderer. Primary use: hindsight-free practice and manual strategy validation.

## 5.7 Scanner Engine

`ScannerUseCase` screens many symbols and ranks opportunities.
- **Universe:** a pre-configured watchlist (~30 symbols across Forex, Crypto, Stocks, Indices, Metals, Energy) managed by `WatchlistManager`.
- **Composite scoring:** trend strength + momentum + volatility + setup quality → a single comparable score.
- **Categorization & tags:** category badges (Best Buy/Sell/Swing/Scalp/Long-Term) and tags (`TRENDING`, `OVERBOUGHT`, `OVERSOLD`, `HIGH_VOL`, `MOVER`).
- **Data access:** uses the repository's one-shot `getCandles(symbol, timeframe)` (default H1) so it can scan without reactive subscriptions; empty caches are seeded so the scanner always has data.
- `PERF` Scanning N symbols is CPU-bound; run on `@DefaultDispatcher`, ideally with bounded parallelism (`async` per symbol, awaited in chunks) to avoid saturating cores on low-end devices.

## 5.8 Alert Engine

Two engines: `AlertEngine` (rules + delivery gating) and `SmartAlertEngine` (context-aware/AI-assisted alerts). Delivery is via `data/alerts/AlertDispatcher` (Android notifications; `POST_NOTIFICATIONS` permission declared).
- **Priority filtering:** `LOW / MEDIUM / HIGH / CRITICAL`.
- **Cooldown deduplication:** suppress repeats of the same alert within a cooldown window.
- **Hourly rate limiting:** cap alerts/hour to prevent notification spam.
- **Acknowledgment tracking:** alerts can be acknowledged and cleared.
- `RULE` Alert evaluation runs off the Main thread; only the final `AlertDispatcher.notify(...)` touches Android UI/notification APIs.

## 5.9 Market Structure Engine

`AnalyzeMarketStructureUseCase` detects swing points (fractal highs/lows) and structure breaks, producing a `MarketStructure { bias, swingHighs, swingLows, breaks }`.
- **Break types** (`StructureBreakType`): `BOS` (continuation), `CHOCH` (reversal), `MSS` (strong shift), `IDM` (inducement).
- **Bias** (`BULLISH / BEARISH / NEUTRAL`) derived from the sequence of confirmed breaks.
- `RULE` **Non-repainting:** at bar `i`, only `[0..i]` is read; a confirmed break never mutates. Only `confirmed` breaks are rendered.

## 5.10 Confluence Engine

`ConfluenceEngine` scores agreement **across timeframes** (complements `MultiTimeframeAnalysisUseCase`).
- Input: `Map<Timeframe, List<Candle>>` (needs ≥ 50 bars per TF) + a `primaryDirection`.
- Per TF it computes: structure bias, ADX trend strength, EMA20/50 alignment, RSI zone, structure-intact flag.
- Output `ConfluenceResult`: overall bias (majority vote), a **0–100 confluence score** (`aligned/total × 100`), aligned-TF count, and a human recommendation (≥80 "Strong — all aligned", ≥60 "Good — majority", ≥40 "Mixed — caution", else "Weak — wait").
- `NOTE` This is the *timeframe-alignment* confluence. The *factor-level* confluence (sweep+BOS+FVG+OB+…) lives in the AI decision tier ([7.5](#75-master-decision-engine)). Both must agree for institutional-grade setups.

## 5.11 Portfolio Engine

- **Today:** `RiskEngine` tracks account balance, peak, drawdown, and exposure percentages; `CorrelationMatrix` computes pairwise correlations (to cap correlated exposure, `maxCorrelatedExposurePercent = 200%`, `correlationThreshold = 0.7`); `MarketHeatmap` visualizes relative strength.
- **H2:** open-position tracking, real portfolio exposure (currently simplified to `0.0` in `RiskCheckResult`), multi-symbol P&L aggregation, and correlation-aware sizing.

## 5.12 Trade Journal Engine

`JournalEngine` + the `journal/presentation` feature: records trades (entry/exit, R-multiple, setup type, emotion/notes), computes statistics (win rate, expectancy, streaks, per-setup performance), and is a **syncable** type ([2.10](#210-synchronization--conflict-resolution-strategy)). Journal entries carry `id` + `entryTime` for last-write-wins merge. Future: auto-journaling with chart screenshots and AI-generated insights (reference `journal/trade-journal.ts`).

---

# 6. Institutional Trading Concepts

This section is the **canonical definitions + detection specification** for the Smart Money Concepts (SMC), ICT, LIT, and SMT vocabulary FoxTrader implements. It binds each concept to its domain model and detector so engineers and AI agents share one precise meaning. Detection today lives in `SmcDetector` + `AnalyzeMarketStructureUseCase`; the richer variants (IFVG, breaker, OTE, AMD, kill zones) are specified here as the port target from `reference/typescript-src/modules/`.

`RULE` Every concept below is **non-repainting**: detection at bar `i` uses only `[0..i]`. All zones/levels, once confirmed, are immutable.

## 6.1 Market Structure: BOS, CHoCH, MSS, IDM

- **Swing point:** a fractal high/low confirmed by surrounding bars (`SwingPoint { type, price, timestamp, index }`).
- **BOS (Break of Structure):** price closes beyond the prior confirmed swing in the direction of trend → **continuation**.
- **CHoCH (Change of Character):** the first break *against* the prevailing trend → early **reversal** signal.
- **MSS (Market Structure Shift):** a strong, decisive CHoCH (often with displacement/FVG) → higher-conviction reversal.
- **IDM (Inducement):** a minor liquidity grab that *induces* entries before the real move; often the last pullback's liquidity taken before a BOS.
- Model: `StructureBreak { type, direction, breakPrice, breakTimestamp, breakIndex, confirmed }`; aggregate `MarketStructure { bias, swingHighs, swingLows, breaks }`.

```
   Bullish structure:  HH ─ HL ─ HH ─ HL ...        (BOS on each new HH)
   CHoCH:              ... HH ─ HL ─ (break below HL) → bias may flip bearish
```

## 6.2 Order Blocks (OB), Breaker, Mitigation

- **Order Block:** the last opposing candle before an impulsive (displacement) move — an institutional supply/demand zone.
  - **Bullish OB:** last **bearish** candle before a strong bullish impulse.
  - **Bearish OB:** last **bullish** candle before a strong bearish impulse.
- **Detection** (`SmcDetector.detectOrderBlocks`): a move is "impulsive" when `bodySize > avgRange(last 20) × impulseMultiplier` (default 1.5). The preceding opposing candle's high/low defines the zone; `strength ∈ [0,1]` scales with impulse size; the zone extends up to 20 bars for rendering.
- **Mitigation:** price returning into the OB and reacting (`isPriceMitigated` marks `mitigated = true` when price revisits the zone level). A **mitigated** OB has (partly) done its job; an **unmitigated** OB is a live zone.
- **Breaker:** a *failed* order block — when an OB is violated and price uses the flipped zone as support/resistance in the opposite direction. (Port target: `reference/modules/order-blocks`.)
- Model: `OrderBlock { type, highPrice, lowPrice, startIndex, endIndex, mitigated, strength }`.

## 6.3 Liquidity: BSL, SSL, Equal Highs/Lows, Sweeps, Pools

- **Liquidity** = clustered stop orders / resting orders that institutions target.
  - **BSL (Buy-Side Liquidity):** above equal highs (buy stops) — target for bearish sweeps.
  - **SSL (Sell-Side Liquidity):** below equal lows (sell stops) — target for bullish sweeps.
- **Liquidity pool:** a cluster of ≥ `minTouches` (default 2) equal highs/lows within a tolerance (`ATR × 0.3`).
- **Sweep (liquidity grab):** price spikes beyond the pool to trigger stops, then reverses. `SmcDetector.detectLiquidity` marks `swept = true` and records `sweepIndex` when a later bar exceeds the level by the tolerance.
- Model: `LiquidityPool { type (BUY_SIDE/SELL_SIDE), price, startIndex, endIndex, swept, sweepIndex? }`.

## 6.4 Fair Value Gaps: FVG, IFVG, Imbalance, BPR

- **FVG (Fair Value Gap / imbalance):** a 3-candle inefficiency where the middle candle displaces so far that candle 1 and candle 3 do not overlap.
  - **Bullish FVG:** `candle[i-2].high < candle[i].low` (gap between them).
  - **Bearish FVG:** `candle[i-2].low > candle[i].high`.
- **Detection** (`SmcDetector.detectFairValueGaps`): records the gap band; `isFvgFilled` tracks penetration → `filled` (≥ 50% penetration flagged) and `fillPercent ∈ [0,1]`. Price tends to revisit (fill) gaps.
- **IFVG (Inversion FVG):** an FVG that gets filled and then acts as support/resistance from the opposite side (inverted). (Port target: `reference/modules/fair-value-gaps`.)
- **BPR (Balanced Price Range):** overlap of a bullish and bearish FVG creating a high-sensitivity zone. (Port target.)
- Model: `FairValueGap { type, highPrice, lowPrice, index, filled, fillPercent }`.

## 6.5 Premium / Discount & OTE

- Using the current dealing range (swing low → swing high):
  - **Discount:** lower half — preferred zone to **buy**.
  - **Premium:** upper half — preferred zone to **sell**.
  - **Equilibrium:** the 50% level.
- **OTE (Optimal Trade Entry):** the **62–79% Fibonacci retracement** of the impulse leg (classic ~0.705 sweet spot), inside discount (for longs) or premium (for shorts). Computed via `analysis/FibonacciEngine`. OTE + an unmitigated OB/FVG inside discount/premium is a textbook institutional entry.

```
  High ─────────────────────  100%  ── Premium (sell zone)
        ...                     79%  ┐
        OTE entry band          70.5% ├─ OTE (retracement entries)
        ...                     62%  ┘
  Mid  ─────────────────────   50%  ── Equilibrium
        ...                          ── Discount (buy zone)
  Low  ─────────────────────    0%
```

## 6.6 Sessions & Kill Zones

- **Sessions** (`TradingSession`, UTC hours, overlay colors):

| Session | Open (UTC) | Close (UTC) |
|---------|-----------|-------------|
| Sydney | 22 | 07 |
| Tokyo | 00 | 09 |
| London | 07 | 16 |
| New York | 13 | 22 |

- **Kill Zones (ICT):** high-probability windows within sessions — London Open, New York Open (AM), London Close, and the Asian range. Detected by `SessionDetector`; rendered as `SessionRange { session, startIndex, endIndex, highPrice, lowPrice }` backgrounds (chart layer L0.5).
- `NOTE` Session boundaries are computed in UTC then displayed in the device's local time (`ChartViewport.formatTime` uses `TimeZone.getDefault()`).

## 6.7 AMD / Power of Three

- **AMD (Accumulation → Manipulation → Distribution)**, a.k.a. ICT **Power of Three**:
  1. **Accumulation:** range-bound build-up (often the Asian range).
  2. **Manipulation:** a false move that sweeps liquidity against the eventual direction (the "judas swing").
  3. **Distribution:** the real, sustained move in the intended direction.
- Detection combines session ranges + liquidity sweeps + a subsequent structure break/displacement. (Port target: `reference/modules/ict-concepts`.)

## 6.8 SMT (Smart Money Technique / Divergence)

- **SMT divergence:** correlated instruments disagree at a key level — e.g., one makes a higher high while its correlated pair fails to, revealing institutional intent.
- Backed by `analysis/DivergenceDetector` (price/oscillator divergence) + `correlation/CorrelationMatrix` (which pairs are correlated). A confirmed SMT among correlated symbols is one of the decision-engine confluences. (Port target: `reference/modules/smt`.)

## 6.9 LIT (Liquidity Inducement Theory)

- **LIT** focuses on how price **induces** traders (traps + inducement) before the real move:
  - **Inducement:** obvious liquidity (a clean swing) placed to be swept before price seeks the true POI (point of interest / OB).
  - **Trap:** a break that lures breakout traders, then reverses.
  - **Shift:** the structural confirmation after the inducement is taken.
- Represented in the AI layer by the LIT agent (`reference/agents/impl/lit-agent.ts`), weighted highly in the orchestrator. (Port target: `reference/modules/lit-trading`.)

## 6.10 Volume Profile (POC / VAH / VAL)

- `SmcDetector.computeVolumeProfile(candles, buckets = 50)` distributes each bar's volume across its price range into buckets, splitting by bull/bear for delta.
- Outputs `VolumeProfile { levels, pocPrice, vahPrice, valPrice, totalVolume }`:
  - **POC (Point of Control):** highest-volume price level (strongest acceptance).
  - **VAH / VAL (Value Area High/Low):** bounds of the 70%-of-volume value area centered on POC.
- Rendered as a right-aligned horizontal histogram (chart layer L1.7). Also supports the broader `analysis/MarketProfile` (TPO) work.

## 6.11 How the Concepts Connect (Confluence Map)

A high-probability FoxTrader setup stacks these concepts. The decision engine ([7.5](#75-master-decision-engine)) formalizes this as required confluences:

```
   HTF bias (Market Structure)  ─┐
   Liquidity sweep (BSL/SSL)     ─┤
   BOS / CHoCH / MSS             ─┤
   FVG (or IFVG/BPR)             ─┼──▶  Setup grade
   Order Block (unmitigated)     ─┤     (WEAK → INSTITUTIONAL)
   Premium/Discount + OTE        ─┤
   Session / Kill Zone           ─┤
   SMT divergence                ─┤
   Volume (delta / profile)      ─┘
```

`RULE` No single concept authorizes a trade. Institutional-grade setups require **multiple** aligned concepts *and* pass risk gating. This is the mechanical expression of "no single-indicator signals."


---

# 7. AI Architecture

FoxTrader's AI layer is a **multi-agent, confluence-gated reasoning system**. Its purpose is not to "predict price" but to **explain the market**: to enumerate which institutional confluences are present, weigh them, and produce a transparent, non-repainting recommendation that a disciplined trader (or the risk engine) can act on or reject.

The design source of truth is `reference/typescript-src/agents/` and `reference/typescript-src/ai/`; the Kotlin port lands in `domain/usecase/` (analysis + a new `domain/usecase/ai/` package) plus a thin optional remote LLM adapter in `data/remote/` for natural-language narration (H2).

## 7.1 Design Principles

1. **Deterministic core, optional LLM narration.** The confluence math and decision logic are deterministic Kotlin (reproducible, testable, offline). An LLM (remote) is used only to *phrase* explanations, never to *make* the trade decision. `SECURITY`/`RULE` A trade decision must be reconstructable without any network call.
2. **Every agent is independent and single-concern.** Agents do not call each other; they each read the market context and emit an `AgentAnalysis`. The orchestrator aggregates.
3. **Confluence over prediction.** Approval requires multiple agents to agree ([7.5](#75-master-decision-engine)).
4. **Explainability is mandatory.** Every decision carries the list of satisfied and missing confluences and per-agent reasoning.
5. **Non-repainting.** Agents read only confirmed history `[0..i]`.

## 7.2 Agent Roster

Ported from `reference/agents/impl/`. Each implements the `Agent` contract and returns an `AgentAnalysis { direction, confidence 0..1, reasoning[], keyLevels[], weight }`.

| Agent | Concern | Primary inputs |
|-------|---------|----------------|
| `MarketStructureAgent` | BOS/CHoCH/MSS/IDM, HTF bias | swing points, structure breaks |
| `SmartMoneyAgent` | Order blocks, mitigation, displacement | `SmcDetector` OBs |
| `ICTAgent` | FVG/IFVG, OTE, AMD, kill zones | FVGs, sessions, fib |
| `LITAgent` | Inducement, traps, POI sequencing | liquidity + structure |
| `TrendAgent` | Trend direction/strength | EMA alignment, ADX |
| `VolumeAgent` | Volume delta, profile, climax | volume profile, delta |
| `NewsAgent` | High-impact event proximity | `NewsEngine` calendar |
| `RiskAgent` | Setup risk quality (R:R, stop placement) | `RiskEngine` |
| `PsychologyAgent` | Overtrading, tilt, discipline flags | journal + session stats |

`RULE` A new agent must: (a) be single-concern, (b) read only `[0..i]`, (c) return calibrated `confidence`, (d) supply human `reasoning`, and (e) declare a `weight` for the orchestrator. Adding an agent must not modify existing agents (Open/Closed).

## 7.3 Prompt Strategy (for the narration LLM only)

- **System prompt** fixes the persona ("institutional SMC/ICT analyst"), the non-advice disclaimer, and the output schema (JSON with `bias`, `confidenceLabel`, `confluences[]`, `explanation`).
- **Structured input, not free text.** The LLM receives the *already-computed* `MasterDecision` (bias, score, satisfied/missing confluences, per-agent reasoning) and is asked only to narrate it in plain language. `RULE` The LLM is never given raw candles and asked to "decide."
- **Determinism controls:** low temperature, fixed schema, response validated against a Kotlin `@Serializable` DTO; on validation failure, fall back to a deterministic template string (offline path).
- **Token discipline:** send compact numeric summaries, not full arrays. Cache narrations keyed by decision hash.
- `SECURITY` No account secrets, API keys, or PII are ever placed in a prompt. Prompts are scrubbed and rate-limited.

## 7.4 Reasoning Engine

The reasoning engine converts agent outputs into a weighted, explainable verdict (`reference/agents/orchestrator.ts`):
1. **Collect** every `AgentAnalysis`.
2. **Directional vote** = weighted sum of `direction × confidence × weight` across agents → net bias + aggregate strength.
3. **Confluence extraction** = the set of institutional factors the agents collectively confirmed (sweep, BOS/CHoCH, FVG, OB, SMT, session, HTF bias, trend, volume).
4. **Disagreement handling:** conflicting high-confidence agents lower the aggregate confidence and are surfaced explicitly ("TrendAgent bullish vs SmartMoneyAgent bearish").

## 7.5 Master Decision Engine

`reference/agents/master-decision-engine.ts` is the final gate. Port target: `domain/usecase/ai/MasterDecisionEngine.kt`.

**Approval requires ALL of:**
- **Confluence count** ≥ threshold (reference default: **≥ 5 of 9** confluences).
- **Aggregate confidence** ≥ threshold (e.g., 0.65).
- **Risk gate** passes (`RiskEngine.canOpenTrade` → allowed).
- **Psychology gate** passes (no tilt/overtrading block).

**Output — `MasterDecision`:**
```
MasterDecision {
  action: BUY | SELL | NO_TRADE
  grade: WEAK | MODERATE | STRONG | INSTITUTIONAL   // scales with confluence count + confidence
  confidence: 0..1
  entry, stopLoss, takeProfit, positionSize          // from RiskEngine
  satisfiedConfluences: [...], missingConfluences: [...]
  reasoning: [ per-agent lines ]
  vetoedBy: RISK | PSYCHOLOGY | null
}
```

`RULE` A `RISK` or `PSYCHOLOGY` veto forces `action = NO_TRADE` regardless of confluence/confidence. Risk always wins ([5.4](#54-risk-engine)).

```
 Agents ─▶ Reasoning Engine ─▶ (confluence count, aggregate confidence)
                                         │
                          ┌──────────────┴───────────────┐
                          ▼                               ▼
                   count ≥ 5 AND conf ≥ 0.65?      Risk & Psychology gates
                          │ yes                          │
                          └────────────┬─────────────────┘ pass
                                       ▼
                          MasterDecision(action, grade, R:R, explanation)
                                       │ veto? → NO_TRADE
```

## 7.6 Confidence Engine

- Per-agent confidence is **calibrated**, not arbitrary: it maps concrete measurements to `[0,1]` (e.g., displacement size vs ATR, retracement depth vs OTE band, ADX magnitude, volume delta z-score).
- Aggregate confidence combines agent confidences by weight, penalized by disagreement and by missing high-weight confluences.
- Confidence maps to a human label: `<0.4 Low`, `0.4–0.65 Moderate`, `0.65–0.8 High`, `>0.8 Very High`.
- `RULE` Confidence must be monotonic and explainable: if a new confluence appears, confidence must not decrease without a stated reason.

## 7.7 Trade Explanation Engine

Produces the human narrative for a `MasterDecision`: the setup story ("London sweep of Asian low → CHoCH → bullish FVG + unmitigated demand OB in discount, OTE 0.705"), the satisfied/missing confluences, the R:R and invalidation, and the risk verdict. Backed deterministically by templates; optionally polished by the narration LLM ([7.3](#73-prompt-strategy-for-the-narration-llm-only)). Also feeds the journal (auto-annotation) and alerts (rich alert bodies).

## 7.8 Market Explanation Engine

`reference/ai/mentor-assistant.ts` + `market-scanner.ts`: explains the *current state* of a symbol independent of a trade ("HTF bullish, price in premium, likely to seek discount/liquidity below X before continuation"). Powers the mentor/education surface and the scanner's per-symbol rationale.

## 7.9 Optimization Engine

`reference/backtest/optimizer.ts` + `monte-carlo.ts`: strategy parameter optimization (grid / walk-forward) and **Monte-Carlo robustness** (shuffle/bootstrap trade sequences to estimate drawdown distributions and the probability of ruin). `WARNING` Optimization must use walk-forward / out-of-sample validation to avoid curve-fitting; report in-sample vs out-of-sample metrics side by side.

## 7.10 Learning Engine

- **Today:** the journal + risk history provide the feedback loop; Kelly sizing adapts to realized win-rate/edge ([5.4](#54-risk-engine)); the `PsychologyAgent` learns discipline patterns from journal stats.
- **Future (H5):** per-user setup performance tracking (which confluence stacks the user wins/loses with), adaptive agent weights, and personalized alert thresholds — all on-device by default for privacy.
- `RULE` Any learning that adapts trade behavior must be transparent (surfaced to the user) and must never bypass the risk gate.

## 7.11 Voice & Future AI Expansion

- `reference/voice/voice-assistant.ts` is the blueprint for hands-free queries ("What's the London bias on EURUSD?").
- Expansion path: on-device small models for narration/privacy, image understanding of chart screenshots, and multi-symbol portfolio-level reasoning. Every expansion keeps the deterministic decision core authoritative.

---

# 8. Data Layer

The data layer implements the domain's repository interfaces and owns all persistence, networking, and streaming. It is the only layer allowed to touch Room, Retrofit, OkHttp, and DTOs.

## 8.1 Responsibilities & Shape

```
data/
├── local/      Room: FoxDatabase, CandleDao, CandleEntity
├── remote/
│   ├── api/    MarketApi (Retrofit REST)
│   ├── dto/    CandleDto (kotlinx.serialization)
│   └── websocket/  MarketWebSocket (interface) + BinanceWebSocket (impl)
├── mapper/     CandleMapper (pure DTO/Entity <-> domain)
├── repository/ MarketRepositoryImpl (SSOT orchestration), SampleData (seed)
└── alerts/     AlertDispatcher (Android notifications)
```

## 8.2 Offline Cache & Database

`FoxDatabase` (Room) — current schema:
- **Entity `CandleEntity`** in table `candles`, **composite primary key `(symbol, timeframe, timestamp)`**, columns: `open, high, low, close, volume`.
- `RULE` The composite PK + `OnConflictStrategy.REPLACE` make writes **idempotent** — refetching a range or receiving an updated forming bar overwrites in place; no duplicates.
- **DAO `CandleDao`:** reactive `observeCandles(symbol, timeframe): Flow<List<CandleEntity>>` (ORDER BY timestamp ASC), one-shot `getCandles(...)`, `upsertAll(...)`, `upsertCandle(...)`, `clear(symbol, timeframe)`, `count(...)`.
- **DB version:** 1. **Migration policy today:** `fallbackToDestructiveMigration()` (acceptable pre-1.0 because candle data is a re-fetchable cache, not user-authored data).

`WARNING` Destructive migration is safe **only** for the re-derivable candle cache. Before adding any user-authored table (journal, drawings, settings) to Room, you **must** switch to real, versioned `Migration` objects and remove destructive fallback for those tables — see [8.7](#87-versioning--migration).

## 8.3 Database Roadmap (user data)

Planned tables (H2), each with a domain repository interface + Room DAO + mapper:

| Table | Owner model | Notes |
|-------|-------------|-------|
| `journal_entries` | `JournalEntry` | syncable; carries `id`, `entryTime`, future `revision` |
| `chart_drawings` | `ChartDrawing` | syncable; per symbol/timeframe |
| `alerts` | `Alert` | rules + state (acknowledged, last fired) |
| `watchlists` | `WatchlistItem` | user symbol universe |
| `settings` | key/value or typed | mirrors `AppPreferences` for backup/sync |

`SECURITY` User-data tables that contain anything sensitive must use **SQLCipher** (encrypted Room) once authentication exists ([10.1](#101-encryption)).

## 8.4 Remote APIs

**REST (`MarketApi` via Retrofit):**
- Base URL `http://10.0.2.2:8000/` (emulator → host; future FastAPI backend). `SECURITY` Production must be HTTPS with certificate pinning ([10.5](#105-certificate-pinning)).
- `OkHttpClient`: 15 s connect, 30 s read/write timeouts; `HttpLoggingInterceptor(BODY)` in **debug only**.
- `Json` config: `ignoreUnknownKeys = true`, `isLenient = true`, `coerceInputValues = true` — resilient to provider schema drift.
- **DTO `CandleDto` → domain `Candle`** via `CandleMapper` (pure functions, the only conversion site).

**WebSocket (`MarketWebSocket` ← `BinanceWebSocket`, `@Singleton`):**
- Subscribes to Binance kline streams (`<symbol>@kline_<interval>`), one shared OkHttp `WebSocket`.
- Exposes `ticks: SharedFlow<TickUpdate>` (buffer 64, `tryEmit` — drop-on-overflow, never block) and `connectionState: StateFlow<ConnectionState>` (`DISCONNECTED / CONNECTING / CONNECTED / RECONNECTING / ERROR`).
- **Resilience:** malformed frames are parsed defensively and dropped (`parseAndEmit` never throws); reconnect with backoff; symbol map tracks active subscriptions; `disconnectAll()` cleans up scope + socket.
- `RULE` The WebSocket writes forming-bar updates into Room (via the repository), so the chart's SSOT stays consistent; the UI never subscribes to the socket directly.

## 8.5 Data Providers (`DataProvider`)

`domain/model/DataProvider.kt` enumerates providers with capability flags and (where relevant) key requirements. The reference `modules/data-provider/providers/` implements adapters for each.

| Provider | Asset focus | Live stream | Key required |
|----------|-------------|-------------|--------------|
| **Dukascopy** | Forex/CFD **tick** history | historical | no |
| **Binance** | Crypto | yes (WS) | no (public data) |
| **Bybit** | Crypto | yes | no/optional |
| Alpaca | US stocks | yes | yes |
| OANDA | Forex | yes | yes |
| Polygon | Stocks/options | yes | yes |
| Twelve Data | Multi-asset | limited | yes |
| Alpha Vantage | Multi-asset | no | yes |
| Interactive Brokers | Multi-asset | yes | yes (gateway) |

- `AppPreferences.canGoLive()` gates streaming on provider capability + presence of any required key.
- `RULE` New providers implement the provider interface (see [2.11](#211-module-split-readiness--plugin-architecture)) + a `MarketWebSocket`/REST adapter; core code changes are not permitted (Open/Closed).

### 8.5.1 Dukascopy & Tick Data

- **Dukascopy** provides high-quality historical **tick** data (bid/ask, volume) for forex/CFDs — the gold standard for realistic backtests.
- Ticks are aggregated into candles at ingestion; raw ticks (H2) feed the tick-accurate backtest/replay path that resolves the SL-vs-TP intrabar ambiguity ([5.5](#55-backtesting-engine)).
- Format: compressed binary blocks per hour; decompress → parse → aggregate → store.

## 8.6 Tick Data, Compression & Storage Optimization

- `PERF` **Candle storage** is compact: 8 numeric columns keyed by `(symbol, timeframe, timestamp)`. A year of H1 candles ≈ 8,760 rows/symbol — trivial. A year of M1 ≈ 525k rows/symbol — significant; enforce **retention** ([8.8](#88-data-retention)).
- **Tick storage** is large. Strategy:
  - Store aggregated candles as the primary, always-available layer.
  - Store ticks only for the ranges the user backtests, in a separate table, **compressed** (delta-encode timestamps, quantize prices to pip integers, GZIP blocks).
  - Evict tick blocks by LRU under a storage budget.
- **Indexing:** the composite PK doubles as the range-scan index (`WHERE symbol=? AND timeframe=? ORDER BY timestamp`). Add a secondary index only if a profiled query needs it.

## 8.7 Versioning & Migration

- `RULE` Every schema change **bumps `FoxDatabase.version`** and ships a Room `Migration(from, to)` **once user-authored tables exist**. Never rely on destructive fallback for user data.
- Provide a `MIGRATIONS` array to the Room builder; write a migration test (`MigrationTestHelper`) for every migration.
- **DTO/model versioning:** the lenient `Json` config tolerates additive backend changes; breaking changes get a new endpoint version or a mapper branch. Sync payloads carry a `schemaVersion` for forward/backward compatibility.

## 8.8 Data Retention

- `RULE` Candle cache retention is bounded per `(symbol, timeframe)` (e.g., keep the most recent N bars needed for the deepest zoom-out, default aligned to the 100k viewport max). Prune older rows on a WorkManager job.
- Tick blocks: LRU eviction under a storage budget (default target: keep total app data < a few hundred MB on device).
- User data (journal/drawings) is **never** auto-deleted; it is backed up/synced instead.

## 8.9 Data Flow Recap & Failure Modes

See [2.6](#26-data-flow-market-data-path). Failure handling in `MarketRepositoryImpl.refreshCandles`:
- Network OK → map DTO→domain→entity → `upsertAll` → observers update.
- Network fails + cache empty → seed `SampleData` (app stays usable).
- Network fails + cache non-empty → return `Result.failure` (surface a toast/snackbar) while cached data keeps serving.

`TROUBLESHOOT` *Chart shows synthetic-looking data* → the backend at `10.0.2.2:8000` is unreachable and the empty-cache seed path ran. Start the backend or select a live-capable provider with a valid key, then pull-to-refresh.

`TROUBLESHOOT` *Duplicate/january-1970 candles* → a timestamp unit mismatch (seconds vs milliseconds) in a new provider mapper. Normalize all timestamps to **epoch milliseconds** in the mapper; the composite PK assumes ms.


---

# 9. Performance Engineering

Performance is budgeted, measured, and enforced. This section defines the budgets, the instrumentation, and the optimization discipline for every subsystem — not just the chart ([Section 4](#4-chart-engine-bible) covers the renderer specifically).

## 9.1 Performance Budgets

`RULE` These are hard budgets. A change that breaches a budget must be fixed or explicitly waived in review with a follow-up ticket.

| Domain | Budget | Floor |
|--------|--------|-------|
| Chart frame time | ≤ 8.33 ms (120 Hz) / ≤ 16.67 ms (60 Hz) | budget usage < 100% |
| Draw-pass heap allocation | 0 bytes/frame | 0 bytes/frame |
| Cold start (app open → first frame) | < 800 ms | < 1500 ms |
| Chart data ready → first draw | < 1 frame | < 2 frames |
| Timeframe switch → redraw | < 100 ms | < 250 ms |
| Indicator recompute (visible range) | < 8 ms | < 16 ms |
| Full backtest (10k bars, simple strategy) | < 300 ms | < 1 s |
| Scanner scan (30 symbols, H1) | < 1.5 s | < 4 s |
| Room range query (2k candles) | < 5 ms | < 20 ms |
| WebSocket tick → chart update | < 50 ms | < 150 ms |
| Steady-state memory (chart open, 10k candles) | < 180 MB | < 256 MB |
| Jank rate (dropped frames during 60 s use) | < 0.1% | < 1% |

## 9.2 CPU

- Push all analysis to `@DefaultDispatcher`; keep the Main thread for state + gestures + draw dispatch.
- `PERF` Prefer primitive arrays (`DoubleArray`/`IntArray`) and index loops over `List<T>` + iterators + boxing in hot analysis (existing indicator convention).
- Compute indicators/SMC **once per input change**, cache results; never recompute per frame or per recomposition.
- Parallelize embarrassingly-parallel work (scanner across symbols, MTF across timeframes) with bounded `async` fan-out; avoid oversubscription on low-core devices.
- Cooperative cancellation (`ensureActive()`) in long loops so a superseded backtest/scan stops immediately.

## 9.3 GPU / Rendering

- Covered in [4.5](#45-gpu--hardware-acceleration), [4.14](#414-adaptive-quality--120-fps-optimization). Summary rules: viewport culling, zero per-frame allocation, hoisted `Paint`, clipped layers, `invalidateTick` redraw trigger, adaptive quality degradation before the budget blows.
- Prefer simple hardware-accelerated primitives; avoid per-frame `Path` allocation and per-frame `drawText` beyond axis/crosshair labels.

## 9.4 Memory

- `PERF` Steady-state heap budget 180 MB with 10k candles open. Watch for:
  - Retaining multiple large candle lists (keep the DB as SSOT + one visible window).
  - Leaked coroutine scopes / un-cancelled streams.
  - `Bitmap`s held in state.
- Use LeakCanary in debug to catch Activity/ViewModel/scope leaks.
- Primitive arrays over boxed collections in analysis reduces both CPU and allocation pressure.

## 9.5 Battery

- `collectAsStateWithLifecycle()` stops flow collection off-screen.
- Close/unsubscribe the WebSocket when the chart is not visible; do not stream in the background without a foreground service justification.
- Batch/debounce network refreshes; respect Doze/App Standby for background scans (WorkManager constraints).
- `PERF` The adaptive quality controller reduces GPU/CPU load under thermal pressure, indirectly protecting battery and preventing thermal throttling.

## 9.6 Startup

- Single Activity + Compose; minimal work in `FoxTraderApp.onCreate` (Hilt graph only).
- `RULE` No disk/network I/O on the main thread during startup. Room and Retrofit are lazily provided by Hilt and touched off-main.
- Defer non-critical initialization (news, scanner priming) until after first frame.
- Seed/first-draw path is designed so the chart never blocks on the network ([8.9](#89-data-flow-recap--failure-modes)).

## 9.7 Network

- Tight timeouts (15 s connect / 30 s read); lenient JSON parsing; drop-on-overflow tick buffer so a fast stream can never back-pressure the UI.
- Coalesce range fetches; cache in Room; avoid redundant refetches of already-cached ranges.
- `PERF` For history paging (H2), request pages sized to the viewport, not the whole history.

## 9.8 Database

- Idempotent upserts; composite-PK range scans; ascending-ordered reads.
- Do writes in transactions (`upsertAll`) and off the Main thread.
- Enforce retention to keep tables small and queries fast ([8.8](#88-data-retention)).

## 9.9 Profiling

- **In-app:** `PerformanceProfiler` (rolling FPS, avg/worst frame, budget %, dropped/spike counts, tiers) + a debug FPS/tier overlay.
- **Android Studio Profiler:** CPU (method/sampling traces), Memory (allocation tracking — verify 0 alloc in draw), Energy.
- **Macrobenchmark / Baseline Profiles:** measure cold start, scroll jank; ship a Baseline Profile to speed startup (H2).
- **Systrace/Perfetto:** confirm frames complete within budget on the RenderThread.
- `RULE` Profile on a **mid-range physical device**, not just the emulator; emulator FPS is not representative.

## 9.10 Optimization Checklist (whole app)

- [ ] Analysis off the Main thread (`@DefaultDispatcher`); results cached until inputs change.
- [ ] Zero allocation in draw loop and coordinate transforms.
- [ ] `collectAsStateWithLifecycle` everywhere state is collected.
- [ ] WebSocket closed when chart not visible; tick buffer drop-on-overflow.
- [ ] Room writes batched + off-main; retention job scheduled.
- [ ] No main-thread I/O at startup; heavy init deferred past first frame.
- [ ] `PerformanceProfiler` shows no CRITICAL tier during interaction on a mid-range device.
- [ ] Backtests/scans use cooperative cancellation.
- [ ] LeakCanary clean; steady-state heap within budget.
- [ ] Macrobenchmark cold-start + jank within budgets; Baseline Profile shipped.

## 9.11 Performance Regression Gate

`RULE` Performance is protected in CI (H2): a Macrobenchmark job compares cold-start and jank metrics against a committed baseline; a regression beyond tolerance fails the build. New chart features must include a note in the PR describing measured frame-time impact.

---

# 10. Security

FoxTrader handles account balances, trade history, API keys, and (H3+) authentication. Security is designed defense-in-depth. Many controls are specified here as the implementation target (`reference/typescript-src/security/security.ts` is the design source); the app is pre-auth today, so this section is largely forward-normative — but the rules apply the moment each capability lands.

## 10.1 Encryption

- **Data at rest:**
  - `RULE` Secrets (API keys, tokens) are stored via **Jetpack Security `EncryptedSharedPreferences`** / DataStore backed by a key in the **Android Keystore** — never plaintext prefs.
  - `RULE` Sensitive user-data tables (journal, account) use **SQLCipher-encrypted Room** once auth exists ([8.3](#83-database-roadmap-user-data)). The candle cache (non-sensitive, re-derivable) may remain unencrypted for performance.
- **Data in transit:** `SECURITY` TLS 1.2+ only; production `BASE_URL` must be HTTPS. The current `http://10.0.2.2:8000` is emulator-only dev and must never ship.
- **Keys:** hardware-backed Keystore (StrongBox where available); keys are non-exportable; rotate on credential change.

## 10.2 Authentication

- **Target (H3):** token-based auth (OAuth2/OIDC or the FastAPI backend's JWT). Access + refresh tokens stored encrypted; short-lived access tokens; refresh rotation.
- `RULE` No credential is ever logged, placed in an analytics event, or included in an AI prompt.
- Offline mode works without auth (local-only); sync/backup requires auth.

## 10.3 Authorization

- Least privilege: the app requests only what it needs. Server-side, per-user data is scoped by user ID; the client never trusts client-supplied identity.
- Feature gating (e.g., premium/marketplace) is enforced server-side, not just hidden in the UI.

## 10.4 Biometrics

- **Target:** `androidx.biometric` (BiometricPrompt) to gate app open and sensitive actions (revealing keys, placing orders via a broker SDK).
- `RULE` Biometrics unlock a Keystore key (`setUserAuthenticationRequired(true)`), so a spoofed UI cannot bypass crypto. Provide a device-credential fallback. Never store biometric data; the OS owns it.

## 10.5 Certificate Pinning

- `RULE` Production OkHttp uses a `CertificatePinner` pinning the backend's leaf/intermediate SPKI hashes; include a backup pin and a rotation runbook.
- `WARNING` Ship pins with an expiry/rotation plan; a mispinned build cannot talk to the backend and cannot be fixed without an app update. Keep a remote-config kill-switch strategy for emergencies (H3).

## 10.6 Root / Emulator Detection

- **Target:** integrate **Play Integrity API** for device/app attestation on sensitive flows (auth, orders, sync).
- Optional heuristic root checks (su binaries, test-keys, Magisk) inform a risk score; `RULE` do **not** hard-block solely on heuristics (false positives) — combine with Play Integrity and degrade sensitive features rather than bricking the app.

## 10.7 Anti-Debugging

- `RULE` Release builds set `debuggable=false` (default for release) and strip logging.
- Optional runtime checks (`Debug.isDebuggerConnected`, tracer-pid) may raise a risk signal for sensitive flows; keep them advisory, not app-breaking.

## 10.8 Anti-Tampering

- **R8/ProGuard** enabled in release for shrink + obfuscation (`isMinifyEnabled = true`, `proguard-rules.pro`).
- `RULE` Verify signing (`GET_SIGNING_CERTIFICATES`) and app source (installer package) for sensitive flows; combine with Play Integrity's app-recognition verdict.
- Sensitive constants are not hard-coded; secrets come from Keystore/backend, never from strings in the APK.

## 10.9 Secrets Management

- `RULE` **No secrets in the repo.** No API keys, tokens, or keystore passwords committed. Local dev secrets live in `local.properties` / environment / Gradle properties that are git-ignored; CI secrets live in GitHub Actions **encrypted secrets**.
- The release **keystore** and its passwords are stored only in CI secrets / a secure vault (see [14.2](#142-release-signing)).
- User-provided provider API keys are stored in `EncryptedSharedPreferences`, entered by the user, and never leave the device except to the provider over TLS.
- `SECURITY` Scrub logs: no PII, no keys, no tokens, no full request bodies in release logging (body logging is debug-only in `NetworkModule`).

## 10.10 Privacy & Compliance

- Minimize data collection; prefer on-device processing (analysis, AI narration where feasible).
- Provide a data export + delete path for user-authored data (journal/drawings) to support privacy requests.
- `RULE` Any third-party SDK (crash/analytics) must be documented in a data-safety inventory and reflected in the Play Data Safety form ([14.5](#145-analytics)).

## 10.11 Threat Model (summary)

| Threat | Control |
|--------|---------|
| Device theft | Keystore + biometrics + encrypted user data |
| MITM on API | HTTPS + certificate pinning |
| Repackaged/tampered APK | R8 obfuscation + signing check + Play Integrity |
| Rooted device exfiltration | Encrypted storage, Play Integrity, degrade sensitive features |
| Secret leakage | No secrets in repo/APK; Keystore; CI secrets; scrubbed logs |
| Malicious plugin (H4) | Sandboxed plugin API, read-only market access, no state mutation |
| Prompt injection via market/news text | Structured inputs to LLM; LLM never decides trades ([7.3](#73-prompt-strategy-for-the-narration-llm-only)) |


---

# 11. UI Design System

The **Fox Design System** is a dark-first, institutional visual language: a cool blue-black base with a warm amber accent (the fox identity) and monospace tabular numerals for prices. It is implemented in `ui/theme/` (`Color.kt`, `Type.kt`, `Theme.kt`) and mapped onto Material 3 color roles so every composable inherits it via `MaterialTheme`.

`RULE` Feature UI reads color and type from `MaterialTheme.colorScheme` / `MaterialTheme.typography`. Raw `Color(0x…)` literals are permitted **only** inside the chart renderer (which draws with semantic tokens like `FoxBullish` directly for performance) and inside `ui/theme/`.

## 11.1 Color

**Neutrals (cool blue-grey ramp)** — backgrounds → text:

| Token | Hex | Role |
|-------|-----|------|
| `FoxNeutral0` | `#080B12` | Deepest background (`background`) |
| `FoxNeutral5` | `#0C1019` | Surface |
| `FoxNeutral10` | `#111720` | Card / panel (`surfaceVariant`, `surfaceContainer`) |
| `FoxNeutral15` | `#181F2B` | Elevated (`surfaceContainerHigh`) |
| `FoxNeutral20` | `#1F2735` | Subtle border (`outlineVariant`) |
| `FoxNeutral30` | `#2D3748` | Border (`outline`) |
| `FoxNeutral40` | `#3D4A5C` | Muted |
| `FoxNeutral60` | `#7A8494` | Secondary text (`onSurfaceVariant`) |
| `FoxNeutral80` | `#C4C9D4` | Body text |
| `FoxNeutral90` | `#E4E7EC` | Primary text (`onBackground`, `onSurface`) |

**Accent (Fox amber):** `FoxAmber40 #B89040`, `FoxAmber50 #D4A84E` (**primary**), `FoxAmber60 #E6BE6A` (hover), `FoxAmber70 #F0D48E` (light).

**Trading semantics:** `FoxBullish #00C873` / `FoxBullishText #00E688`; `FoxBearish #E8364F` / `FoxBearishText #FF5C72`.
**System semantics:** `FoxInfo #3B8DF0`, `FoxWarning #E6A030`, `FoxError #E8364F`, `FoxSuccess #00C873`.
**Light theme surfaces:** `FoxLightBg #FAFBFD`, `FoxLightSurface #FFFFFF`, `FoxLightSurfaceRaised #F3F4F7`, `FoxLightBorder #E4E7EC`, `FoxLightText #111720`, accent `FoxAmberLight #B89040`.

`RULE` **Never** use raw red/green for bull/bear in feature UI — use `FoxBullish`/`FoxBearish` so the palette stays consistent and remains tunable for accessibility ([11.11](#1111-accessibility)).

## 11.2 Typography

`FoxTypography` (Material 3 `Typography`) — geometric scale, tight tracking for an institutional feel; system sans for UI, monospace for numbers:

| Role | Size / line | Weight | Tracking | Use |
|------|-------------|--------|----------|-----|
| `displaySmall` | 28 / 34 | Bold | −0.5 | Big screen titles / hero prices |
| `titleLarge` | 18 / 24 | SemiBold | −0.2 | Screen/app-bar titles |
| `titleMedium` | 14 / 20 | SemiBold | — | Section headers |
| `bodyLarge` | 14 / 20 | Normal | — | Primary body |
| `bodyMedium` | 13 / 18 | Normal | — | Secondary body |
| `labelLarge` | 12 / 16 | Medium | — | Buttons / chips |
| `labelSmall` | 11 / 14 | SemiBold | +0.5 | Overline / tags |

- `FoxMono = FontFamily.Monospace` and `FoxPriceStyle` (mono, Medium, 14sp, −0.2 tracking).
- `RULE` **All prices, P&L, and numeric market data use `FoxPriceStyle`/`FoxMono`** so digits are tabular and columns align — a hallmark of professional trading UIs. Never render prices in a proportional font.

## 11.3 Spacing & Layout

- `RULE` Use a **4dp base grid**; standard steps: 4, 8, 12, 16, 24, 32. Screen gutters 16dp; card padding 12–16dp; list item min height 48dp (touch target).
- Honor window insets via `Scaffold` padding (edge-to-edge). Never hard-code status/nav bar heights.
- Content max-width applies on tablets/foldables ([11.13](#1113-landscape-foldables--tablets)).

## 11.4 Icons

- Material Symbols (outlined) as the default set; the app/launcher icon is the fox mark (`resources/icon.svg`, adaptive `mipmap` foreground/background).
- `RULE` Icon buttons are ≥ 48×48dp touch targets with a `contentDescription` (or `null` when purely decorative). Tint icons with `onSurface`/`onSurfaceVariant`, accent actions with `primary`.

## 11.5 Motion & Animation

- **Direct manipulation** for the chart (1:1 with the finger, no easing) — predictability beats flourish in trading tools.
- **UI transitions** use Material 3 motion: emphasized-decelerate for enter, standard for state changes; durations 150–300ms. Prefer `animate*AsState`/`AnimatedVisibility`/`Crossfade`.
- `RULE` No animation on the chart's data draw path; motion is reserved for navigational/affordance UI. Respect the system "remove animations" accessibility setting.

## 11.6 Components

Standard, reusable composables (shared UI lands in a future `:core-ui` module — [2.11](#211-module-split-readiness--plugin-architecture)):
- **Timeframe chips** (tappable `FilterChip` row; selected = amber).
- **Metric card** (label in `labelSmall`, value in `FoxPriceStyle`, colored by bull/bear/neutral).
- **Scanner row** (symbol, score, category badge, tag chips).
- **Bias badge** (BULLISH/BEARISH/NEUTRAL pill).
- **Structure/SMC legend** chips.
- `RULE` Components are stateless (`value` + `onEvent`), themed, and preview-backed.

## 11.7 Cards

- Surface `surfaceVariant`/`surfaceContainer` (`FoxNeutral10`), 12–16dp corner radius, 1dp `outlineVariant` border, subtle elevation (dark theme relies on surface tint, not heavy shadows). Section-header + content structure.

## 11.8 Dialogs

- Material 3 `AlertDialog` for confirmations; the **Symbol Picker** (`SymbolPickerDialog`) is a searchable, categorized instrument list.
- `RULE` Dialogs are dismissible, keyboard-aware, and never block on I/O (show loading state instead).

## 11.9 Bottom Sheets

- `ModalBottomSheet` for contextual tools: drawing toolbar options, indicator settings, replay controls, alert creation.
- `RULE` Bottom sheets respect IME + navigation insets and support drag-to-dismiss; heavy content lazy-loads.

## 11.10 Chart Controls (domain-specific UI)

- **Drawing toolbar** (`DrawingToolbar`): tool selection (trend line, ray, rectangle, fib, etc.), color, delete.
- **Indicator panel** (`IndicatorPanel`): toggle/parameterize overlays.
- **Replay overlay** (`ReplayOverlay`): transport controls + progress + speed.
- `RULE` These float over the chart with translucent scrims and never steal the chart's pan/zoom gestures ([4.6](#46-touch--gesture-engine)).

## 11.11 Accessibility

- `RULE` **Do not encode meaning in color alone.** Bull/bear must also differ by shape/label/sign (▲/▼, +/−) for color-vision deficiency; offer a color-blind-safe palette variant.
- Text contrast meets **WCAG AA** (4.5:1 body): the neutral ramp + amber accent are tuned for this on the dark base.
- Touch targets ≥ 48dp; all actionable elements have `contentDescription`/semantics; support TalkBack, dynamic font scaling (use `sp`, avoid fixed heights that clip scaled text), and reduced-motion.
- `RULE` New screens ship with a semantics pass and are testable with `composeTestRule.onNodeWithContentDescription`.

## 11.12 Theming Behavior

- **Dark-first:** `FoxDarkColors` is the default institutional experience; `FoxLightColors` available and driven by `isSystemInDarkTheme()` (overridable in settings).
- `FoxTraderTheme` tints the status/navigation bars to `background` and sets light/dark status-bar icons via `WindowCompat`.
- `WARNING` Dynamic color (Material You) is intentionally **not** used — brand consistency + chart legibility require the fixed Fox palette. Do not enable `dynamicColorScheme`.

## 11.13 Landscape, Foldables & Tablets

- `MainActivity` handles `orientation|screenSize|screenLayout` config changes itself; Compose recomposes and the chart viewport survives via `remember`.
- `RULE` Use **WindowSizeClass** to adapt: on `Expanded` width (tablets, unfolded foldables, landscape), show a **two-pane** layout (chart + side panel: scanner/journal/indicators) instead of bottom-nav-only.
- **Landscape chart** maximizes chart area (collapse chrome); the renderer is resolution-independent (all transforms are ratio-based).
- **Foldables:** handle fold posture (hinge) via Jetpack WindowManager (H2); avoid placing interactive controls under the hinge.
- **Tablets:** larger touch targets scale naturally; apply content max-width to metric-heavy screens.

---

# 12. Developer Workflow

This section defines how work moves from idea to merged, shippable code.

## 12.1 Environment & Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK** | 17 (Temurin) | Kotlin/Gradle compilation (JVM target 17) |
| **Android SDK** | API 34 (`compileSdk`/`targetSdk`) | Platform APIs |
| **Android Studio** | Hedgehog (2023.1)+ | IDE + Compose preview |
| **Gradle** | 8.9 (via committed wrapper) | Build |
| **Kotlin** | 2.0 | Language + Compose compiler plugin |
| **Git** | 2.30+ | VCS |
| **Device/Emulator** | API 29+ (Android 10) | Run/test |

Full setup (clone, wrapper verify, SDK licenses, run) is in [Appendix A](#appendix-a-environment-setup--prerequisites).

## 12.2 Git Strategy

- **Trunk-based with short-lived feature branches.** `main` is always releasable and protected (no direct pushes; PR + green CI required).
- `RULE` In this sandbox, **never** `git push` directly — use the provided push/PR tooling, always to a **new branch**, never to `main`.
- Keep branches short-lived (< a few days) and rebased on `main` before merge; prefer small, frequently-merged PRs over long-lived branches.

## 12.3 Branch Naming

`RULE` `type/short-description` (kebab-case), optionally with an issue number:
```
feat/fvg-detector
fix/chart-pan-drift
perf/viewport-culling-alloc
refactor/risk-engine-kelly
docs/engineering-bible
chore/bump-compose-bom
test/backtest-metrics
```
Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `chore`, `test`, `build`, `ci`.

## 12.4 Commit Conventions

- `RULE` **Conventional Commits:** `type(scope): summary` in imperative mood, ≤ 72-char subject.
  ```
  feat(chart): add crosshair long-press readout
  fix(chart): remove duplicate pan handler causing drift
  perf(indicators): return DoubleArray to avoid boxing
  docs: rewrite DEVELOPMENT.md as the Engineering Bible
  ```
- Body explains **why**, not just what; reference issues (`Refs #123`, `Closes #123`).
- `RULE` No secrets, no generated artifacts, no commented-out dead code in commits.

## 12.5 Code Review

- `RULE` Every change lands via PR with **at least one approving review** and green CI. No self-merges to `main`.
- Reviewers verify against this Bible: layering ([3.3](#33-clean-architecture-rules)), non-repainting ([1.6](#16-institutional-quality-principles)), zero-alloc hot paths ([9.1](#91-performance-budgets)), injected dispatchers, immutable `UiState`, tests present.
- Keep PRs focused (< ~400 changed lines where feasible); large mechanical changes separated from behavioral ones.
- **Review etiquette:** comments are specific and actionable; use `nit:` for non-blocking suggestions; blocking issues cite the RULE they violate.

## 12.6 Pull Requests

`RULE` PR description template:
```
## What
<one-paragraph summary>

## Why
<motivation / issue link>

## How
<key implementation notes; ADR reference if architectural>

## Testing
<unit/UI tests added; manual test steps; device tested>

## Performance
<frame-time / memory impact for chart or hot-path changes; "n/a" otherwise>

## Screenshots / recordings
<for any UI change>

## Checklist
- [ ] Follows Engineering Bible (layering, non-repainting, zero-alloc, DI)
- [ ] Tests added/updated and passing locally
- [ ] No secrets committed
- [ ] Docs/ADR updated if architecture changed
```

## 12.7 CI/CD

GitHub Actions (`.github/workflows/android.yml`): on push/PR, set up JDK 17, run unit tests and build the debug APK, upload the `foxtrader-debug-apk` artifact.
- `RULE` CI runs Gradle with `--no-configuration-cache --no-daemon` (ADR-007).
- Pipeline stages (target end state): **lint/format → unit tests → assemble debug → (H2) instrumented + macrobenchmark → artifact/publish**.
- Users can install the app by downloading the CI artifact ([Quick Start in README](#appendix-a-environment-setup--prerequisites)).

## 12.8 Build & Run Commands

```bash
./gradlew :app:assembleDebug          # debug APK (fast, no minify)
./gradlew :app:installDebug           # build + install on connected device
./gradlew :app:testDebugUnitTest      # JVM unit tests (domain + VMs)
./gradlew :app:assembleRelease        # release APK (R8/minify on)
./gradlew :app:lintDebug              # Android Lint
./gradlew :app:kspDebugKotlin         # regenerate Hilt/Room (after DI changes)
adb install app/build/outputs/apk/debug/app-debug.apk
```
`RULE` Do not enable the Gradle configuration cache (ADR-007); it breaks cold Hilt/KSP builds.

## 12.9 Quality Gates

`RULE` A PR is mergeable only when ALL pass:
1. Compiles (debug + release).
2. All unit tests green.
3. Android Lint: no new errors.
4. ktlint/detekt (H2): no new violations.
5. No new performance-budget regression ([9.11](#911-performance-regression-gate)).
6. At least one approving review.
7. No secrets / no committed build artifacts.

## 12.10 Definition of Done

A change is **Done** when:
- [ ] Meets the requirement/acceptance criteria.
- [ ] Adheres to this Engineering Bible (layering, non-repainting, DI, zero-alloc where applicable).
- [ ] Unit tests cover new domain logic; regression test added for any bug fix.
- [ ] UI changes have previews + a manual test on a physical device.
- [ ] Performance impact measured for chart/hot-path changes and within budget.
- [ ] Docs/ADR/steering updated if behavior or architecture changed.
- [ ] Merged to `main` via green PR; CI artifact builds.

## 12.11 Code Style & Conventions

- `RULE` Official **Kotlin style** (`kotlin.code.style=official`); 4-space indent; ~120-col soft limit.
- Explicit visibility for public API; `internal` by default for module-private; prefer `val`, immutability, and expression bodies.
- Naming: `PascalCase` types, `camelCase` members, `SCREAMING_SNAKE_CASE` consts, `Impl` suffix only for the single obvious implementation of an interface.
- One top-level class per file (small related helpers may share a file); package = layer/feature.
- `RULE` No `!!` in production code; handle nullability explicitly. No `GlobalScope`. No hard-coded dispatchers ([3.11](#311-coroutines)).
- KDoc on every public engine/use case describing inputs, the non-repainting guarantee, and units (e.g., timestamps = epoch ms, prices = instrument quote units).

## 12.12 Dependency Management

- `RULE` **All** versions live in the Gradle **version catalog** (`gradle/libs.versions.toml`); modules reference `libs.*` aliases. No inline version strings in `build.gradle.kts`. See [Appendix B](#appendix-b-dependency-version-catalog).
- Adding/upgrading a dependency: update the catalog, justify in the PR, check transitive impact + license, and confirm the build + tests stay green.
- Prefer first-party Jetpack/Kotlin libraries; every third-party SDK must be logged in the data-safety inventory ([10.10](#1010-privacy--compliance)).


---

# 13. QA Bible

Quality is engineered, not inspected in at the end. FoxTrader's testability comes from its architecture: the domain is pure Kotlin, so the analytical core is exhaustively testable on the JVM without an emulator.

## 13.1 Testing Pyramid & Where Tests Live

```
             ▲  fewer, slower, higher-fidelity
   ┌─────────┴─────────┐
   │  E2E / Instrumented│  Espresso + Compose UI test (H2): critical user journeys
   ├───────────────────┤
   │  Integration       │  ViewModel + fake repo + Room in-memory; mapper round-trips
   ├───────────────────┤
   │  Unit (the base)   │  Pure domain engines: structure, indicators, SMC, risk,
   │                    │  backtest, replay, drawing, orders, heatmap  ← MOST TESTS HERE
   └───────────────────┘
             ▼  many, fast, deterministic
```

`RULE` The majority of tests are **domain unit tests** (JVM, no Android). Existing suites: `AnalyzeMarketStructureUseCaseTest`, `TechnicalIndicatorsTest`, `NewIndicatorsTest`, `SmcDetectorTest`, `OrderManagerTest`, `ReplayEngineTest`, `DrawingEngineTest`, `MarketHeatmapTest`.

## 13.2 Toolchain

| Tool | Version (catalog) | Use |
|------|-------------------|-----|
| JUnit 4 | 4.13.2 | test runner |
| kotlinx-coroutines-test | 1.9.0 | `runTest`, test dispatchers |
| Turbine | 1.1.0 | `Flow`/`StateFlow` assertions |
| MockK | 1.13.12 | fakes/mocks for repos & use cases |
| AndroidX Test / JUnit ext | 1.2.1 | instrumented |
| Espresso | 3.6.1 | instrumented UI flows |
| Compose UI Test (`ui-test-junit4`) | Compose BOM | composable tests |
| Room `MigrationTestHelper` | Room 2.6.1 | migration tests (once migrations exist) |

## 13.3 Unit Tests

- `RULE` Every engine/use case has unit tests covering the happy path, boundaries (empty/insufficient data — e.g., `< 50 bars`, `< 2 candles`), and the **non-repainting invariant**.
- **Determinism:** use fixed candle fixtures (a `SampleData`-style generator seeded constant). No `Random` without a fixed seed; no wall-clock dependence (inject a clock where time matters, e.g., sessions/alerts).
- **Numeric assertions:** compare doubles with a tolerance (`assertEquals(expected, actual, 1e-6)`).
- **Example — non-repainting test (mandatory pattern for detectors):**
  ```kotlin
  @Test fun `structure break is stable when future bars arrive`() {
      val full = fixtureCandles()               // e.g., 200 bars
      val atI  = analyze(full.subList(0, 120))  // detect using [0..119]
      val later = analyze(full.subList(0, 160)) // more data later
      // every confirmed break detected at 120 must still exist unchanged at 160
      assertTrue(atI.breaks.filter { it.confirmed }
          .all { b -> later.breaks.any { it.breakIndex == b.breakIndex && it.type == b.type } })
  }
  ```

## 13.4 Integration Tests

- **ViewModel + fake repository:** drive `onEvent`, assert `uiState` transitions with Turbine; inject test dispatchers (via the dispatcher qualifiers) and `advanceUntilIdle()`.
- **Room (in-memory):** `Room.inMemoryDatabaseBuilder(...)` to test DAO queries, ordering (`ORDER BY timestamp ASC`), and idempotent upserts (composite PK REPLACE → no duplicates).
- **Mapper round-trips:** `domain → dto → domain` and `domain → entity → domain` are identity-preserving; timestamps stay epoch-ms.

## 13.5 UI Tests (Compose)

- `createComposeRule()`; assert with `onNodeWithText/ContentDescription`; test states (loading/empty/error/content) by feeding hand-built `UiState` to stateless content composables.
- Critical journeys (H2, instrumented): switch timeframe, pan/zoom the chart, open symbol picker + select, run a scan, create an alert, add a journal entry.
- `RULE` UI tests assert **semantics/behavior**, not pixels. Chart rendering fidelity is validated via the performance/benchmark path and screenshot tests, not brittle pixel diffs.

## 13.6 Performance Tests

- **Macrobenchmark (H2):** cold/warm startup, chart scroll jank (`FrameTimingMetric`), measured on a physical mid-range device, compared to a committed baseline ([9.11](#911-performance-regression-gate)).
- **Microbenchmark:** hot paths — coordinate transforms, indicator computation over 10k bars, `SmcDetector` passes — assert time budgets from [9.1](#91-performance-budgets).
- **Allocation assertion:** verify zero allocations across a simulated draw pass (allocation-tracking harness / manual profiler check gated in review).

## 13.7 Stress Tests

- **Large datasets:** load 100,000 candles; assert culled draw loop touches only the visible window and frame time stays within budget.
- **High-rate ticks:** flood `BinanceWebSocket.ticks` beyond the 64-buffer; assert drop-on-overflow (no back-pressure, no crash, UI stays live).
- **Long-running replay/backtest:** run to completion + cancel mid-run; assert cooperative cancellation and no scope leaks.
- **Memory soak:** open/close chart + switch symbols repeatedly; assert steady-state heap within budget (LeakCanary clean).

## 13.8 Security Tests

- `RULE` **Secret scanning** in CI (gitleaks/trufflehog) — build fails on any committed key/token.
- Verify release build: `debuggable=false`, logging stripped, R8 mapping produced.
- (H3) Verify encrypted storage (no plaintext secrets/PII on disk), certificate pinning (connection fails against a wrong cert), and biometric-gated Keystore access.
- Dependency vulnerability scan (e.g., OWASP dependency-check / GitHub Dependabot alerts).

## 13.9 Regression Tests

- `RULE` **Every bug fix ships with a regression test** that fails before the fix and passes after. Name it after the defect (e.g., `chart pan does not double-apply on single-finger drag` guards the [4.6](#46-touch--gesture-engine) drift bug).
- Keep a "regression suite" tag for known historical defects; never delete a regression test.

## 13.10 Benchmark Tests

- Codify the [4.15](#415-benchmark-targets-chart)/[9.1](#91-performance-budgets) targets as automated benchmarks with pass/fail thresholds; publish results as CI artifacts and track trend over time.

## 13.11 Test Automation & Coverage

- `RULE` CI runs `:app:testDebugUnitTest` on every push/PR (gate #2 in [12.9](#129-quality-gates)); instrumented + macrobenchmark run on a device farm/emulator matrix (H2).
- **Coverage targets:** domain layer ≥ **85%** line coverage (it is pure and cheap to test); overall ≥ 60%. Coverage is a signal, not a goal — a non-repainting test with no assertions is worthless. Enforce via JaCoCo/Kover report in CI.
- Flaky tests are quarantined and fixed, never `@Ignore`d silently.

---

# 14. Deployment

## 14.1 Google Play

- **Distribution:** Google Play (production track) + internal/closed/open testing tracks; CI debug-APK artifact for developer sideloading ([Appendix A](#appendix-a-environment-setup--prerequisites)).
- `RULE` Ship an **Android App Bundle (`.aab`)** to Play (`bundleRelease`) for per-device optimized delivery, not a universal APK.
- **Identity:** `applicationId = com.foxtrader.app` (debug builds use the `.debug` suffix so both can co-exist on one device).
- **Store listing** must carry the analytical-tool disclaimer ([1.1](#11-mission)); no profit claims. Categorize as Finance; complete content rating and Data Safety accurately.

## 14.2 Release Signing

- `RULE` Release builds are signed with the upload key via **Play App Signing** (Google manages the app signing key; we hold the upload key).
- The upload keystore + passwords are stored **only** in CI encrypted secrets / a secure vault — **never** in the repo, never in `gradle.properties` committed to VCS. The signing config reads them from environment/secret injection at build time.
- `WARNING` Losing the upload key requires a Play-assisted reset; back it up in the org secret vault with documented recovery. Never email or Slack a keystore.

## 14.3 Build Types & Flavors

- **debug:** `applicationIdSuffix ".debug"`, `debuggable true`, HTTP logging on, no minify — for development.
- **release:** `isMinifyEnabled true` (R8 shrink + obfuscate via `proguard-rules.pro`), `debuggable false`, HTTPS-only backend, logging stripped.
- Future flavors (H3): `dev` / `staging` / `prod` pointing at different backend base URLs (never hard-code prod URLs in `dev`).

## 14.4 Crash Reporting

- `RULE` Integrate a crash reporter (Firebase Crashlytics or Play Vitals as baseline) for release builds; upload the **R8 mapping file** on every release so stack traces deobfuscate.
- Set user-privacy-safe custom keys (symbol, timeframe, screen) to reproduce crashes; **never** log PII, balances, or secrets into crash metadata.
- Track crash-free-sessions rate as a release health KPI (target ≥ 99.5%).

## 14.5 Monitoring & Analytics

- **Play Vitals:** ANRs, crashes, excessive wakeups, slow/frozen frames — track against the [9.1](#91-performance-budgets) budgets.
- **Analytics (privacy-first):** measure feature usage (which analyses/timeframes are used) with minimized, non-identifying events; document every event in the data-safety inventory ([10.10](#1010-privacy--compliance)).
- `RULE` Analytics is opt-out-respecting and never captures trade content, balances, or PII.

## 14.6 Backups

- **User data:** journal/drawings/settings backed up via encrypted cloud sync ([2.10](#210-synchronization--conflict-resolution-strategy)); provide user-triggered export (JSON) + Android Auto Backup for eligible data (with sensitive tables excluded or encrypted).
- **Candle cache:** not backed up (re-derivable from providers).
- **Backend (H3):** PostgreSQL/TimescaleDB automated backups + point-in-time recovery; Redis is a cache (rebuildable).

## 14.7 Disaster Recovery

| Failure | Response |
|---------|----------|
| Backend outage | App stays functional offline (SSOT is local Room); degrade to cached/seeded data; surface a non-blocking banner |
| Provider outage | Fail over to another configured `DataProvider`; cached data continues to serve |
| Bad release | Play **staged rollout** (start at small %); **halt rollout**/rollback on crash-rate spike; hotfix via new versionCode |
| Data corruption on device | Candle cache is destructively rebuildable; user data restored from last cloud sync/export |
| Key compromise | Rotate secrets; certificate-pin backup pin + app update; Play upload-key reset runbook |

- `RULE` Every production release uses **staged rollout** with automated health-based halt criteria (crash-free rate, ANR rate). Never 100% a release on day one.

## 14.8 Versioning & Release Cadence

- `RULE` **Semantic-ish app versioning:** `versionName` = `MAJOR.MINOR.PATCH` (currently `0.1.0`); `versionCode` is a monotonically increasing integer (CI-derived, e.g., build number).
- Tag releases in git (`vMAJOR.MINOR.PATCH`); maintain a `CHANGELOG` generated from Conventional Commits.
- Cadence: frequent internal builds; promoted to production behind staged rollout after passing all quality + performance gates.

---

# 15. Future Roadmap

The roadmap turns FoxTrader from a native app into an extensible platform. Each item below has a design source in `reference/typescript-src/` and a target Kotlin surface. Every future capability inherits the non-negotiables: non-repainting analysis, risk-gated decisions, offline-first, and the performance budgets.

## 15.1 Plugin SDK (H4)

- A `:sdk-*` module set + runtime registries ([2.11](#211-module-split-readiness--plugin-architecture)) letting third parties add capability without touching core.
- **Sandbox contract:** plugins receive read-only, non-repainting candle access and emit descriptive outputs (series, annotations, orders); they cannot mutate app state or the DB directly.
- `SECURITY` Plugins run with least privilege; a manifest declares required capabilities; untrusted plugins are isolated ([10.11](#1011-threat-model-summary)).

## 15.2 Indicator SDK (H4)

- `interface Indicator { fun compute(candles: List<Candle>, params): IndicatorResult }` + registry; the built-in indicators ([Section 5](#5-trading-engine)) are re-expressed as first-party plugins (dogfooding the SDK).
- Outputs are viewport-cullable series so custom indicators inherit chart performance for free.

## 15.3 Drawing SDK (H4)

- `interface DrawingTool` extending the existing `DrawingEngine`/`ChartDrawing` model so custom tools (custom fibs, harmonic templates, annotations) plug into the drawing renderer + persistence + sync.

## 15.4 Scripting Engine (H4)

- A safe, sandboxed scripting runtime (design source `reference/customization/`) for user-authored strategies/alerts/scans in a constrained DSL.
- `SECURITY`/`RULE` The script sandbox has no filesystem/network/reflection access, enforced CPU/time quotas, and read-only candle access — a rogue script can never repaint, exfiltrate, or hang the UI.

## 15.5 Broker SDK (H5)

- `interface BrokerAdapter` (design source `reference/brokers/` — Alpaca/Binance/Bybit adapters, `broker-registry.ts`) for live order execution behind the risk gate.
- `RULE` Every order routes through `RiskEngine.canOpenTrade` + `OrderManager`; a broker adapter can never bypass risk gating. Biometric + Play Integrity required for live order placement ([Section 10](#10-security)).

## 15.6 Marketplace (H5)

- A curated store for indicators, drawing tools, strategies, and scripts, with review/signing, ratings, and revenue share.
- `SECURITY` All marketplace artifacts are signed, scanned, and sandboxed; server-side entitlement enforcement ([10.3](#103-authorization)).

## 15.7 Desktop Synchronization (H5)

- Sync journal/drawings/settings/watchlists across devices via the H3 cloud sync + conflict resolution ([2.10](#210-synchronization--conflict-resolution-strategy)); a desktop client (or web) shares the backend and the reference engine behavior.

## 15.8 Wear OS Support (H5)

- Glanceable alerts, watchlist tiles, and confirmation of triggered setups on the wrist; heavy analysis stays on phone/backend, watch shows results + notifications.

## 15.9 Android Auto Support (H5)

- Voice-first, glanceable, safety-constrained: high-priority alert readouts and watchlist status only. `RULE` No chart interaction or trading actions while driving — Android Auto surface is read-only/notification-only per platform driver-distraction rules.

## 15.10 AI Evolution (H5+)

- On-device narration models (privacy), chart-screenshot understanding, portfolio-level multi-symbol reasoning, and adaptive agent weighting from the learning engine ([7.10](#710-learning-engine)).
- `RULE` The deterministic decision core remains authoritative forever; AI expands explanation and personalization, never unilateral trade authority. Risk always wins.

## 15.11 Backend Platform (H3, enabling many of the above)

- FastAPI + PostgreSQL/TimescaleDB (tick/candle time-series) + Redis (cache/pub-sub); WebSocket fan-out for live feeds (design source `reference/api/`, `reference/database/`). Enables sync, auth, marketplace, and desktop.

---

# Appendix A: Environment Setup & Prerequisites

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 17 (Temurin recommended) | Kotlin/Gradle compilation |
| Android SDK | API 34 (compileSdk) | Android platform APIs |
| Android Studio | Hedgehog (2023.1)+ | IDE with Compose preview |
| Git | 2.30+ | Version control |
| Device/Emulator | API 29+ (Android 10) | Running/testing |

Optional: Compose Layout Inspector; `scrcpy` for device mirroring; a physical mid-range device for performance testing.

### Setup

```bash
# 1. Clone
git clone https://github.com/hamahasan441-png/Foxtrader.git
cd Foxtrader

# 2. Verify the committed Gradle wrapper (no separate Gradle install needed)
./gradlew --version          # Gradle 8.9

# 3. Accept Android SDK licenses
yes | sdkmanager --licenses

# 4. Open in Android Studio: File > Open > Foxtrader/ (wait for Gradle sync, 3-5 min first time)

# 5. Build & run
./gradlew :app:installDebug
adb shell am start -n com.foxtrader.app.debug/com.foxtrader.app.MainActivity
```

### Get the app without building (CI artifact)

1. Repo **Actions** tab → latest green **Android CI - Build APK** run.
2. Download the **`foxtrader-debug-apk`** artifact.
3. Install the `.apk` on any Android 10+ device.

---

# Appendix B: Dependency Version Catalog

All versions are the single source of truth in `gradle/libs.versions.toml`. `RULE` Never inline a version in a `build.gradle.kts`; add it here and reference `libs.*`.

**Build & language**

| Item | Version |
|------|---------|
| Android Gradle Plugin (AGP) | 8.5.2 |
| Kotlin | 2.0.20 |
| KSP | 2.0.20-1.0.25 |
| Compose Compiler plugin | via `kotlin-compose` (Kotlin 2.0.20) |
| Gradle (wrapper) | 8.9 |
| compileSdk / targetSdk | 34 |
| minSdk | 29 |
| JVM target | 17 |

**Libraries**

| Group | Version |
|-------|---------|
| AndroidX Core KTX | 1.13.1 |
| Lifecycle (runtime/viewmodel/runtime-compose) | 2.8.6 |
| Activity Compose | 1.9.2 |
| Compose BOM | 2024.09.03 |
| Material 3 | 1.3.0 |
| Navigation Compose | 2.8.1 |
| Hilt | 2.52 |
| Hilt Navigation Compose | 1.2.0 |
| Room | 2.6.1 |
| Retrofit | 2.11.0 |
| OkHttp (+ logging) | 4.12.0 |
| kotlinx.serialization JSON | 1.7.3 |
| Retrofit ↔ kotlinx.serialization converter | 1.0.0 |
| Coroutines (android) | 1.9.0 |
| DataStore Preferences | 1.1.1 |
| Material Icons Extended | via Compose BOM |

**Testing**

| Item | Version |
|------|---------|
| JUnit 4 | 4.13.2 |
| AndroidX JUnit ext | 1.2.1 |
| Espresso | 3.6.1 |
| MockK | 1.13.12 |
| Turbine | 1.1.0 |
| kotlinx-coroutines-test | 1.9.0 |
| Compose UI Test (ui-test-junit4) | Compose BOM |

**Gradle plugins:** `android-application` (AGP), `kotlin-android`, `kotlin-compose`, `kotlin-serialization` (all Kotlin 2.0.20), `ksp` (2.0.20-1.0.25), `hilt` (2.52).

`NOTE` DataStore Preferences is in the catalog for `AppPreferences`-backed settings persistence.

---

# Appendix C: Troubleshooting Reference

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Cold CI build fails with configuration-cache problems | Hilt/KSP + config cache (ADR-007) | Build with `--no-configuration-cache --no-daemon`; keep `org.gradle.configuration-cache=false` |
| "Unresolved reference" after adding a Hilt binding/module | KSP hasn't regenerated Dagger | `./gradlew :app:kspDebugKotlin` then rebuild |
| Chart doesn't respond to touch | A parent composable consumes gestures | Ensure `CandleChart` owns its `pointerInput`; no competing scroll parent ([4.16](#416-chart-performance-optimization-checklist)) |
| Chart pans twice as fast / drifts | Duplicate pan handler reintroduced | Use only `detectTransformGestures` for pan+zoom; delete any separate `detectDragGestures` pan ([4.6](#46-touch--gesture-engine)) |
| Chart shows synthetic-looking data | Backend `10.0.2.2:8000` unreachable → seed path ran | Start backend or pick a live provider + key; pull-to-refresh ([8.9](#89-data-flow-recap--failure-modes)) |
| Duplicate or 1970 candles | Timestamp unit mismatch in a provider mapper | Normalize all timestamps to epoch **milliseconds** in the mapper |
| Frames dropping / jank during pan | Allocation in draw loop or analysis on Main thread | Move analysis to `@DefaultDispatcher`; remove per-frame allocation; check `PerformanceProfiler` tier ([9.10](#910-optimization-checklist-whole-app)) |
| Gradle sync slow first time | Dependency download | Expected (3–5 min first sync); subsequent syncs are cached |
| Room crash after schema change | Destructive fallback dropped data / missing migration | Bump `FoxDatabase.version`; add a real `Migration` for user-data tables ([8.7](#87-versioning--migration)) |
| WebSocket keeps reconnecting | Network/endpoint issue | Check `connectionState` flow; malformed frames are dropped by design — inspect provider stream format |

---

# Appendix D: Glossary

- **BOS / CHoCH / MSS / IDM** — Break of Structure / Change of Character / Market Structure Shift / Inducement ([6.1](#61-market-structure-bos-choch-mss-idm)).
- **OB / Breaker** — Order Block / failed-and-flipped order block ([6.2](#62-order-blocks-ob-breaker-mitigation)).
- **FVG / IFVG / BPR** — Fair Value Gap / Inversion FVG / Balanced Price Range ([6.4](#64-fair-value-gaps-fvg-ifvg-imbalance-bpr)).
- **BSL / SSL** — Buy-Side / Sell-Side Liquidity ([6.3](#63-liquidity-bsl-ssl-equal-highslows-sweeps-pools)).
- **OTE** — Optimal Trade Entry (62–79% retracement) ([6.5](#65-premium--discount--ote)).
- **AMD / PO3** — Accumulation-Manipulation-Distribution / Power of Three ([6.7](#67-amd--power-of-three)).
- **SMT** — Smart Money Technique / correlated-instrument divergence ([6.8](#68-smt-smart-money-technique--divergence)).
- **SMC / ICT / LIT** — Smart Money Concepts / Inner Circle Trader / Liquidity Inducement Theory.
- **POC / VAH / VAL** — Point of Control / Value Area High / Value Area Low ([6.10](#610-volume-profile-poc--vah--val)).
- **SSOT** — Single Source of Truth (the local Room DB for market data).
- **Non-repainting** — analysis at bar `i` uses only `[0..i]`; confirmed outputs never change ([1.6](#16-institutional-quality-principles)).
- **Viewport culling** — drawing only the visible bar window ([4.4](#44-candle-rendering--viewport-culling)).
- **R-multiple** — profit/loss expressed in units of initial risk (R).
- **Kelly fraction** — fraction of the Kelly-optimal bet used (half-Kelly by default) ([5.4](#54-risk-engine)).
- **Frame budget** — max ms/frame for the target refresh rate (8.33 ms @120 Hz; 16.67 ms @60 Hz).

---

*End of the FoxTrader Engineering Bible. Amendments are made via pull request; significant architectural decisions are recorded as new ADRs in [2.4](#24-architecture-decision-records-adrs).*

---

# Appendix E: Sprint 2/3/4 Improvement Log

## Architecture Hardening (Sprint 2)

### AppPreferences — injected dispatcher
`AppPreferences` previously created an unmanaged `CoroutineScope(Dispatchers.IO)`. It now injects
`@IoDispatcher CoroutineDispatcher` via Hilt and wraps the scope in `SupervisorJob()` so that a
child failure does not cancel the entire scope. This makes the dispatcher swappable in tests.

### ChartViewModel — CPU-bound analysis off main thread
`processCandles()` is now a `suspend fun` that wraps all indicator and SMC computations in
`withContext(defaultDispatcher)`. Previously every Flow emission ran the full analysis pipeline
synchronously on the coroutine calling context. The `@DefaultDispatcher` qualifier is now injected
into `ChartViewModel` alongside the existing `@IoDispatcher` used by the repository.

`observeMarket()` additionally applies `distinctUntilChangedBy { it.size }` to suppress redundant
reanalysis when the Room observer emits for the same-size candle list (e.g. after an unrelated
Room write to a different table row).

The AI orchestrator + Master Decision Engine are similarly offloaded to `defaultDispatcher` inside
`runAiDecision()`.

### RiskEngine — true thread safety
`RiskEngine` is a `@Singleton` accessed from both ViewModel coroutines and background scanner jobs.
The mutable state is now protected as follows:
- `tradeHistory` → `CopyOnWriteArrayList` (safe concurrent add + iteration)
- `tradingHalted` → `AtomicBoolean` (lock-free read/write)
- `peakBalance` / `currentBalance` → `@Volatile` with `synchronized(lock)` on compound operations

### AiAlertService — concurrent cooldown map
`recentAlerts` changed from `mutableMapOf` to `ConcurrentHashMap` so that concurrent AI decision
cycles (one per symbol×timeframe observed) cannot corrupt the cooldown bookkeeping.

### NetworkModule — hardened OkHttp defaults
All three OkHttp clients (backend, Binance, Alpha Vantage) now declare:
- `writeTimeout(30s)` — prevents hung uploads on slow connections
- `callTimeout(60s)` — hard wall-clock cap per request to prevent resource leakage
- `retryOnConnectionFailure(false)` — pushes retry logic to the repository layer where failures
  can be surfaced properly instead of being silently swallowed by OkHttp

## Chart Engine (Sprint 3)

### Pinch-zoom anchored to gesture centroid
Previously the pinch zoom was anchored to the midpoint of the visible viewport regardless of
where the user placed their fingers. The gesture centroid (provided by `detectTransformGestures`
as the first parameter) is now used: the bar-index under the centroid remains fixed during zoom.
This eliminates the "jumping viewport" issue when zooming near the left or right edge.

Additionally `visibleBars` is now clamped inside the zoom path (`coerceIn(5f, total)`) rather
than relying solely on the post-gesture `viewport.clamp()` call, which prevents a single-frame
visual artifact when `zoom` is very large.

## UI/UX (Sprint 3)

### Accessibility semantics
- Symbol chip: `clickable(onClickLabel = "Change symbol", role = Role.Button)`
- LIVE toggle: `role = Role.Switch` + `contentDescription` reflecting current state
  ("Connect live feed" / "Disconnect live feed")
- Timeframe chips: `role = Role.Tab` + `contentDescription` with `", selected"` suffix for
  the active chip, enabling TalkBack to announce state without requiring visual focus cues

## Trading Readiness (Sprint 4)

### SignalPipeline extension point
`domain/usecase/signal/SignalPipeline.kt` provides a lightweight, ordered pipeline of
`SignalProcessor` functional interfaces that transform a `DecisionResult` before it reaches
the alert/order dispatch layer. Key properties:
- Processors run left-to-right in insertion order.
- Exceptions in any processor are swallowed (result of the previous processor is retained),
  making the pipeline fault-tolerant.
- A disapproved result still passes through all processors (processors may add block reasons).
- `SignalPipeline.PASSTHROUGH` is a zero-allocation singleton for the no-processor case.

To add a custom gate (e.g. news blackout, session filter): implement `SignalProcessor`,
inject it via Hilt, and build a `SignalPipeline` with it. The extension point is documented
and ready for Hilt multibinding if multiple processors need to be composed automatically.

## AI Foundations (Sprint 4)

### MtfContextProvider graceful fallback
`getHtfContext()` is now wrapped in `runCatching { … }.getOrElse { emptyMap() }`. Each
individual timeframe fetch is also individually wrapped so that a failure for one HTF does not
cancel the fetches for the remaining HTFs. This means the AI agent system degrades gracefully
to single-timeframe context when the repository throws (e.g. corrupt DB entry) instead of
propagating an exception to the ChartViewModel.

## Testing (Sprint 4)

### RiskEngineTest (15 cases)
Full coverage of: percentage/fixed/ATR/volatility position sizing; fixed/ATR/structure/FIXED
stop-loss methods; pre-trade risk gating; manual and auto-halt (drawdown, consecutive losses,
daily loss); resume; Kelly criterion; balance tracking; reset.

### SignalPipelineTest (8 cases)
Covers: passthrough/empty pipeline identity; single and multi-processor ordering; exception
swallowing; veto (approved → disapproved); all-processors-run-even-when-disapproved contract.
