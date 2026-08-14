# FoxTrader — Enhancement Masterplan (Sprint 6 → Sprint 11)

**Status:** proposed · **Author:** engineering audit of `main` @ `166b214`
**Baseline:** 243 Kotlin files · ~26,400 LOC main · 404 unit tests · 0 instrumentation tests
**Governing directive:** [`.kiro/steering/foxtrader-directive.md`](.kiro/steering/foxtrader-directive.md)
**Reference bible:** [`DEVELOPMENT.md`](DEVELOPMENT.md) (Sprint 5 log = Appendix F)

---

## 0. How this plan was built

Sprint 5 fixed the chart camera and wired the performance layer. That closed the
biggest *declared-but-dead* gap in the render path. This audit re-ran the same
question across the whole repo: **what does the documentation promise that the
binary does not do, and what will break first under real usage?**

Three classes of finding, in priority order:

| Class | Meaning | Count found |
|---|---|---|
| **A — Correctness / data-loss risk** | Ships today, can lose or corrupt user data, or silently lies to the trader | 5 |
| **B — Dead capability** | Engine exists, is tested, has **zero call sites** — pure inventory, zero user value | 11 |
| **C — Scale / polish ceiling** | Works at demo scale, fails the directive's bar (120fps, 100k candles, institutional) | 7 |

Everything below is traceable to a file and a line. No speculative features are
scheduled ahead of Class A.

---

## 1. Findings

### 1.1 Class A — correctness and data-loss risk

**A1. `fallbackToDestructiveMigration()` is live in production.**
`di/DatabaseModule.kt:25` calls it *after* `addMigrations(*FoxDatabase.MIGRATIONS)`.
Room's contract: if a migration path is missing, the whole database — including
`journal_entries` and `chart_drawings`, both explicitly documented as
"user-authored data that must survive schema upgrades" (`FoxDatabase.kt:38-40`) —
is **dropped and recreated**. The careful hand-written `MIGRATION_1_2` /
`MIGRATION_2_3` are a safety net with a hole cut in the middle of it. Any future
v4 shipped without a migration silently deletes every trade a user ever logged.

**A2. Room schema export is off, so migrations cannot be tested.**
`exportSchema = false` (`FoxDatabase.kt:27`). Without exported JSON schemas
there is no `MigrationTestHelper`, which means A1 can never be caught by CI —
only by a user losing their journal.

**A3. Seven of ten advertised data providers do nothing.**
`DataProvider` enum declares `POLYGON`, `OANDA`, `ALPACA`, `TWELVE_DATA`,
`INTERACTIVE_BROKERS`, `DUKASCOPY` with `supportsLive = true` and Settings
collects API keys for each. `MarketRepositoryImpl.refreshCandles` branches on
exactly two (`ALPHA_VANTAGE`, `BYBIT`) plus a Binance heuristic; grep for
`POLYGON|OANDA|ALPACA|TWELVE|INTERACTIVE` across `data/` returns **nothing**.
Selecting Polygon and pasting a paid API key silently falls through to
`fetchDefaultCandles`, fails, and lands in the sample-data seeder (below).
`DukascopyAdapter.kt:36` is an explicit `TODO` stub. The user believes they are
looking at their broker's prices. They are looking at a random walk.

**A4. Synthetic data is indistinguishable from real data in the UI.**
`MarketRepositoryImpl.kt:78-88` seeds `SampleData.generate(...)` into the *same*
`candles` table on any network failure with an empty cache, and `getCandles`
does the same for the scanner. Nothing marks those rows as synthetic. The
chart, the 10-agent AI, the backtester and the scanner then produce confident
BUY/SELL narratives over fabricated prices with no visual distinction. For a
tool whose disclaimer promises "analyse markets using historical and simulated
data", conflating the two in one table is the single most dangerous behaviour
in the app.

**A5. Candle cache grows without bound and is never pruned.**
`CandleDao` has `clear(symbol, timeframe)` and nothing else destructive. Every
live WebSocket tick `upsert`s a row; `observe()` is `SELECT * ... ORDER BY
timestamp ASC` with no `LIMIT`. A user who leaves LIVE on across 30 watchlist
symbols accumulates rows forever, and every single tick re-emits the **entire**
series through the Flow → `processCandles` → full structure + indicator +
explanation recomputation. This is both the storage leak and the CPU cliff.

### 1.2 Class B — dead capability (built, tested, unreachable)

Eleven engines have **zero non-test call sites**. Verified by resolving each
class name across `app/src/main`:

| Engine | LOC | Where it should surface |
|---|---:|---|
| `PortfolioEngine` | 189 | README claims exposure/P&L/correlation clusters — no screen renders it |
| `MarketHeatmap` | 146 | Scanner tab, as a visual grid mode |
| `SmartAlertEngine` | 205 | Alerts have no UI at all — no inbox, no history, no ack |
| `WatchlistManager` | 94 | Watchlist is a hardcoded `DEFAULT_SYMBOLS` list in `ChartUiState` |
| `MultiChartManager` | 143 | Directive names multi-chart explicitly; no layout host exists |
| `NewsEngine` | 133 | `NewsAgent` votes on news the app never fetches or displays |
| `PositionCalculator` | 144 | No calculator screen |
| `ConfluenceEngine` | 144 | README claims an MTF confluence overlay on the chart |
| `MarketProfile` | 83 | Volume-profile layer is gated in the quality controller but never drawn |
| `SeasonalityEngine` | 81 | No surface |
| `SupportResistanceDetector` | 81 | No chart layer |
| `MultiTimeframeAnalysisUseCase` | — | Constructed by nothing |

That is ~1,500 lines of tested domain logic delivering exactly zero user value.
**The highest return-on-effort work in this repo is not writing new engines —
it is wiring the ones that already exist.** The directive's "never introduce
technical debt" cuts both ways: unreachable code is debt.

Also dead: `SignalPipeline` (documented Sprint 4 extension point, 0 call sites),
`FibonacciEngine`, `CorrelationMatrix` (1 ref), `RiskGatedBrokerExecutor`.

### 1.3 Class C — scale and polish ceiling

**C1. No history paging.** `refreshCandles(limit = 500)` and there is no
`loadOlder` / prepend path anywhere. The README advertises "100,000+ candles at
120fps" and viewport culling for them; the app can never *obtain* more than 500
bars. The renderer is ready for a dataset the data layer cannot supply.

**C2. Full-series recomputation per tick.** See A5. `processCandles` runs
structure analysis + all indicators + `MarketExplanationEngine` over the whole
list on every emission. Incremental/windowed computation is the only way this
holds at 100k bars.

**C3. Compose stability is unenforced.** Two `@Stable` annotations in the entire
codebase; `ChartUiState` carries `List<Candle>` and raw arrays with a
hand-written `equals` that deliberately skips the arrays. No
`kotlinx-collections-immutable`, no compiler stability config, no strong-skipping
report in CI. Recomposition correctness is currently maintained by a comment.

**C4. No instrumentation, macrobenchmark, or screenshot tests.**
`app/src/androidTest` does not exist. The §4.15 benchmark table in
DEVELOPMENT.md is asserted, never measured on device. No baseline profile →
cold start and first-frame jank are unoptimised by default.

**C5. No static analysis or coverage gate.** No detekt, no ktlint, no
`lint { abortOnError }`, no jacoco. CI builds and runs unit tests; nothing
enforces style, complexity, or coverage. `CandleChart.kt` is 1,200 lines with 18
`DrawScope` extensions in one file — exactly what a complexity gate exists to
catch.

**C6. Release build is not shippable.** `versionCode = 1`, no signing config, no
crash reporting, no `versionNameSuffix` discipline, launcher icon is
`ic_dialog_info` in `AlertDispatcher.kt:55`. The roadmap's "Release on Google
Play Store" has no runway.

**C7. Zero string externalisation.** `strings.xml` holds two entries; every
label in every screen is a hardcoded Kotlin literal. No localisation, and no
accessibility review path.

---

## 2. Sprint plan

Six sprints. Each is independently shippable, ends green, and moves one class of
finding to zero. Ordering is strictly risk-first, then value-per-line, then
scale, then shipping.

---

### Sprint 6 — Data Integrity *(Class A, all five)*

> **Theme:** the app must never lie about where a price came from, and must
> never delete a user's journal.

**6.1 Provenance as a first-class concept.**
Add `CandleSource { LIVE, CACHED, SYNTHETIC }` to the domain model and a
`source` column on `CandleEntity` (schema v4, real migration, default
`'LIVE'` for existing rows). `SampleData` writes `SYNTHETIC`. Then:
- `ChartUiState` exposes `isSynthetic`; `ChartScreen` renders a persistent amber
  **SIMULATED DATA** banner that cannot be dismissed while synthetic bars are in
  the viewport.
- `MasterDecisionEngine` **hard-vetoes** any decision computed over a series
  containing synthetic bars, with reason `SYNTHETIC_DATA`. This is the same
  class of gate as the existing risk/psychology vetoes and belongs beside them.
- Scanner rows sourced from seeded data carry a `SIM` badge.

**6.2 Remove the destructive-migration escape hatch.**
Drop `fallbackToDestructiveMigration()`. Set `exportSchema = true`, commit
`app/schemas/`, add `room.schemaLocation` to KSP args. Add
`MigrationTestHelper` instrumentation tests covering 1→2→3→4 with real row
assertions on journal and drawing survival. A missing migration must now fail
CI, not the user.

**6.3 Honest provider surface.**
Add `DataProvider.implemented: Boolean`. Settings renders unimplemented
providers disabled with a "coming soon" affordance instead of accepting an API
key that goes nowhere. `MarketRepositoryImpl` throws a typed
`ProviderNotImplementedException` rather than silently degrading. Then
implement **Polygon.io** REST aggregates + authenticated minute WebSocket end
to end as an additional real non-crypto provider, behind the existing provider
seams. Higher timeframes must be aggregated locally without fabricating gaps.

**Status update (2026-08-14):** `PolygonApi`/`PolygonDataSource` cover refresh,
strict-before history paging, provider testing, and repository routing.
`PolygonWebSocket` now covers authenticated stock/forex/crypto/index minute
aggregates with bounded reconnects and local no-repaint timeframe aggregation.
`DataProvider.POLYGON` is selectable and advertises live support.

**6.4 Cache retention policy.**
`CandleDao.prune(symbol, timeframe, keepCount)` executes after each refresh and
on a periodic WorkManager job; `observe()` and one-shot reads use a bounded
newest-window query. Cap per series through the persisted cache ceiling; older
bars are evicted without touching user-authored tables.

**Status update (2026-08-14):** `CandleRetentionWorker` and
`CandleRetentionScheduler` now provide the periodic safety net, while chart and
scanner reads remain bounded and ascending.

**Definition of done:** synthetic data is visually and algorithmically
quarantined; no code path can drop a user table; provider list matches reality;
`candles` table size is bounded. Migration tests green in CI.

---

### Sprint 7 — Activation *(Class B — wire the dead engines, part 1)*

> **Theme:** ~1,500 tested lines currently ship as dead weight. Turn them into
> screens. Zero new engines are written this sprint.

**7.1 Portfolio screen** — new bottom-nav destination (or Journal sub-tab) over
`PortfolioEngine`: open exposure, net directional bias, unrealised P&L,
concentration warnings, correlation clusters via `CorrelationMatrix`.

**7.2 Alerts Inbox** — the alert system has an engine, a dispatcher, a worker
and a scheduler, and **no UI**. Add an alerts destination: history, priority
filter, acknowledge, deep-link to the chart at the triggering bar. Wire
`SmartAlertEngine` behind it. Ship the real fox notification icon
(closes `AlertDispatcher.kt:55`).

**7.3 User watchlists** — replace `ChartUiState.DEFAULT_SYMBOLS` with
`WatchlistManager` + a Room-backed `watchlists` table (schema v5). Create,
rename, reorder, multi-list. This is table stakes for every competitor named in
the directive.

**7.4 Position Size Calculator** — a focused sheet over `PositionCalculator`,
reachable from the chart and from the AI decision panel.

**Definition of done:** four engines move from 0 call sites to production
surfaces. Each screen has a ViewModel unit test and a Compose UI test.

---

### Sprint 8 — Chart Depth *(Class B part 2 + C1)*

> **Theme:** the renderer is ahead of its data and its layers. Close both.

**8.1 Infinite history paging.** `MarketRepository.loadOlderCandles(symbol, tf,
before, limit)`; `ChartViewport` fires a prefetch when the left edge crosses a
threshold; results are **prepended** without disturbing the camera anchor (the
anti-drift invariant from §4.6 must hold across a prepend — this needs a
dedicated `ChartViewportTest` case). Loading shimmer at the left gutter. This
is what finally makes the advertised 100k-candle claim reachable.

**8.2 Incremental analysis.** Split `processCandles` into a full pass (on
symbol/timeframe change) and an incremental pass (on tick) that recomputes only
the trailing window. Indicator state becomes resumable (EMA/RSI/ATR are all
naturally incremental). Target: tick-to-frame under 4ms on the reference device.

**8.3 New chart layers, from existing engines.**
- `MarketProfile` → volume profile / value-area histogram (the quality
  controller already has a `volumeProfile` gate waiting for it).
- `SupportResistanceDetector` → auto S/R bands.
- `ConfluenceEngine` → the MTF confluence ribbon the README already claims.
- `FibonacciEngine` → auto-fib on the last confirmed swing.
All four are quality-tier gated and off by default in the indicator panel.

**8.4 Multi-chart layout.** Host `MultiChartManager`: 1×1 / 1×2 / 2×2 grids,
synchronised crosshair and optional symbol/timeframe linking.

**8.5 Split `CandleChart.kt`.** 1,200 lines → `layers/` package, one file per
`DrawScope` layer, with the composable reduced to orchestration. Prerequisite
for the complexity gate in Sprint 10.

---

### Sprint 9 — Performance Proof *(Class C2, C3, C4)*

> **Theme:** the directive demands 120fps and continuous profiling. Right now
> that is an assertion in a markdown file.

**9.1 Macrobenchmark module** (`:benchmark`): startup (cold/warm), chart scroll
jank, pinch-zoom frame timing, 100k-candle load. Runs on an emulator in CI, with
results published as a build artifact and regression thresholds enforced.

**9.2 Baseline + startup profile** generated from the benchmark journeys and
committed. Typically 20-30% off cold start for a Compose app this size.

**9.3 Compose stability enforcement.** Adopt `kotlinx-collections-immutable`
for all UI-facing collections, add a `stability-config` file, enable
strong-skipping, and emit compiler metrics with a CI check that fails on new
unstable parameters in the chart hot path.

**9.4 Instrumentation + screenshot tests.** Create `app/src/androidTest`
(currently absent): navigation smoke, Compose UI tests per screen, and Roborazzi
screenshot tests for the Fox Design System so theme regressions are visible in
PR diffs.

**9.5 Memory.** LeakCanary in debug; an allocation audit of the draw pass
(`Paint()` construction and `Path()` at `CandleChart.kt:907` must be hoisted to
`remember`).

---

### Sprint 10 — Engineering Hygiene *(Class C5, C7)*

**10.1 Static analysis gate:** detekt (with a complexity budget), ktlint, and
Android `lint { abortOnError = true }`, all wired into CI as required checks.
Baseline the existing violations, fail on new ones.

**10.2 Coverage gate:** jacoco with a floor on `domain/` (proposal: 80% line,
given 404 tests already exist there), reported per PR.

**10.3 Full string externalisation** into `strings.xml`, plus content
descriptions on every interactive chart control. Unblocks localisation and a
real accessibility pass.

**10.4 CI matrix hardening:** run on `arena/**` branches too (currently only
`main`/`feat/**`/`fix/**`, so agent branches build nothing), add a release-build
job so R8/proguard breakage is caught before tagging, and cache the Gradle
configuration properly.

---

### Sprint 11 — Release Readiness *(Class C6)*

**11.1** Signing config from env/keystore, `versionCode` derived from CI run
number, `bundle` (AAB) output alongside the APK.
**11.2** Crash + ANR reporting (Firebase Crashlytics or Sentry), behind an
opt-in privacy toggle, with a documented no-PII policy.
**11.3** Play Store assets: icon set, feature graphic, screenshots generated
from the Sprint 9 screenshot tests, data-safety declaration, and a prominent
in-app rendering of the educational-tool disclaimer.
**11.4** Internal testing track release, then closed beta.

---

## 3. Sequencing rationale

```
Sprint 6  Data Integrity      ← nothing else matters if the prices are fake
   │                            and the journal can vanish
   ▼
Sprint 7  Activation          ← highest value-per-line in the repo:
   │                            0 new engines, 4 new surfaces
   ▼
Sprint 8  Chart Depth         ← needs S6 provenance + S7 watchlists;
   │                            unlocks the 100k-candle claim
   ▼
Sprint 9  Performance Proof   ← measures what S8 built, on real hardware
   │
   ▼
Sprint 10 Hygiene             ← gates protect everything above from regression
   │
   ▼
Sprint 11 Release             ← ship it
```

Sprint 6 is non-negotiable and non-parallelisable. Sprints 7 and 8 could
overlap if two workstreams exist, with the caveat that 8.5 (splitting
`CandleChart.kt`) will conflict with any concurrent chart work — do it first or
last, never in the middle.

---

## 4. Success metrics

| Metric | Baseline | Target |
|---|---|---|
| Engines with 0 call sites | 11 | 0 |
| Advertised providers that actually fetch | 3 / 10 | honest list, ≥4 real |
| User-data loss paths | 1 (destructive migration) | 0 |
| Synthetic data distinguishable in UI | no | yes, + AI veto |
| Max candles loadable per series | 500 | unbounded (paged) |
| Instrumentation / benchmark tests | 0 | full suite, CI-enforced |
| Static analysis gates | 0 | detekt + ktlint + lint + jacoco |
| Externalised strings | 2 | 100% |
| Measured p95 frame time @120Hz | unmeasured | < 8.3ms, CI-tracked |

---

## 5. Explicitly out of scope

Per the directive ("never convert to a website, never create another
repository") and to keep focus:

- **FastAPI backend / PostgreSQL / Redis.** The app is offline-first and
  correct without it. Revisit only when cloud sync or social features are
  actually scheduled.
- **Social / copy-trading.** Requires the backend, and requires the risk
  engine to be battle-tested first.
- **Real broker order routing.** `RiskGatedBrokerExecutor` exists and is tested,
  but connecting live capital before Sprints 6, 9 and 10 are complete would be
  reckless. Paper-trading only until then.
- **Wear OS / Android Auto / voice.** Roadmap H5. Nothing above depends on them.
- **LLM trade authority.** Stays narration-only, permanently. Raw candles are
  never delegated to an LLM for a decision — this is an architectural
  invariant, not a scheduling decision.

---

## 6. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Schema v4/v5 migrations land buggy | User data loss — the exact thing S6 exists to prevent | S6.2 migration tests ship **before** S6.1's new column |
| Prepend paging breaks camera anti-drift | The app's single most important interaction regresses | Dedicated `ChartViewportTest` cases written before the feature |
| `CandleChart.kt` split causes visual regressions | Silent rendering bugs | Do it *after* Sprint 9's screenshot tests exist, or accept manual QA |
| Polygon integration blocked on API access | S6.3 slips | Adapter interface + fakes land first; the concrete provider is swappable |
| Detekt baseline hides real problems | Debt frozen, not paid | Baseline expires — a scheduled ticket per sprint burns it down |
| Scope creep into new engines during S7 | Dead code count goes *up* | S7 has a hard rule: zero new domain engines |

---

## 7. Working agreements

Carried forward from the directive and DEVELOPMENT.md §12.10:

1. Every sprint ends with a green `:app:assembleDebug` **and**
   `:app:testDebugUnitTest`. Never leave the repo broken.
2. Every feature ships with unit tests; every screen ships with a UI test from
   Sprint 9 onward.
3. Conventional Commits, one logical change per commit.
4. Every sprint appends an Improvement Log appendix to `DEVELOPMENT.md`
   (Appendix G onward), in the same evidence-first style as Appendix F: what was
   claimed, what was actually there, what changed, what is now tested.
5. Refactor before adding. If the existing code can be improved, improve it
   first — the eleven dead engines are the standing proof of what happens when
   this rule is skipped.
