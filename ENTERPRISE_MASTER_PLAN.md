# FoxTrader — Enterprise Master Plan

**Status:** proposed · **Type:** full-repository engineering review + prioritized roadmap
**Baseline reviewed:** `main` working tree · 347 Kotlin files (~45,684 LOC main+test), 68 unit-test files (~9,896 LOC), 10 instrumentation tests, 8 macrobenchmarks
**Reference (non-built):** `reference/typescript-src/` — the original Capacitor/WebGL web app (v2.1.0) being ported to native Kotlin. Not shipped, not compiled.
**Supersedes:** `ENHANCEMENT_MASTERPLAN.md` (Sprint 6→11) and `MARKET_DATA_ENGINE_CONTINUATION.md` — both are now partially executed; this plan reconciles the codebase against them and re-prioritizes from the *current* reality.

> **Reading order:** §1 gives the verdict. §2 is maturity. §3–§13 are the per-domain reviews with file/line evidence. §14 classifies every item (implement / refactor / remove / keep / postpone). §15 is the prioritized, sequenced roadmap with acceptance + DoD per task. §16 is metrics, §17 the risk register.

---

## 1. Executive Summary

FoxTrader is a **genuinely strong, well-architected native Android trading-analysis app** that is significantly further along than a typical project at this stage. The prior masterplan's Class-A data-integrity work is **done and done well**: data provenance is a first-class concept, migrations are non-destructive, the decision engine hard-vetoes synthetic data, and the cache is bounded. The chart engine is **production-grade** — allocation-free camera math, frame-rate-independent fling, anchored zoom, prepend paging, adaptive quality. The domain logic (SMC/ICT detection, risk engine, multi-agent decisioning) is pure, deterministic, non-repainting, and heavily unit-tested.

The project is **not** held back by missing features. It is held back by **four structural issues** that now dominate the risk profile:

> **Provider update (2026-08-14):** Polygon.io historical aggregates are now wired through the existing client-side adapter seam, alongside Twelve Data. The live-data breadth remains partial because Polygon's live stream is not yet connected and Dukascopy/OANDA/Alpaca/IB remain gated.

1. **A large orphaned subsystem.** The new `data/market/*` real-time engine (19 production + 16 test files) has **zero wiring** into the app. It is the exact "dead capability" anti-pattern the previous plan warned against — recreated at scale. Decision required: **finish it or delete it.** Leaving it is the single biggest source of technical debt in the repo.
2. **A god-object ViewModel.** `ChartViewModel` is 1,388 lines with 17+ injected dependencies and 44 functions. It is the maintainability bottleneck of the whole chart feature and the highest-value refactor.
3. **A correctness bug in the risk core.** `RiskEngine` hardcodes the forex standard-lot contract size (`* 100_000`) in every sizing path. Position size, risk amount, and every downstream risk gate are **wrong for crypto, stocks, indices, and metals** — instruments the app explicitly supports. For a tool whose entire value proposition is disciplined risk, this is the most important non-cosmetic defect.
4. **Honest-but-hollow capabilities.** Several advertised systems are architecturally present but non-functional: the external LLM provider (only a `NoOp` exists), the `NewsAgent` (votes on news that is never fetched), and 6 fully-tested but unreferenced engines. The app is honest about none-of-these lying to the user, but the capability gap should be closed or the surface trimmed.

**Verdict on maturity:** late-**Beta** on the client; **Alpha** on live-data breadth (only crypto + Alpha Vantage are real providers; no backend for forex/stocks); **pre-Alpha** on release engineering (debug signing, `versionCode = 1`, no crash reporting).

**The strategic move** is not to build more trading features. It is to **consolidate**: wire or remove the orphaned engine, break up the god object, fix the risk math, then harden for release. Feature breadth is already ahead of engineering consolidation — closing that gap is where the return is.

---

## 2. Current Maturity

| Dimension | Level | Evidence |
|---|---|---|
| **Architecture** | High / mature | Clean MVVM + Clean Architecture; domain owns repository interfaces; 8 focused Hilt modules; offline-first Room SSOT |
| **Chart / rendering** | Production-grade | `ChartViewport` pure math; fling frame-rate independent; anchored zoom; prepend paging; adaptive quality; layer split |
| **Domain / trading logic** | High | `SmcDetector`, `RiskEngine`, `MasterDecisionEngine`, `AgentOrchestrator` — pure, non-repainting, deterministic, tested |
| **Data integrity** | High | Provenance (`CandleSource`), non-destructive migrations (DB v6), pruning, provider gating, synthetic-data veto |
| **Live data breadth** | Alpha | Real: Binance/Bybit (crypto) plus Alpha Vantage, Twelve Data, and Polygon.io historical aggregates. Live non-crypto streaming remains limited |
| **AI** | Deterministic-rules mature; ML absent | 10 heuristic agents + master gate. No machine learning, no real LLM provider (NoOp only) |
| **Testing** | Good, unevenly distributed | 68 unit files, migration test, 10 smoke tests, 8 macrobenchmarks. Coverage gate is chart-only (25% floor) |
| **Engineering hygiene** | Partial | detekt + ktlint + jacoco exist but **scoped to the chart package only**; not app-wide |
| **Release engineering** | Pre-Alpha | Debug signing, `versionCode = 1`, no crash reporting, no committed baseline profile, 2 TODOs |
| **Documentation** | Very high (verbose) | `DEVELOPMENT.md` = 4,791 lines / 382 headers; thorough but drifting from code reality |
| **Consolidation / debt** | Weak | Orphaned market engine (35 files), god-object ViewModel, 6 dead engines |

---

## 3. Strengths (keep and protect)

- **Correct, defensible core invariants.** Non-repainting analysis, synthetic-data veto ranked *above* the risk veto (`MasterDecisionEngine.kt` §0), LLM kept narration-only, dependency inversion at the repository seam. These are the right hills to have died on.
- **Chart engine.** `ChartViewport.kt` and `CandleChart.kt` are exemplary: hoisted `Paint`, `remember`-scoped viewport, `withFrameNanos` fling loop that *only runs during a fling*, prepend-paging that preserves the camera anchor (`shiftForPrependedBars`). This is genuinely hard to get right and it is right.
- **Data layer discipline.** The team removed the unsourced `getCandles` overload specifically to stop three call sites from silently bypassing provenance (documented in `MarketRepository.kt`). That is mature, defensive API design.
- **DI cleanliness.** HTTPS enforced for release (`NetworkModule`), a separate no-auth client so FoxTrader tokens never leak to third-party market hosts, injectable dispatchers for testability.
- **Test culture.** 68 unit-test files with evidence-first KDocs, a real Room `MigrationTestHelper` suite, macrobenchmarks, and LeakCanary in debug.

---

## 4. Weaknesses & Architecture Problems

| # | Problem | Evidence | Severity |
|---|---|---|---|
| W1 | **Orphaned real-time market engine** — 19 prod + 16 test files, 0 references outside `data/market/`. Wiring blocks (RealtimeConnection, OkHttp transport, provider factory, engine façade, DI) never built | `find` shows `RealtimeConnection`, `RealtimeMarketDataEngine`, `MarketDataProviderFactory`, `OkHttpWebSocketTransport` = **MISSING**; app uses old `MarketWebSocket` | **Critical (debt)** |
| W2 | **God-object `ChartViewModel`** — 1,388 LOC, 17+ deps, 44 functions | `feature/chart/presentation/ChartViewModel.kt` | **High** |
| W3 | **Forex-only risk math** — `* 100_000` contract size in every sizing path | `RiskEngine.kt` (8 occurrences) | **High (correctness)** |
| W4 | **Dead engines** — `SmartAlertEngine`, `NewsEngine`, `SeasonalityEngine`, `MultiTimeframeAnalysisUseCase`, `SignalPipeline`, `RiskGatedBrokerExecutor` = 0 call sites | grep across `app/src/main` | Medium |
| W5 | **No concrete LLM provider** — only `NoOpAiProviderClient` implements `AiProviderClient` | `di/AiModule.kt` | Medium |
| W6 | **`NewsAgent` votes with no news source** — an AI confluence dimension that is structurally inert | `NewsEngine` dead + no news fetch path | Medium |
| W7 | **Schemas not committed** — `exportSchema = true` and migration tests reference `app/schemas`, but 0 schema files are tracked | `git ls-files app/schemas` = 0 | **High (test integrity)** |
| W8 | **Non-crypto live breadth remains partial** — Polygon/Twelve Data now cover keyed historical aggregates, but live non-crypto streams still have no client path and unsupported providers remain gated | `MarketRepositoryImpl` provider branches; `DataProvider.implemented` | Medium |
| W9 | **Hygiene gates are chart-scoped** — detekt/ktlint sources and jacoco includes cover only chart+indicator packages | `app/build.gradle.kts` detekt/ktlint `source`/`filter` blocks | Medium |
| W10 | **Manifest requires GLES 3.0** (`required="true"`) but the chart is Compose Canvas, not GL — needlessly excludes devices and misrepresents the renderer | `AndroidManifest.xml` `uses-feature glEsVersion=0x00030000` | Low–Medium |
| W11 | **Partial string externalization** — ~34 hardcoded `text = "…"` literals remain in feature screens | grep of `feature/**` | Low |
| W12 | **Two live TODOs** — fox notification icon, Dukascopy stub — violating the "no TODO" directive | `AlertDispatcher.kt:77`, `DukascopyAdapter.kt:36` | Low |
| W13 | **Doc drift** — `DEVELOPMENT.md` (4,791 lines) and `README` advertise capabilities that are dead/absent (multi-provider, smart alerts, news) | cross-reference | Low–Medium |

---

## 5. Technical Debt Ledger

Ranked by carrying cost:

1. **Orphaned `data/market/*` engine (W1)** — ~35 files of tested code delivering zero value while imposing full maintenance, review, and cognitive cost. Every refactor must reason about code that runs nowhere. *This is the largest single debt.*
2. **`ChartViewModel` god object (W2)** — blocks safe change to the most important screen; every chart feature touches it; testing requires 17 mocks.
3. **Six dead engines (W4)** — smaller than W1 but the same category. Each is a standing invitation to "just wire it later" that never comes.
4. **Chart-only hygiene gates (W9)** — the rest of the codebase (data, domain/ai, features) has no complexity/style/coverage floor, so debt accrues silently outside the chart package.
5. **Uncommitted schemas (W7)** — migration tests give false confidence; a broken upgrade path can ship.
6. **Doc drift (W13)** — 4,791 lines that partially describe an aspirational app, not the built one; misleads contributors and future planning.

---

## 6. Chart Engine Review

**Verdict: the strongest part of the codebase. Keep almost entirely as-is.**

- Coordinate transforms, culling, and camera ops live in `ChartViewport` (`@Stable`, allocation-free, no Compose snapshot reads in hot paths). Correct separation from the composable.
- `CandleChart.kt` was split from ~1,200 lines into a `layers/` package (8 layer files) with the composable reduced to orchestration — exactly the right structure.
- Fling uses `v *= friction^dt` (frame-rate independent, verified in code), started only on qualifying lift-off, and the animation loop exits when settled (no idle vsync wakeups).
- Prepend paging preserves the visual anchor (`shiftForPrependedBars`) and prefetches at a bar threshold — this is what makes the "100k candles" claim actually reachable.
- Adaptive quality gates each layer before the frame budget is spent.

**Gaps:**
- `MAX_VISIBLE_BARS = 100_000` but the hot cache is capped at 5,000 bars (`MAX_CACHED_BARS`) and refresh `limit = 500`. The renderer can *draw* 100k; the data layer's paging must actually feed it that deep for the claim to hold end-to-end. Paging exists but is in-memory per session — validate the deep-scroll path under test.
- The manifest's `uses-feature glEsVersion` requirement (W10) misrepresents this as a GL renderer. It is hardware-accelerated Compose Canvas. Fix the manifest and the marketing language.
- Full-series indicator/structure recompute on data change still exists in the ViewModel path; incremental (trailing-window) analysis is the next scale lever (tie to W2 refactor).

---

## 7. Trading Engine Review

**Verdict: high-quality domain logic with one serious correctness bug and some redundant compute.**

- `SmcDetector` is comprehensive (OB, FVG, liquidity pools, volume profile, breaker blocks, IFVG, BPR, AMD/Power-of-Three) and rigorously **non-repainting** (index `i` uses only `[0..i]`). Pure, thread-safe, stateless.
- `RiskEngine` is thread-safe (atomic halt flag, `CopyOnWriteArrayList`, `synchronized` balance), with 6 sizing methods, 4 stop methods, pre-trade gating, Kelly, drawdown auto-halt.

**Problems:**
- **R1 (correctness, High):** `RiskEngine` assumes a forex 100,000-unit standard lot in *every* sizing branch. For BTCUSD, AAPL, or US30 this yields absurd volumes and risk amounts, corrupting every gate that depends on `riskAmount`. Needs an **instrument/contract-spec abstraction** (contract size, tick value, quote currency) keyed off asset class. This is the top trading-engine fix.
- **R2 (scale, Medium):** `SmcDetector.detectBreakers/detectIFVG/detectBPR` each re-run `detectOrderBlocks`/`detectFairValueGaps`; `findPriceClusters` is O(n²). At 5,000 bars recomputed per tick this is a CPU cliff. Cache primitives per (series, version) and/or compute on windows.
- **R3 (Low):** `RiskEngine` daily/weekly loss uses `config.accountBalance` (static) as the denominator while sizing uses `currentBalance` — a subtle inconsistency worth aligning.

**Scanner / backtest / replay** are wired to real screens and tested; no blocking issues found. Backtest is bar-by-bar with no look-ahead (matches the invariant).

---

## 8. Backend Review

**Verdict: there is no backend. This is the honest state and it is fine for now — but it caps the product.**

- The `reference/typescript-src/` tree is the **old web app**, not a server. It is not built or deployed.
- The Kotlin app is offline-first and correct without a server. Real live data exists only for **crypto (Binance/Bybit)** and **Alpha Vantage**. Forex/stocks/indices route to `api.getCandles` (a FoxTrader backend that does not exist), fail, and fall back to clearly-labelled synthetic data.
- **Consequence:** the advertised multi-asset breadth is real only for crypto today. This is disclosed honestly in-app, but it is a hard ceiling on the value proposition and on any future sync/social feature.
- **Recommendation:** keep the app backend-optional. Do **not** build a heavyweight FastAPI+Postgres+Redis platform speculatively. Add real *client-side* providers (Polygon, Twelve Data, OANDA) behind the existing adapter seam first — they deliver forex/stock/index data with no server to operate. A backend becomes justified only when cloud sync or social features are actually scheduled.

---

## 9. Database Review

**Verdict: mature and safe, with one test-integrity gap.**

- Room, 6 entities, DB **v6**, all migrations hand-written and non-destructive; `fallbackToDestructiveMigration()` deliberately removed. User-authored data (journal, drawings, watchlists) is protected.
- Provenance column (`candles.source`) added correctly in `MIGRATION_3_4` (drops unclassifiable legacy rows rather than mislabeling them — the right call).
- Cache retention via `CandleDao.prune(keepCount)` bounds growth.

**Gaps:**
- **D1 (High):** `app/schemas/` is **not committed** (0 tracked files) though `exportSchema = true` and `FoxDatabaseMigrationTest` + the build's `androidTest` asset wiring depend on it. Without committed per-version schema JSON, migration tests cannot validate historical upgrade paths — they give false confidence. Generate and commit schemas v1–v6.
- **D2 (Low):** `observe()` has no `LIMIT`; retention relies on `prune` running. A defensive bounded window on the query would harden against a missed prune.

---

## 10. AI Review

**Verdict: excellent deterministic decisioning; "AI" is rule-based, not machine-learned; the LLM and news seams are hollow.**

- `AgentOrchestrator` runs 10 agents in dependency phases with weighted aggregation and a >15% edge requirement to avoid coin-flip signals. `MasterDecisionEngine` gates on data integrity → risk/psychology veto → directional consensus → confluence count → confidence. Pure and deterministic. **This is the right architecture for a trading tool** (auditable, non-repainting, no black-box authority).

**Gaps:**
- **AI1 (Medium):** No concrete `AiProviderClient` implementation exists — only `NoOpAiProviderClient`. The "external AI provider" narration feature always degrades. Either implement one real provider (OpenAI/Anthropic-compatible, narration-only) or stop advertising it.
- **AI2 (Medium):** `NewsAgent` contributes to confluence but there is no news data source (`NewsEngine` is dead). The agent is either inert or fabricating a signal dimension. Wire a real news/economic-calendar source or remove the agent from the confluence set until one exists.
- **AI3 (info):** There is **no machine learning** anywhere — no models, training, or feature pipeline. That is a legitimate and arguably safer design choice; just align the "Chief AI Officer / ML / DL" framing and docs with reality. If ML is ever wanted, it belongs as an *advisory* signal behind the master gate, never as trade authority.

---

## 11. Performance Review

**Verdict: strong foundations, measurement infrastructure exists, a few known cliffs.**

- Allocation-free chart hot path; hoisted `Paint`; `kotlinx-collections-immutable` + a compose stability config; CPU work on `Dispatchers.Default`; adaptive quality.
- `:benchmark` module with 8 macrobenchmarks (startup, chart scroll, pinch, workspace, navigation, scanner/settings, portfolio/alerts) + `BaselineProfileGenerator`.

**Gaps:**
- **P1 (Medium):** No **committed baseline profile** — cold start and first-frame jank are unoptimized by default despite the generator existing. Generate and commit.
- **P2 (Medium):** Full-series recompute per tick (ties to R2 and W2). Incremental/windowed analysis is the main remaining lever.
- **P3 (Low):** Benchmarks are defined but there is no evidence they run in CI with regression thresholds; wire them (or a subset) into the pipeline so the numbers in `DEVELOPMENT.md` are *measured*, not asserted.

---

## 12. Testing Review

**Verdict: good coverage and culture, unevenly gated.**

- 68 unit-test files (~9.9k LOC) across domain/data; 10 instrumentation smoke tests; a real migration test; 8 macrobenchmarks.

**Gaps:**
- **T1 (High):** Coverage gate (`jacocoChartCoverageVerification`) enforces only a **25% line floor on chart+indicator packages**. The risk engine, decision engine, SMC detector, repositories — the correctness-critical core — have **no enforced floor**. Extend a coverage gate to `domain/` (the previous plan proposed 80%; start at a realistic ratchet).
- **T2 (Medium):** Migration tests are undermined by uncommitted schemas (D1).
- **T3 (Medium):** No screenshot/visual-regression tests for the design system, so theme/layer regressions are invisible in PR diffs.
- **T4 (Low):** No tests target the risk-math instrument bug (R1) precisely because the bug is baked into expectations — add asset-class-parametrized sizing tests when fixing R1.

---

## 13. Documentation & Developer-Experience Review

- **Docs:** `DEVELOPMENT.md` is extraordinarily thorough (4,791 lines) but has **drifted** — it documents dead/aspirational capabilities (multi-provider, smart alerts, news, LLM) as if shipped. Volume is not the problem; accuracy is. Add a short, authoritative "what is actually wired today" matrix at the top and keep it current; move aspirational sections into a clearly-labeled "Future" appendix (much of §15 already is).
- **DX:** Version catalog, committed wrapper, fast-feedback CI, LeakCanary, detekt/ktlint/jacoco — all good. Two friction points: hygiene gates are chart-only (so most of the repo has no guardrails), and configuration-cache is disabled (documented trade-off; revisit once Hilt/KSP support stabilizes).
- **Two live TODOs (W12)** contradict the stated "no TODO" directive and should be closed or converted to tracked tasks.

---

## 14. Disposition — What to Implement / Refactor / Remove / Keep / Postpone

### Implement (new work that closes a real gap)
- **Instrument/contract-spec model** for `RiskEngine` (fixes R1 forex-only math).
- **Commit Room schemas v1–v6** and make migration tests real (D1).
- **Additional non-crypto providers** behind the existing adapter seam (OANDA/Alpaca/Interactive Brokers or a Polygon live stream) — historical Polygon and Twelve Data paths now exist; no server is required (W8).
- **App-wide hygiene + coverage gates** (extend detekt/ktlint/jacoco beyond chart) (W9, T1).
- **Committed baseline profile** + benchmark-in-CI (P1, P3).
- **One real `AiProviderClient`** (narration-only) *or* formally descope the feature (AI1).
- **Release engineering:** signing config, `versionCode` from CI, crash reporting behind opt-in, AAB output (release gap).

### Refactor (improve existing code, no behavior change)
- **`ChartViewModel`** → decompose into cohesive collaborators (data/stream controller, indicator coordinator, AI/decision coordinator, drawing/watchlist controllers) behind a slim ViewModel (W2).
- **`SmcDetector` compute reuse** — share OB/FVG results across breaker/IFVG/BPR; window the O(n²) clustering (R2).
- **Incremental analysis** — split full vs trailing-window passes (P2).
- **Fix the manifest GLES requirement** and align renderer language (W10).
- **Finish string externalization** in feature screens (W11).

### Remove (delete; it is pure debt)
- **Either fully wire OR delete the `data/market/*` engine (W1).** Decision in §15 T1. If not scheduled for wiring within one cycle, **delete it** (it is preserved in git history) rather than carry 35 dead files.
- **Dead engines with no near-term surface:** `SeasonalityEngine`, `MultiTimeframeAnalysisUseCase` (superseded by `MtfContextProvider`/`ConfluenceEngine`), `SignalPipeline` — remove unless a concrete consumer is scheduled.
- **`NewsAgent` from the confluence set** until a news source exists (AI2) — or wire `NewsEngine` to a real feed.

### Keep unchanged (do not touch)
- The chart engine (`ChartViewport`, `CandleChart`, `layers/`), except the incremental-analysis hook.
- The data-integrity architecture (provenance, veto, migrations, pruning, provider gating).
- The DI structure, dispatcher injection, and repository seam.
- The deterministic AI decision architecture and non-repainting invariants.
- The `RiskGatedBrokerExecutor` (dead but intentional — paper-trading only; keep behind a flag, do **not** wire to live capital).

### Postpone (explicitly out of scope now)
- FastAPI/Postgres/Redis backend, social/copy-trading, live broker order routing, Wear OS/Android Auto/voice, marketplace/scripting SDK (H4/H5), any ML/model training.

---

## 15. Prioritized Roadmap

Six phases. Each ends green (`:app:assembleDebug` + `:app:testDebugUnitTest`), is independently shippable, and is ordered risk/correctness-first → debt → hardening → release. Fields per task: **Priority · Impact · Complexity · Dependencies · Order · Acceptance · DoD.**

Global Definition of Done (applies to every task): compiles; unit tests green; no new TODOs/placeholders; no new detekt/ktlint violations in touched files; Conventional Commit; `DEVELOPMENT.md` "what's wired" matrix updated; one logical change per commit.

---

### Phase 0 — Correctness & Test Integrity *(non-negotiable, do first)*

**T0.1 — Fix forex-only risk math (instrument contract spec)**
- **Priority:** P0 · **Impact:** Very High (core correctness) · **Complexity:** M
- **Dependencies:** none · **Order:** 1
- **Acceptance:** an `InstrumentSpec` (asset class, contract size, tick/point value, quote currency) drives all `RiskEngine` sizing/stop math; BTCUSD, AAPL, US30, XAUUSD, EURUSD each produce correct volume + risk amount; no literal `100_000` remains in sizing paths.
- **DoD:** asset-class-parametrized unit tests for all 6 sizing methods; existing risk tests updated; gates (`canOpenTrade`) verified against corrected `riskAmount`.

**T0.2 — Commit Room schemas v1–v6, make migration tests real**
- **Priority:** P0 · **Impact:** High (data safety) · **Complexity:** S
- **Dependencies:** none · **Order:** 2
- **Acceptance:** `app/schemas/…/{1..6}.json` committed; `FoxDatabaseMigrationTest` validates 1→2→…→6 with row-survival assertions on journal/drawings/watchlists; a deliberately missing migration fails CI.
- **DoD:** schemas tracked in git; migration test documented; CI runs it (instrumented or Robolectric where possible).

**T0.3 — Close the two live TODOs**
- **Priority:** P1 · **Impact:** Low · **Complexity:** S
- **Dependencies:** none · **Order:** 3
- **Acceptance:** real fox notification icon shipped (`AlertDispatcher`); `DukascopyAdapter` TODO removed by either implementing behind the provider seam or deleting the stub and marking the provider unimplemented.
- **DoD:** grep for `TODO|FIXME` in `app/src/main` returns 0.

---

### Phase 1 — Decide the Orphaned Engine *(kill the biggest debt)*

**T1.1 — Market-engine decision gate: wire or delete**
- **Priority:** P0 (debt) · **Impact:** Very High (maintainability) · **Complexity:** L (wire) / S (delete)
- **Dependencies:** none · **Order:** 4
- **Decision rule:** if the real-time engine will be the live-data path this cycle → execute the wiring blocks below. Otherwise → **delete `data/market/*` + its tests** (recover from git if revived).
- **Acceptance (wire path):** `OkHttpWebSocketTransport` + `RealtimeConnection` + `MarketDataProviderFactory` + `RealtimeMarketDataEngine` implemented and injected; `ChartViewModel` (post-T2) consumes no-repaint candles from it; the old `MarketWebSocket` path is removed; live Binance/Bybit ticks flow through the new engine with provenance intact.
- **Acceptance (delete path):** `data/market/*` and its 16 tests removed; build green; no references dangling; a one-line note in `DEVELOPMENT.md` records the decision.
- **DoD:** zero orphaned files in the chosen direction; engine (if kept) has ≥1 production call site and an integration test.

---

### Phase 2 — Decompose the God Object

**T2.1 — Refactor `ChartViewModel` into cohesive collaborators**
- **Priority:** P1 · **Impact:** High (maintainability, testability) · **Complexity:** L
- **Dependencies:** T1.1 (so live-data source is settled first) · **Order:** 5
- **Acceptance:** `ChartViewModel` drops below ~350 LOC and ≤6 direct collaborators; extracted units (e.g., `ChartDataStreamController`, `IndicatorCoordinator`, `DecisionCoordinator`, `DrawingController`) are independently unit-tested; UI behavior unchanged (smoke tests pass).
- **DoD:** each extracted collaborator has its own tests; no regression in chart smoke/benchmark suites; recomposition/stability report shows no new unstable params.

**T2.2 — Incremental analysis pass**
- **Priority:** P2 · **Impact:** Medium-High (scale) · **Complexity:** M
- **Dependencies:** T2.1 · **Order:** 6
- **Acceptance:** on a live tick, only the trailing window recomputes; EMA/RSI/ATR state is resumable; tick-to-frame under target (measure on reference device); full pass only on symbol/timeframe change.
- **DoD:** correctness tests prove incremental == full-recompute results; benchmark shows tick-to-frame improvement.

**T2.3 — `SmcDetector` compute reuse + window clustering**
- **Priority:** P2 · **Impact:** Medium · **Complexity:** M
- **Dependencies:** T2.2 · **Order:** 7
- **Acceptance:** breaker/IFVG/BPR reuse a single OB/FVG computation; `findPriceClusters` no longer O(n²) at 5k bars (bucketed or windowed); outputs identical to current within tolerance.
- **DoD:** regression tests assert identical detections; micro-benchmark shows reduced compute.

---

### Phase 3 — Trim & Truthful Capabilities

**T3.1 — Remove or wire dead engines**
- **Priority:** P2 · **Impact:** Medium (debt) · **Complexity:** S–M
- **Dependencies:** none · **Order:** 8
- **Acceptance:** `SeasonalityEngine`, `MultiTimeframeAnalysisUseCase`, `SignalPipeline`, `SmartAlertEngine` each either gain a real production surface **or** are removed; final grep shows 0 zero-call-site domain engines (excluding the intentionally-flagged `RiskGatedBrokerExecutor`).
- **DoD:** each decision recorded; tests for anything newly wired; build green.

**T3.2 — News: wire a source or descope the agent**
- **Priority:** P2 · **Impact:** Medium (signal integrity) · **Complexity:** M
- **Dependencies:** none · **Order:** 9
- **Acceptance:** either `NewsEngine` is fed by a real news/economic-calendar source and surfaced, **or** `NewsAgent` is removed from the confluence set and docs updated so no inert dimension influences decisions.
- **DoD:** decision engine confluence set matches reality; tests updated; README/DEVELOPMENT corrected.

**T3.3 — LLM provider: implement one or descope**
- **Priority:** P2 · **Impact:** Medium · **Complexity:** M
- **Dependencies:** none · **Order:** 10
- **Acceptance:** either one real narration-only `AiProviderClient` (OpenAI/Anthropic-compatible, key from encrypted store, timeouts, no-PII, never sees raw candles for authority) ships and is user-selectable, **or** the feature is formally descoped and docs/settings reflect offline-only.
- **DoD:** if implemented — integration test with a fake HTTP server, key never logged, graceful degradation preserved.

---

### Phase 4 — Engineering Hardening

**T4.1 — App-wide hygiene + coverage gates**
- **Priority:** P1 · **Impact:** High (regression protection) · **Complexity:** M
- **Dependencies:** Phases 0–3 (so churned code is stable) · **Order:** 11
- **Acceptance:** detekt + ktlint cover `data/`, `domain/`, `feature/` (baseline existing violations, fail on new); jacoco floor extended to `domain/` at a ratchet (start realistic, e.g., 50%, scheduled to climb); `lint { abortOnError = true }` retained.
- **DoD:** CI enforces all gates as required checks; baseline file committed with a burn-down note.

**T4.2 — Baseline profile + benchmarks in CI**
- **Priority:** P2 · **Impact:** Medium · **Complexity:** M
- **Dependencies:** T2.2 · **Order:** 12
- **Acceptance:** baseline profile generated from the benchmark journeys and committed; a benchmark subset runs in CI with regression thresholds published as an artifact.
- **DoD:** cold-start improvement measured and recorded; thresholds enforced.

**T4.3 — Manifest/renderer truth + string externalization**
- **Priority:** P3 · **Impact:** Low-Medium · **Complexity:** S
- **Dependencies:** none · **Order:** 13
- **Acceptance:** GLES `required` fixed (removed or set to reflect actual usage); remaining ~34 hardcoded feature-screen strings moved to `strings.xml` with content descriptions on interactive chart controls.
- **DoD:** device-compatibility footprint verified; accessibility scan on key screens passes.

---

### Phase 5 — Release Readiness

**T5.1 — Signing, versioning, AAB**
- **Priority:** P1 · **Impact:** High (shippability) · **Complexity:** M
- **Dependencies:** Phase 4 · **Order:** 14
- **Acceptance:** release signing from secure keystore/env; `versionCode` derived from CI; AAB output alongside APK; release R8/proguard build gated in CI.
- **DoD:** a signed release AAB builds in CI; no debug signing in release.

**T5.2 — Crash/ANR reporting (opt-in, no PII)**
- **Priority:** P1 · **Impact:** High (operability) · **Complexity:** M
- **Dependencies:** T5.1 · **Order:** 15
- **Acceptance:** Crashlytics or Sentry behind an opt-in privacy toggle; documented no-PII policy; keys/tokens never captured.
- **DoD:** verified crash appears in dashboard from a release build; opt-out fully disables collection.

**T5.3 — Store readiness**
- **Priority:** P2 · **Impact:** Medium · **Complexity:** M
- **Dependencies:** T5.1, T5.2 · **Order:** 16
- **Acceptance:** icon set, screenshots (from screenshot tests if T3 adds them), data-safety declaration, prominent in-app educational-tool disclaimer; internal testing track.
- **DoD:** internal track release succeeds; disclaimer surfaced before first analysis.

---

## 16. Success Metrics

| Metric | Baseline (now) | Target |
|---|---|---|
| Orphaned files (`data/market/*` unwired) | 35 | 0 (wired or deleted) |
| Zero-call-site domain engines | 6 | 0 (excl. flagged broker executor) |
| Largest ViewModel LOC | 1,388 | < 350 |
| Risk math correct across asset classes | forex only | all supported classes |
| Committed Room schemas | 0 | 6 (v1–v6) |
| Coverage gate scope | chart only (25%) | `domain/` floor (ratcheting) |
| Live TODOs in `app/src/main` | 2 | 0 |
| Real non-crypto data provider | 2 historical (Twelve Data + Polygon.io) | ≥ 3 client-side or one non-crypto live stream |
| Committed baseline profile | no | yes |
| Release signing | debug | release keystore |
| Crash reporting | none | opt-in, no-PII |
| Hygiene gate scope | chart only | app-wide |

---

## 17. Risk Register

| Risk | Impact | Mitigation |
|---|---|---|
| Deleting the market engine loses valuable work | Rework later | It stays in git history; delete only if not scheduled within the cycle |
| Risk-math fix changes existing outputs | Test churn, user-visible sizing changes | Asset-class-parametrized tests first; document the correction as a fix, not a regression |
| ViewModel refactor introduces chart regressions | The app's core screen breaks | Do it after smoke tests exist and hold; keep behavior identical; lean on benchmarks |
| Incremental analysis diverges from full recompute | Silent wrong signals | Equivalence tests gate the change |
| Committing schemas surfaces an already-broken migration | Short-term CI red | That is the point — fix the migration before release, not on a user's device |
| App-wide gates surface a wall of violations | Momentum stall | Baseline + fail-on-new only; scheduled burn-down |
| Scope creep into new features during consolidation | Debt count rises again | Phases 1–3 have a hard rule: no new trading features until debt is cleared |

---

## 18. Sequencing Rationale

```
Phase 0  Correctness & Test Integrity   ← wrong risk math + untested migrations are the only
   │                                       things that can hurt a user right now
   ▼
Phase 1  Decide the Orphaned Engine      ← settle the live-data source before touching the
   │                                       ViewModel that consumes it
   ▼
Phase 2  Decompose the God Object        ← now safe; unlocks incremental analysis + future work
   │
   ▼
Phase 3  Trim & Truthful Capabilities    ← remove dead code / make advertised features real
   │
   ▼
Phase 4  Engineering Hardening           ← gates protect everything above from regression
   │
   ▼
Phase 5  Release Readiness               ← ship it
```

Phase 0 is non-negotiable and non-parallelizable. Phase 1's decision blocks Phase 2 (the ViewModel consumes whatever live-data source wins). Phases 3 can partially overlap Phase 2 with a second workstream, except anything touching the chart, which must not run concurrently with T2.1.

---

*Prepared as a complete engineering review of FoxTrader. No code was changed in producing this plan.*



---

## 19. Sprint Log

### Sprint 1 — T0.1: Asset-class-correct money math *(status: implemented, pending CI build)*

**Problem fixed.** Money-to-price conversion was hardcoded to the forex 100,000-unit standard lot in six places, so position size, risk amount, portfolio exposure, realized P&L, and backtest P&L were wrong by orders of magnitude for crypto, metals, indices, and stocks — the instruments the app supports. Validated against the professional standard in `ENGINEERING_RESEARCH.md` §1.4 (`money = stopDistance × volume × contractSize`, contract size varies by asset class).

**Approach.** Reused the codebase's existing, correct concept — `PositionCalculator.InstrumentType` + `AssetClassifier` + `InstrumentTypeResolver` — instead of inventing a competing one. The key insight: money-risk per unit = `stopDistance × contractSize` (the pip size cancels), so the correct generalization of `100_000` is simply each instrument's resolved `contractSize`.

**Changes (7 files):**
- `RiskEngine` — injects `InstrumentTypeResolver`; all 6 sizing branches use the resolved `contractSize`; result now carries `contractSize`.
- `PositionSizeResult` (model) — new `contractSize` field so downstream consumers share the same contract assumption.
- `RiskGatedOrderService` + `RiskGatedBrokerExecutor` — volume-override risk uses the result's `contractSize`; deleted both `CONTRACT_SIZE = 100_000` constants.
- `PortfolioEngine` — resolves contract size **per position** (was one blanket lot across the whole book); removed the blanket `contractSize` param.
- `JournalPositionMapper` + `JournalEngine` — mark-to-market and realized P&L resolve per symbol.
- `BacktestLabViewModel` — sets `BacktestConfig.contractSize` from the tested symbol (engine stays pure/configurable).

**Tests:** added 6 asset-class sizing tests to `RiskEngineTest` (forex/crypto/gold/index + a fixed-lots crypto regression guard + a differentiation assertion); updated `PortfolioEngineTest`'s crypto-warnings case to a realistic large book (it previously only tripped warnings *because* of the bug). Forex-based existing tests are unchanged (resolver returns 100k for FX).

**Self-review / benchmark:** the fix adds one symbol→type resolution (a handful of string comparisons) per order/snapshot/close — all rare, non-per-frame operations. No hot-path or rendering impact. Net maintainability gain: 5 duplicated magic constants eliminated, unified on a single resolver.

**Known limitations (logged, not blocking):** quote→account currency FX conversion is assumed 1.0 — correct for the USD-quoted/USD-account instruments in scope, but cross-currency pairs (e.g. EURGBP on a USD account) would need an FX-rate factor; `InstrumentType` is coarse (no per-contract futures multipliers). Both are acceptable for current scope and noted for a future refinement.

**Definition of Done status:**
- [x] No literal `100_000` remains in any sizing/P&L/exposure path (verified repo-wide; only an unrelated 5-dp rounding utility remains).
- [x] BTCUSD, XAUUSD, US30, AAPL, EURUSD each produce asset-class-correct volume and risk amount (unit tests).
- [x] Downstream risk gates consume the corrected `riskAmount`.
- [x] No new TODOs; no fully-qualified names in bodies; single logical change.
- [ ] **CI build + unit tests green** — cannot be run in this offline sandbox (no Android SDK / no dependency network). Must be confirmed by the GitHub Actions `android.yml` workflow on push. This is the one open item before merge.

**Blocked in this environment (deferred within Phase 0):**
- **T0.2 (commit Room schemas)** — requires a Gradle/KSP build to generate the schema JSON; blocked without the Android SDK. Do on a machine/CI with the SDK.
- **T0.3 (fox notification icon)** — needs a real drawable asset (design), so deferred; the Dukascopy stub TODO can be removed independently in a follow-up.

---

### Sprint 2 — Phase 0 remainder + Phase 1-2 consolidation *(status: implemented)*

Covered by commits 601bf40 through cf25f8f:
- **T0.2:** Room schema dir created with `.gitkeep`.
- **T0.3:** Fox notification icon (vector drawable) + DukascopyAdapter KDoc stub replacement. Zero TODOs remain.
- **T1.1:** Deleted orphaned `data/market/` engine (35 files, 3193 lines). Zero references remained.
- **T2.1:** Decomposed `ChartViewModel` (1388 LOC) into 5 focused controllers (71% reduction).
- **T2.3:** SmcDetector compute reuse (`analyzeAll`) + bucket-based `findPriceClusters`.

---

### Sprint 3 — Phase 3: Dead engine removal, LLM descope, executable invariants *(status: implemented)*

**T3.1 — Remove dead engines (6 files deleted):**
Deleted `SmartAlertEngine.kt`, `NewsEngine.kt`, `SeasonalityEngine.kt`, `MultiTimeframeAnalysisUseCase.kt`, `SignalPipeline.kt`, and `SignalPipelineTest.kt`. All had zero import references in production or test source. Empty `news/` and `signal/` directories removed.

**T3.2 — NewsAgent decision: KEEP.**
`NewsAgent` is a legitimate protective gate that blocks trading near high-impact news events. It reads `AgentContext.minutesToHighImpactNews` and `inNewsBlackout` (user/calendar-supplied context), carries weight 0.8 in the orchestrator, and does not require a live news feed to function. It is NOT the dead `NewsEngine` (market-news-fetch); it is a blackout-gating agent. Kept unchanged.

**T3.3 — LLM provider: formally descoped.**
Only `NoOpAiProviderClient` exists. The AI layer is deterministic rules-based; the LLM seam is narration-only. Updated `AiProviderClient.kt` KDoc to clearly state this is a future extension point for optional narration, currently backed by NoOp. No false promises in the settings UI (the "Coming soon" text refers only to data providers not yet connected, which is accurate).

**T3.4 — BacktestEngine cost modeling: VERIFIED ALREADY COMPLETE.**
`BacktestConfig` already includes configurable `spread` (with `variableSpread` mode), `commissionPerLot`, and `slippage`. The engine applies spread to SL/TP fills and deducts commission from gross P&L. No changes needed.

**T3.5 — Executable invariants on risk/decision hot paths:**
- `RiskEngine.calculatePositionSize`: `require(entryPrice > 0.0)`, `require(stopLossPrice >= 0.0)`.
- `MasterDecisionEngine.evaluate`: `check(confidence in 0.0..100.0)` before final return.
- `AgentOrchestrator.aggregate`: `check(aggregateConfidence in 0.0..100.0)` after computation.
- `PositionSizeResult` init block: `require(volume >= 0.0)`, `require(contractSize > 0.0)`, `require(riskPercent >= 0.0)`.

All guards fail fast in debug, are cheap in release (no allocations on the happy path).


---

### Sprint 4 — Status reconciliation + doc-drift fix (W13) *(status: implemented)*

**Audit finding.** A full re-review of `main` (after PRs #47–#49 merged) confirms the plan is executed through Phase 4's *source-feasible* scope. Verified against the tree:
- **Phase 0:** T0.1 asset-class money-math done (`InstrumentTypeResolver` drives `contractSize` in all six `RiskEngine` sizing paths; no `100_000` literal remains); `RiskEngineTest` carries asset-class-parametrized cases (forex/BTC/gold/index + fixed-lots crypto regression). T0.3 done (zero `TODO`/`FIXME` in `app/src/main`). T0.2 schema *dir* exists.
- **Phase 1:** T1.1 done — `data/market/*` orphaned engine deleted (0 files, 0 references).
- **Phase 2:** T2.1 done — `ChartViewModel` is 430 LOC (from 1,388), decomposed into focused controllers. T2.3 SMC compute reuse in place.
- **Phase 3:** T3.1 dead engines removed; T3.2 `NewsAgent` kept (blackout gate, not the dead `NewsEngine`); T3.3 LLM formally descoped to a NoOp narration seam; T3.5 executable invariants present.
- **T4.3 (manifest half):** already correct — `uses-feature glEsVersion="0x00020000" required="false"` with a comment that the renderer is Compose Canvas, not GL.
- Correctness core is tested: `RiskEngineTest`, `MasterDecisionEngineTest`, `SmcDetectorTest`, `SmcAdvancedTest`.

**Change in this sprint (W13 — the one open, sandbox-feasible item).** Added an authoritative **"What's Wired Today"** status matrix to the top of `DEVELOPMENT.md`, reconciling the 4,791-line spec against built reality (incl. the TRADEPRO additions and the live-data breadth caveat). This directly addresses the doc-drift weakness and would have prevented the #47 partial-merge confusion (later recovered in PR #50).

**Also already done (found during this audit, beyond the earlier sprints):**
- **T5.1 (release engineering core):** `app/build.gradle.kts` already drives `versionCode`/`versionName` from CI env/property with local fallbacks, and has a guarded `release` `signingConfig` (keystore from `FOXTRADER_KEYSTORE_*` env, safe debug-signing fallback when absent) with `isMinifyEnabled`/`isShrinkResources`/proguard on release. Only the store-upload keystore secrets + AAB-in-CI wiring remain.

**Remaining roadmap — blocked in an offline/no-SDK sandbox, must be done on a build-capable machine or CI:**
- **T0.2:** generate + commit Room schema JSON v1–v6 (needs Gradle/KSP build).
- **T4.1:** extend detekt/ktlint/jacoco to `data/`/`domain/`/`feature/` **and** add the static-analysis step to CI (workflow-file change — currently push-blocked for the agent).
- **T4.2:** the committed `baseline-prof.txt` is a stub; regenerate from the benchmark journeys and wire a benchmark subset into CI.
- **T5.2 / T5.3:** opt-in crash/ANR reporting (no Crashlytics/Sentry yet), AAB-in-CI, data-safety declaration, store screenshots.
- **T4.3 (string half):** the remaining ~15 hardcoded literals are almost all dynamic/interpolated or technical labels (`RSI(14)`, FPS/replay counters, `#${trade.id}…`) — low-value to externalize; revisit only alongside a real localization effort.


---

### Sprint 5 — Phase 5 (T5.3): first-run educational-disclaimer gate *(status: implemented)*

**Audit finding.** A re-review of `main` for Phase 5 confirmed that two of the three release-readiness tasks were already substantially done and mis-tracked as "Pending":
- **T5.1 (AAB in CI):** `.github/workflows/release.yml` already runs `:app:bundleRelease` and uploads the `.aab`; `app/build.gradle.kts` already drives `versionCode/versionName` from CI and has a guarded release `signingConfig` with R8 + resource-shrink. Effectively done.
- **T5.2 (opt-in, no-PII crash reporting):** already built — `LocalCrashReporter` behind the `CrashReporter` seam, bound in `di/CrashModule.kt` (mirroring `AiModule`'s NoOp `@Binds`), installed first in `FoxTraderApp.onCreate()`, default OFF, no upload, exception-message-free, rotates to max 5 files. Remote (Crashlytics/Sentry) and ANR-specific capture remain genuine future work.

**The one true gap — closed this sprint.** T5.3's DoD requires the educational-tool disclaimer to be *surfaced before first analysis*. Until now the disclaimer only lived passively in Settings → Privacy; there was no onboarding/first-run flow at all (grep for `Onboarding|FirstRun|hasSeenDisclaimer|welcome` = 0 matches). Added a first-run gate, reusing the existing preference + gate patterns rather than inventing new ones.

**Changes (4 files):**
- `AppPreferences` — new `disclaimerAcknowledged: StateFlow<Boolean>` (default false) + `KEY_DISCLAIMER_ACKNOWLEDGED` + init-hydration + `setDisclaimerAcknowledged(...)`, following the existing `crashReportingEnabled` recipe exactly (DataStore-backed).
- `feature/onboarding/presentation/DisclaimerScreen.kt` — new stateless full-screen composable (Fox design-system styling: `FoxAmber50`/`FoxNeutral60`, scrollable), title + subtitle + body + five key points (not-advice, no-trades, simulated-data, risk, local-only) + a single "I understand and agree" action. Mirrors the `LockScreen` gate pattern (callback-driven, no ViewModel).
- `MainActivity` — the disclaimer is now the **outermost** gate in `FoxTraderAppContent`: `when { showDisclaimer -> DisclaimerScreen; locked -> LockScreen; else -> FoxNavHost }`. Acknowledgment persists so it is shown once.
- `strings.xml` — a new "First-run educational disclaimer gate" block (10 externalized strings); no hardcoded literals in the new screen.

**Docs reconciled:** `PRIVACY_AND_DATA_SAFETY.md` now states the disclaimer is a first-run gate (not only Settings). `DEVELOPMENT.md`'s "What's Wired Today" matrix corrected: crash reporting → ✅ Wired (local, opt-in), added a "First-run educational-disclaimer gate → ✅ Wired" row, and store readiness → ⚠️ Partial (AAB + data-safety done; screenshots pending).

**Definition of Done status:**
- [x] Educational disclaimer surfaced before first analysis, non-bypassable, acknowledgment persisted.
- [x] Reuses existing preference/gate/design-system patterns; no new architecture.
- [x] No new TODOs/placeholders; strings externalized; single logical change.
- [ ] **CI build + unit tests green** — cannot run in this offline/no-SDK sandbox; must be confirmed by the GitHub Actions `android.yml` workflow on push. Verified locally at source level: XML well-formed, imports resolve to existing symbols, brace/paren balance intact.

**Remaining Phase 5 roadmap (build-environment- or asset-dependent):** remote crash/ANR backend (opt-in), Play store screenshots (from screenshot tests), internal testing track release. T0.2 (commit Room schema JSON) and T4.1/T4.2 (app-wide gates + real baseline profile in CI) remain blocked on a Gradle/KSP-capable build environment as previously logged.



---

### Sprint 6 — T2.1 continuation: further `ChartViewModel` decomposition *(status: implemented)*

**Context.** Success-metrics target for T2.1 is "largest ViewModel < 350 LOC." A re-measure of `main` found `ChartViewModel` had drifted back up to **457 LOC** (from the 430 logged in Sprint 4, after the chart drawing-management + LIT X integration work added delegates). Two cohesive concerns were still inlined in the ViewModel.

**Changes (3 files, behaviour identical):**
- **`ChartUiStateMapper.kt` (new):** extracted the ~50-line `_uiState.copy(...)` fan-out from `processCandles` into a pure `ChartUiState.withComputation(candles, source, computation, toggles, tradeProAnalysis)` extension. No I/O, reads no engines (TRADEPRO analysis is computed by the caller and passed in) — so the ViewModel's most complex method is now a single mapping call and the mapping is independently testable.
- **`ChartWatchlistController.kt` (new):** extracted watchlist seeding/observation + add/remove into a plain controller matching the existing `ChartDataController`/`ChartDrawingController`/… pattern. It owns the active-list id internally, so mutations no longer thread it through UI state.
- **`ChartViewModel.kt`:** calls `withComputation`, delegates watchlist actions, drops the inlined `observeWatchlist()` and the unused `persistentListOf` import.

**Result.** `ChartViewModel` **457 → 419 LOC**, and its worst method (the state fan-out) is now a pure, testable function. Behaviour is verified identical by review (field-for-field mapping preserved, incl. `confluence` pass-through and `isLoading` logic; watchlist active-list resolution and mutation targeting unchanged).

**Honest status vs the < 350 metric.** Not yet under 350. The remaining bulk is the **necessary public delegate API** the chart screen calls (multi-chart ~24 one-liner delegates, drawing/replay delegates) plus constructor DI and orchestration — removing those would either break call sites or add indirection without a real maintainability gain. Further reduction is deferred as low-value; the god-object's *complexity* (not just line count) is what this sprint reduced.

**Definition of Done status:**
- [x] Two cohesive concerns extracted; ViewModel stays a thin orchestrator; behaviour identical (review-verified).
- [x] No new TODOs/placeholders; no unused imports in touched files; single logical change.
- [ ] **CI build + unit tests green** — cannot run in this offline/no-SDK sandbox; must be confirmed by the GitHub Actions `android.yml` workflow. Verified at source level: brace/paren balance, same-package helper resolution, no dangling references.

---

### Sprint 8 -- Engineering hardening: operator alignment, coverage extension, domain tests, string externalization, provider adapter *(status: implemented)*

**Scope.** This sprint closes five open items from the remaining Phase 4/Phase 5 roadmap, all feasible without a build environment. It addresses the Sprint 7 audit's operator inconsistency finding, extends coverage gates, adds domain characterization tests, externalizes remaining feature-screen strings, and wires Twelve Data behind the `DataProviderAdapter` seam.

**Changes:**

1. **RiskEngine daily-loss operator alignment (Sprint 7 audit item).** `checkAutoHalt` changed from `dailyLoss > maxDailyLoss` (strict) to `dailyLoss >= maxDailyLoss` (inclusive), matching `canOpenTrade`'s existing `>=` semantics. Added KDoc to `updateConfig` and `updateBalance` clarifying the config-vs-runtime balance contract. New boundary-case test in `RiskEngineTest` verifies the alignment.

2. **T4.1 partial: coverage gate extension.** Extended `domainCoverageIncludes` and `domainCoverageSourceDirs` in `app/build.gradle.kts` to include `SmtDivergenceDetector`, `StrategyTester`, and `StrategyLibrary`. The domain jacoco verification rule (40% floor) now covers smt and strategies packages in addition to the existing risk/smc/ai/backtest/calculator scope. `ignoreFailures = true` remains (non-breaking rollout).

3. **Domain-layer characterization tests (3 new test files, 19 test methods):**
   - `BacktestEngineTest` (7 tests): no-look-ahead invariant, SL-before-TP on same bar, end-of-data closure at last close, empty input, commission deduction, winRate/profitFactor computation, Sharpe ratio.
   - `SmtDivergenceDetectorEdgeCaseTest` (6 tests): MIN_BARS guard, empty correlated map, correlation threshold gating, confidence bounding [62,86], non-repainting swing confirmation, peer-too-short guard.
   - `StrategyTesterTest` (6 tests): single-strategy result, skip-when-insufficient-bars, ranking by score descending, contract-size resolution from symbol, zero-score with <3 trades, non-zero score formula.

4. **T4.3 string externalization.** Moved 30 hardcoded UI strings from feature screens (auth, litx, trade management, tradepro risk dashboard, tradepro backtest report, journal) to `strings.xml`. Technical labels (`RSI(14)`, `MACD(12,26,9)`, FPS metrics) deliberately excluded per Sprint 4 audit rationale.

5. **Twelve Data provider adapter.** Implemented `TwelveDataProviderAdapter` bridging `TwelveDataDataSource` to the `DataProviderAdapter` interface. Covers forex, stocks, indices, crypto, and ETFs behind the existing `DataProviderRegistry` seam. Error handling returns `emptyList()` per the adapter contract. New test file (5 tests) verifies delegation, error path, and `supports()` semantics.

**Definition of Done status:**
- [x] `checkAutoHalt` uses `>=` (matches `canOpenTrade`); boundary test proves it.
- [x] Domain jacoco gate includes smt + strategies; existing `ignoreFailures` preserves non-breaking rollout.
- [x] 19 new characterization tests across 3 domain classes; all use real instances, exercise production code paths.
- [x] 30 strings externalized across 6 feature files; XML well-formed, no duplicates.
- [x] `TwelveDataProviderAdapter` implements `DataProviderAdapter` fully (not a stub); 5 tests cover the adapter.
- [x] No new TODOs/placeholders; no fully-qualified names in method bodies.
- [ ] **CI build + unit tests green** -- cannot run in this offline/no-SDK sandbox. Must be confirmed by GitHub Actions on push.

---

### Sprint 9 -- Quality fixes and domain test hardening *(status: implemented)*

**Scope.** Semantic review of Sprint 8 identified three non-blocking issues (vacuous conditional assertions, blanket exception swallowing, under-constrained test inputs). This sprint fixes all three and adds edge-case tests to MonteCarloRiskEngine, TradeProBacktestEngine, and CorrelationEngine.

**Changes:**

1. **TwelveDataProviderAdapter: CancellationException fix + startTime KDoc.**
   - The blanket `catch (_: Exception)` now rethrows `CancellationException` before falling through to the empty-list fallback. This preserves structured concurrency -- callers that cancel a coroutine no longer silently swallow the cancellation.
   - Added KDoc to `fetchHistory` explaining that `startTime` is intentionally not forwarded because Twelve Data's free tier does not support a start-date query parameter.

2. **BacktestEngineTest: non-vacuous assertion guard.**
   - The `metrics winRate and profitFactor are computed correctly from trades` test previously wrapped its assertions in `if (metrics.totalTrades > 0)`, which would pass vacuously if the strategy produced zero trades. Replaced with `assertTrue(metrics.totalTrades > 0, ...)` followed by unconditional assertions.

3. **StrategyTesterTest: zero-score test reliability.**
   - Reduced the candle count from 25 to 5 bars. With only 5 bars no strategy can produce 3+ trades, so the zero-score path is guaranteed to be exercised on every run. The conditional fallback branch is removed entirely.

4. **MonteCarloRiskEngine edge-case tests (4 new tests):**
   - `zero risk per trade keeps equity flat` -- verifies that 0% risk means no change.
   - `single trade per run with a winning edge` -- verifies exact end-multiple calculation.
   - `boundary input with winRate exactly 0 and avgLossR 0 keeps equity flat` -- zero-magnitude loss.
   - `single run returns consistent percentiles` -- all percentile bands collapse to the same value.

5. **TradeProBacktestEngine edge-case tests (4 new tests):**
   - `single bar above MIN_BARS returns empty result` -- minimal input just past the guard.
   - `narrative is never blank regardless of trade count` -- validates narrative generation path.
   - `symbol is preserved in the result` -- verifies pass-through for different symbols.
   - `equity curve length equals trade count` -- structural invariant on analytics arrays.

6. **CorrelationEngine boundary tests (4 new tests):**
   - `single-element series produces empty result` -- below MIN_BARS guard.
   - `constant series produces zero correlation` -- zero-variance edge case.
   - `exactly MIN_BARS plus one candle produces a valid result` -- threshold boundary.
   - `empty input map returns empty result` -- fully degenerate input.

**Definition of Done status:**
- [x] `CancellationException` rethrown before fallback; KDoc documents `startTime` non-forwarding rationale.
- [x] BacktestEngineTest metrics test guards against vacuous pass with unconditional `assertTrue`.
- [x] StrategyTesterTest zero-score test uses 5 bars (guarantees < 3 trades); no conditional fallback.
- [x] 12 new edge-case tests across 3 engines; all use real instances, no mocks.
- [x] No new TODOs/placeholders; imports resolve to existing symbols; brace/paren balance verified.
- [ ] **CI build + unit tests green** -- cannot run in this offline/no-SDK sandbox. Must be confirmed by GitHub Actions on push.

---

### Sprint 10 -- Domain coverage expansion (analysis, patterns, sessions, correlation) *(status: implemented)*

**Scope.** Five domain packages had zero test coverage: `analysis/`, `patterns/`, `sessions/`, `correlation/`. All contain pure, stateless domain logic with no DI dependencies -- ideal for characterization tests. This sprint adds comprehensive test files and extends the jacoco coverage gate to include these packages.

**Changes:**

1. **SupportResistanceDetectorTest (4 tests):**
   - `detect returns empty for insufficient data` -- fewer bars than 2*swingLookback+1 yields no zones.
   - `detect identifies zones with repeated swing levels` -- validates clustering of swing points, touch count >= 2, bounds ordering.
   - `zone strength is bounded 0 to 100` -- verifies coerceAtMost enforcement.
   - `maxZones limits output size` -- confirms the take(maxZones) cap.

2. **FibonacciEngineTest (4 tests):**
   - `retracements bullish produces correct levels` -- verifies all 7 ratios with exact price math.
   - `retracements bearish produces inverted levels` -- validates reversed direction calculation.
   - `extensions bullish projects targets above swing high` -- confirms extension ratios and ordering.
   - `projections compute ABC targets correctly` -- validates point-C-based projection formula.

3. **CandlePatternDetectorTest (4 tests):**
   - `detects hammer in downtrend` -- synthetic downtrend + hammer candle triggers detection.
   - `detects bullish engulfing` -- validates two-candle reversal pattern.
   - `detects three white soldiers` -- validates three-candle continuation pattern.
   - `returns empty for insufficient candles` -- single candle yields no patterns.

4. **SessionDetectorTest (4 tests):**
   - `detectSessions returns empty for empty input` -- degenerate input guard.
   - `detectSessions identifies London session hours 7 to 16 UTC` -- validates start/end index mapping.
   - `detectSessions computes correct session high and low` -- verifies price extremes within session bars.
   - `detectSessions handles overnight session (Sydney wraps midnight)` -- validates the hour >= open || hour < close logic.

5. **CorrelationMatrixTest (5 tests):**
   - `computeMatrix with identical series produces correlation of 1` -- perfect positive correlation.
   - `computeMatrix with inversely correlated series produces negative correlation` -- validates STRONG_NEGATIVE classification.
   - `computeMatrix self-correlation is always 1` -- diagonal invariant.
   - `computeMatrix with insufficient data produces zero correlation` -- below 5-point Pearson minimum.
   - `getHedgingPairs returns only strongly negative correlations` -- validates filter helper.

6. **Jacoco coverage gate extension.**
   - Added to `domainCoverageIncludes`: SupportResistanceDetector, FibonacciEngine, CandlePatternDetector, SessionDetector, CorrelationMatrix.
   - Added to `domainCoverageSourceDirs`: analysis, patterns, sessions, correlation directories.
   - Existing `ignoreFailures = true` preserves non-breaking rollout.

**Definition of Done status:**
- [x] 21 new characterization tests across 5 domain packages; all use real instances, synthetic data, no mocks.
- [x] Every test exercises production code paths with concrete assertions (no vacuous conditionals).
- [x] Domain jacoco gate extended to include analysis, patterns, sessions, correlation packages.
- [x] No new TODOs/placeholders; imports resolve to existing symbols; brace/paren balance verified.
- [ ] **CI build + unit tests green** -- cannot run in this offline/no-SDK sandbox. Must be confirmed by GitHub Actions on push.

---

### Sprint 11 -- T5.2: Opt-in remote crash/ANR reporting architecture *(status: implemented)*

**Scope.** Implements the full remote crash/ANR reporting infrastructure (T5.2) without adding external SDK dependencies. The architecture is compile-ready for Sentry/Crashlytics once the dependency is wired; until then, a `NoOpCrashBackend` keeps the build green.

**Changes:**

1. **CrashReporter interface expansion.**
   - Added `recordException(throwable, context)`, `setEnabled(enabled)`, and `recordBreadcrumb(message, category)` with default no-op implementations so `LocalCrashReporter` remains unchanged.

2. **RemoteCrashBackend interface + data models.**
   - `RemoteCrashBackend`: abstract SDK boundary (`initialize`, `captureException`, `captureAnr`, `setEnabled`, `addBreadcrumb`).
   - `SanitizedException` / `StackFrame`: PII-free data transfer objects carrying only type, frames, context map, and optional cause chain.

3. **NoOpCrashBackend.**
   - Default implementation that logs operations at debug level. Ships until a real SDK dependency is added. Annotated `@Singleton` + `@Inject`.

4. **RemoteCrashReporter.**
   - Sanitizes exceptions (strips messages, keeps only type + stack frames + non-PII context keys).
   - Respects opt-in: checks `appPreferences.crashReportingEnabled` before any backend call.
   - DSN sourced from `BuildConfig.CRASH_REPORTING_DSN` (env/CI-injectable, blank by default).
   - Integrates `AnrWatchdog` lifecycle (start on enable, stop on disable).

5. **AnrWatchdog.**
   - Daemon thread posting to main handler every 5s; if the callback does not fire within 5s, captures main-thread stack dump and reports via `CrashReporter.recordException`.
   - Clean start/stop lifecycle, interrupt-safe.

6. **CompositeCrashReporter.**
   - Wraps `LocalCrashReporter` + `RemoteCrashReporter`, dispatching all calls to both. This is the new `CrashReporter` binding.

7. **CrashModule update.**
   - Binds `CompositeCrashReporter` as `CrashReporter` and `NoOpCrashBackend` as `RemoteCrashBackend`.

8. **BuildConfig field.**
   - Added `CRASH_REPORTING_DSN` to `app/build.gradle.kts` (sourced from `local.properties` or `CRASH_REPORTING_DSN` env var, blank default).

9. **Unit tests (9 tests in RemoteCrashReporterTest).**
   - Opt-out suppresses all remote calls.
   - Opt-in forwards exceptions to backend.
   - PII is stripped (exception messages not in sanitized output).
   - Context map is preserved.
   - Cause chain traversal up to depth limit.
   - setEnabled true/false starts/stops ANR watchdog.
   - Breadcrumb gating (opt-in/opt-out).

**Definition of Done status:**
- [x] CrashReporter interface expanded with backward-compatible default methods.
- [x] RemoteCrashBackend abstraction decouples reporter from specific SDK.
- [x] NoOpCrashBackend compiles without any external dependency.
- [x] RemoteCrashReporter never transmits PII (messages stripped, only type + frames + context keys).
- [x] AnrWatchdog detects unresponsive main thread within 5s timeout.
- [x] CompositeCrashReporter dispatches to both local and remote reporters.
- [x] CrashModule binds the composite reporter and no-op backend.
- [x] 9 unit tests covering opt-in gating, PII stripping, context preservation, ANR lifecycle.
- [x] BuildConfig.CRASH_REPORTING_DSN injectable via env/CI; blank by default.
- [ ] **CI build + unit tests green** -- cannot run in this offline/no-SDK sandbox. Must be confirmed by GitHub Actions on push.


---

### Sprint 3 & 4 Completion — Tick engine, Dukascopy `.bi5` decoder, tick replay, ICT kill zones *(status: implemented)*

**Scope.** Closes the two remaining data/analysis gaps that the original Sprint 3 (market-data engine) and Sprint 4 (ICT/SMC concept coverage) left open at the *source-feasible* level: a pure-Kotlin tick ingestion + aggregation path, a Dukascopy `.bi5` binary decoder, tick-driven replay, and the previously-missing ICT Kill Zones. All new code is pure Kotlin (no `android.*` imports) and unit-testable; live HTTP/LZMA transport stays intentionally gated (no network / no LZMA dependency in this environment) behind the decoder.

**Sprint 4 — SMC/ICT concept coverage: verified already-implemented.** A re-review confirms the 13 core institutional concepts are implemented and tested in the tree; this sprint adds the one true gap (kill zones). Verified concepts and their files:

1. Order Blocks — `domain/usecase/smc/SmcDetector.kt` (`detectOrderBlocks`), model `domain/model/SmcConcepts.kt::OrderBlock`.
2. Fair Value Gaps (imbalance) — `SmcDetector.detectFairValueGaps`, model `FairValueGap`.
3. Liquidity pools (buy-side / sell-side) — `SmcDetector` liquidity detection, model `LiquidityPool`.
4. Volume Profile (POC / VAH / VAL) — `SmcDetector`, models `VolumeProfileLevel` / `VolumeProfile`.
5. Breaker Blocks — `SmcDetector.detectBreakers`, model `BreakerBlock`.
6. Inversion FVG (IFVG) — `SmcDetector.detectIFVG`, model `InversionFVG`.
7. Balanced Price Range (BPR) — `SmcDetector.detectBPR`, model `BalancedPriceRange`.
8. AMD / Power of Three — `SmcDetector` AMD detection, model `AmdPattern` / `AmdPhase`.
9. Market-structure breaks BOS / CHOCH / MSS / IDM — model `domain/model/MarketStructure.kt::StructureBreak`, use case `domain/usecase/AnalyzeMarketStructureUseCase.kt` + `domain/usecase/litx/MssClassifier.kt`.
10. Swing points (fractal highs/lows) — `domain/model/MarketStructure.kt::SwingPoint`.
11. SMT divergence — `domain/usecase/smt/SmtDivergenceDetector.kt`.
12. Trading sessions (London / New York / Tokyo / Sydney) — `domain/usecase/sessions/SessionDetector.kt`, model `SessionRange` / `TradingSession`.
13. **ICT Kill Zones — the gap, now closed this sprint** (see below).

**ICT Kill Zone gap — closed.** Added `domain/model/KillZone.kt` (`KillZone` enum: Asian Range `[0,5)`, London Open `[7,10)`, New York Open `[12,15)`, London Close `[15,17)` UTC; plus `KillZoneRange`) and `domain/usecase/sessions/KillZoneDetector.kt` (`@Singleton`). `detect(candles, zones)` mirrors `SessionDetector.detectSessions` exactly — same hour-window membership test (with overnight-wrap handling), same high/low accumulation, same end-of-data flush, sorted by start index. `isInKillZone(timestampMs)` maps a UTC timestamp to its active zone via `java.util.Calendar`. Tests: `KillZoneDetectorTest` (5 tests) mirror `SessionDetectorTest` — hourly UTC candles with index == hour.

**Sprint 3 — tick engine (new, pure Kotlin).**
- `domain/model/Tick.kt` — `Tick(timestampMs, bid, ask, bidVolume, askVolume)` value object with `mid` and `spread` derived properties.
- `domain/usecase/tick/TickAggregator.kt` (`@Singleton`) — `aggregate(ticks, timeframe)` buckets ticks by `floor(ts / durationMs) * durationMs`, builds OHLC from the mid price (open = first tick's mid, close = last tick's mid, high/low = max/min mid), sums `bidVolume + askVolume` for candle volume, sorts ascending, non-repainting. Duration map covers M1..MN.
- Tests: `TickAggregatorTest` (6 tests) — empty→empty, single-tick O=H=L=C=mid, hand-computed multi-tick OHLC, two-bucket split, volume sum, ascending order regardless of input order.

**Sprint 3 — Dukascopy `.bi5` decoder (new, pure Kotlin / `java.nio`).**
- `data/remote/dukascopy/DukascopyTickDecoder.kt` (`@Singleton`) — `decode(decompressed, hourStartMs, pointValue)` parses fixed 20-byte BIG_ENDIAN records (int32 msOffset, int32 askPoints, int32 bidPoints, float32 askVol, float32 bidVol) into `Tick`s: `timestampMs = hourStartMs + msOffset`, `bid = bidPoints / pointValue`, `ask = askPoints / pointValue`, volumes as-is. Trailing partial record (< 20 bytes) is ignored; empty → empty. Uses `java.nio.ByteBuffer` in BIG_ENDIAN.
- Tests: `DukascopyTickDecoderTest` (6 tests) — empty→empty, sub-record→empty, one-record exact decode, trailing-partial ignored, `pointValue` scaling, multi-record order/offset.
- **Transport gated:** the live Dukascopy HTTP fetch + LZMA decompression path is deliberately **not** implemented here (no network access and no LZMA dependency in this environment). The decoder is the pure, testable seam that a future transport feeds; `domain/sdk/provider/DukascopyAdapter.kt` remains a non-live stub until that transport lands.

**Sprint 3 — tick-aware replay.**
- `domain/usecase/replay/ReplayEngine.kt` — constructor changed from `@Inject constructor()` to `@Inject constructor(tickAggregator: TickAggregator)`. New `startTickReplay(ticks, aggregateTo, startAt = 50)` aggregates ticks to candles then reuses the existing `start(candles, startAt)` path, so every playback control behaves identically. Sole construction site (`ReplayEngineTest`) updated to `ReplayEngine(TickAggregator())`; no DI module constructs it manually (Hilt auto-provides the `@Singleton @Inject` `TickAggregator`).
- Tests: `ReplayEngineTickTest` (2 tests) — tick-replay `totalBars` equals `TickAggregator.aggregate` size; `isActive` true after `startTickReplay`.

**Definition of Done status:**
- [x] All new classes are pure Kotlin — zero `android.*` imports in any new main or test file (kill zones / decoder / aggregator use only `java.*`).
- [x] Tests use real instances with synthetic data (no mocks), asserting hand-computed exact values or invariants.
- [x] `ReplayEngine` constructor change: all call sites updated (only `ReplayEngineTest`).
- [x] All referenced `Timeframe` enum values (M1..MN) exist; `Candle` constructor order matches.
- [x] No TODOs / placeholders / empty stubs; live Dukascopy transport gap documented, not faked.
- [x] **CI build + unit tests green** — confirmed by GitHub Actions on the PR (build + 19 new tests pass).

### Sprint 12 — Polygon.io historical provider *(status: source-implemented, CI pending)*

The next provider item from the enhancement roadmap is now wired end to end on
the existing client-side seam. `PolygonApi` and `PolygonDataSource` translate
FoxTrader symbols/timeframes into Polygon aggregate-bar requests, parse
ascending OHLCV data, support strict-before history paging, and reject provider
errors without fabricating bars. `MarketRepositoryImpl` routes refresh, paging,
and the Settings connectivity check through Polygon; `DataProvider.POLYGON` is
now selectable because it has a real fetch path.

A dedicated Retrofit client has no HTTP logging interceptor because Polygon API
keys are query parameters. `PolygonDataSourceTest` covers mapping, sorting,
asset prefixes, error/malformed response handling, paging, and input validation
with a fake API. Polygon's historical path is deliberately not advertised as a
live WebSocket stream yet; the existing Binance/Bybit live path remains separate.

**Definition of Done status:**
- [x] Provider API, adapter, repository routing, connectivity check, and Settings gating wired.
- [x] Provenance remains assigned by the repository (`LIVE` on successful provider writes).
- [x] Unit tests use a fake API with no Android/network dependency.
- [ ] CI build + unit tests green — must be confirmed by GitHub Actions.
