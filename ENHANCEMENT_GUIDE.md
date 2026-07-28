# FoxTrader Enhancement Guide

**How to safely improve and extend FoxTrader — for every human engineer and every AI agent.**

> **Version:** 1.0
> **Companion to:** `DEVELOPMENT.md` (the Engineering Bible), `ENTERPRISE_MASTER_PLAN.md` (roadmap), `ENGINEERING_RESEARCH.md` (competitive principles).
> **Status:** Authoritative playbook. If you change FoxTrader, you follow this. It exists so that changes land **green on the first CI run**, preserve the app's core invariants, and never introduce technical debt.

Conventions match the Engineering Bible: **`RULE`** = must-do (blocks a PR if violated), **`WARNING`** = known trap, **`TROUBLESHOOT`** = a failure we have already hit and its fix, **`NOTE`** = rationale.

This guide is written from real build failures encountered while hardening this codebase. Every `TROUBLESHOOT` below is a mistake that actually broke CI. Read them before you write code.

---

## 0. The one-paragraph summary

FoxTrader is a native Android app (Kotlin + Jetpack Compose + Hilt + Room, MVVM + Clean Architecture). You enhance it by working **through the existing seams**, not around them: put money math behind `InstrumentTypeResolver`, put analysis in pure non-repainting domain use-cases, put UI orchestration in feature ViewModels composed of small controllers, and wire everything with constructor injection. You keep it compiling by respecting a short list of Hilt/Room/Gradle rules (§4). You keep it correct by preserving four invariants (§3). You prove it with tests you have actually reasoned through (§6). You ship it via a PR that you pre-flight with the checklist in §9.

---

## 1. The mandatory workflow (never skip a step)

Every enhancement, from a one-line fix to a new subsystem, follows the same loop. This is the exact loop used to build the current codebase.

1. **Understand before touching.** Read the relevant files *in full*. Never propose a change to code you have not read. Use repo search to find every call site of anything you intend to change.
2. **Compare designs, reject the weak ones.** Write down at least two approaches and pick the strongest production-ready one. Prefer reusing an existing concept over inventing a parallel one (see §5, the `InstrumentTypeResolver` story).
3. **Plan the change as a diff.** Know exactly which files change, which tests change, and which call sites are affected — *before* editing.
4. **Implement through the existing seams** (§2, §5). No placeholders. No `TODO`. No dead code.
5. **Self-review against §4 (build safety) and §3 (invariants).** Run the pre-flight checklist (§9) mentally and with grep.
6. **Write/adjust tests and reason through them by hand** (§6). A test you have not mentally executed is not a passing test.
7. **Pre-flight, commit, push to a branch, open/refresh a PR** (§8).
8. **Watch CI. If red, read the actual failing task/test, fix the root cause, repeat.** Never guess-and-push blindly.

`RULE` Do the work in that order. Most CI failures come from skipping step 1 or step 5.

---

## 2. Architecture map — where every kind of change goes

```
app/src/main/java/com/foxtrader/app/
├── data/            # Framework-facing: Room, Retrofit, WebSocket, DataStore, mappers
│   ├── local/       # Room DB, DAOs, entities, migrations
│   ├── remote/      # Retrofit APIs, DTOs, websocket clients (the LIVE data path)
│   ├── repository/  # Repository IMPLEMENTATIONS (implement domain interfaces)
│   └── mapper/      # entity<->domain<->dto conversions (pure functions)
├── domain/          # Pure business logic. NO Android imports. Deterministic. Testable on the JVM.
│   ├── model/       # Immutable data classes + enums (the ubiquitous language)
│   ├── repository/  # Repository INTERFACES (domain owns these; data implements them)
│   ├── sdk/         # Plugin registries: indicators, drawing tools, brokers, providers
│   └── usecase/     # The engines: risk, smc, smt, ai, backtest, scanner, portfolio, ...
├── feature/         # One package per screen: presentation/ (Compose UI + ViewModel + controllers)
├── ui/              # Navigation, theme, shared design system
└── di/              # Hilt modules (one per concern)
```

`RULE` **Dependency direction is one-way:** `feature → domain ← data`. `domain` must never import `data`, `feature`, or any Android UI/framework class. If a domain file needs Android, the design is wrong — move the Android part to `data` or `feature`.

`RULE` **Domain owns interfaces; data implements them.** A new data source implements an existing (or new) `domain/repository` interface and is bound in a Hilt module. Consumers depend on the interface, never the implementation.

**Decision table — "I want to add X, where does it go?"**

| You are adding… | Put it in… | Wire it via… |
|---|---|---|
| A new indicator/analysis algorithm | `domain/usecase/<area>/` as a pure class | inject into the coordinator/use-case that needs it |
| A new market-data provider | `data/remote/` implementing the provider seam | a Hilt `@Provides`/`@Binds` in `di/` + `DataProvider` enum entry |
| A new screen | `feature/<name>/presentation/` (Screen + ViewModel) | a route in `ui/navigation/` |
| New persisted data | new Room entity + DAO + **migration** + schema (§4.3) | `DatabaseModule` |
| A new AI reasoning dimension | `domain/usecase/ai/agents/` implementing `TradingAgent` | register in `AgentOrchestrator` weights |
| Cross-cutting money math | reuse `InstrumentTypeResolver` (§5) | inject it |

---

## 3. The four invariants you must never break

These are the soul of the product. A change that violates any of them is wrong even if it compiles and tests pass.

1. **`RULE` Data provenance is sacred.** Every candle carries a `CandleSource`. Never fabricate data and present it as real. Synthetic/sample data must stay tagged, and the AI decision layer must veto on it (it already does — do not remove that veto).
2. **`RULE` Analysis is non-repainting.** Any detector/indicator that runs on bar index `i` may read only candles `[0..i]`. Never let a past signal change because of a future bar. This is why backtests are trustworthy. When you add analysis, write a test that proves it (see `SmcDetector` — index `i` never reads `i+1`).
3. **`RULE` Risk/money math is asset-class-correct.** Never hardcode a contract size. All money↔price conversion goes through `InstrumentTypeResolver.resolve(symbol).contractSize` (§5). The formula is `moneyRisk = stopDistance × volume × contractSize`.
4. **`RULE` AI advises; it never has unchecked authority.** The decision engine is deterministic and pure, gated in this order: data-integrity veto → risk/psychology veto → directional consensus → confluence count → confidence. LLMs are narration-only. Do not let a model place or size trades directly.

---

## 4. Build-safety rules (the exact things that break CI)

This section is the highest-value part of the guide. Each `TROUBLESHOOT` is a real failure from this codebase's history.

### 4.1 Hilt / Dagger

`RULE` **Never put a default value on an `@Inject` constructor parameter.**

```kotlin
// WRONG — breaks the build with "[Dagger/MissingBinding] ... cannot be provided
// without an @Inject constructor or an @Provides-annotated method"
class RiskEngine @Inject constructor(
    private val resolver: InstrumentTypeResolver = InstrumentTypeResolver(), // ← default kills Dagger
)

// RIGHT — declare the dependency; Dagger provides it from the graph
class RiskEngine @Inject constructor(
    private val resolver: InstrumentTypeResolver,
)
```

`TROUBLESHOOT` *Symptom:* `hiltJavaCompileDebug` fails with `Dagger/MissingBinding ... RiskEngine cannot be provided`. *Cause:* a default value on an `@Inject` constructor param — Kotlin emits two constructors and Dagger can't pick the injection target. *Fix:* remove the default and pass the dependency explicitly. **Then update every non-DI call site** (mainly unit tests) to construct it with the real dependency, e.g. `RiskEngine(InstrumentTypeResolver())`, and add the import.

`RULE` The dependency you inject must itself be injectable — it needs an `@Inject constructor()` or a `@Provides`/`@Binds` in a Hilt module. `InstrumentTypeResolver` is `@Singleton @Inject constructor()`, so it is always available.

`RULE` No fully-qualified class names inside `@Inject` constructors — add a normal `import`.

### 4.2 Kotlin / serialization

`RULE` `kotlinx.serialization` does not support `@Serializable` on generic types without a custom serializer. Every enum used inside a `@Serializable` class must itself be `@Serializable`.

`RULE` Executable invariants (`require`/`check`) throw in **both** debug and release. Use them only where the condition is a genuine precondition the caller guarantees, or where you have already `coerceIn`-clamped the value. **Do not** put a `check(x in a..b)` on a raw computed score that could drift out of range by floating-point rounding — clamp first, then (optionally) assert. Example from `AgentOrchestrator`: confidence is `.coerceIn(0.0, 100.0)` *before* the `check`, so the check can never crash a user.

### 4.3 Room

`RULE` Every schema change bumps `FoxDatabase` `version` **and** adds a hand-written `Migration`. Never add `fallbackToDestructiveMigration()` — it silently deletes user journals/drawings/watchlists.

`RULE` `exportSchema = true` stays on. The exported JSON under `app/schemas/` is what `FoxDatabaseMigrationTest` validates against. `NOTE` The schema JSON is generated by KSP during a real build; if you cannot build locally (e.g., no Android SDK), the CI build generates it — do not fake it by hand.

### 4.4 Gradle build script

`TROUBLESHOOT` *Symptom:* build script fails evaluating `fileTree(...)`. *Cause:* `layout.buildDirectory.dir("x")` returns a `Provider<Directory>`, but `fileTree()` expects a resolved `File`/path. *Fix:* pass a path string (`"$buildDir/x"`) — the deprecation warning on `$buildDir` is harmless and does not fail the build. (Compose `reportsDestination`/`metricsDestination` *do* accept `Provider<Directory>`; jacoco `fileTree` does not. Know which API you are calling.)

`RULE` detekt and ktlint are being rolled out app-wide with `ignoreFailures = true` during the burn-down. Keep them non-blocking until a real baseline is generated from a passing build; then switch to fail-on-new. Do not make them block `assembleDebug` before the baseline exists, or every PR turns red on pre-existing style debt.

`NOTE` CI (`.github/workflows/android.yml`) runs `:app:assembleDebug` then `:app:testDebugUnitTest`. "APK build error" almost always means a Kotlin/Hilt **compile** failure in `main`; a test report link means a **unit-test** failure. Read which one before fixing.

### 4.5 Universal hygiene

`RULE` No `TODO`/`FIXME` in `app/src/main`. No placeholder bodies. No dead code (a class with zero references is either wired or deleted — see §7).

---

## 5. The golden pattern: reuse concepts, don't clone them

The single most instructive fix in this codebase: the risk engine hardcoded the forex 100 000-unit lot (`* 100_000`) in six places, so crypto/metals/indices/stocks were mis-sized by orders of magnitude.

The **wrong** fix would have been to invent a new `InstrumentSpec` type. The **right** fix reused the concept that already existed — `InstrumentTypeResolver` + `PositionCalculator.InstrumentType` — and threaded it through every money↔price site:

```kotlin
// The one true way to convert price distance into money for any instrument:
val contractSize = instrumentTypeResolver.resolve(symbol).contractSize
val moneyRisk = stopDistance * volume * contractSize
```

`RULE` Before adding a new type/util, search the codebase for an existing one that already models the concept. Extending one canonical concept beats maintaining two that can disagree. When you touch one money-math site, grep for the others (`grep -rn "100_000\|contractSize" app/src/main`) and fix them together, or you leave a latent bug.

---

## 6. Testing discipline

`RULE` **Reason through every test by hand before claiming it passes.** In this sandbox you frequently cannot run the Android test suite; a test you have not mentally executed with concrete numbers is not verified.

`RULE` **Parametrize money/analysis tests by asset class.** Prove EURUSD (100k), BTCUSD (1), XAUUSD (100), US30 (1) all produce correct volume and risk. A single forex-only test is how the original bug survived.

`RULE` **Refactors that must not change behavior get an equivalence test.** When `SmcDetector.analyzeAll()` was added to share OB/FVG computation, an equivalence test asserts `analyzeAll(candles)` equals the individual `detect*` calls. Any behavior-preserving refactor (compute reuse, incremental analysis) needs one.

`TROUBLESHOOT` *Symptom:* `testDebugUnitTest` fails; a newly-added test's assertion is wrong. *Cause (real example):* a liquidity test asserted `buySide.isEmpty()`, but 27 identical baseline highs legitimately form a cluster, so it was never empty. *Fix:* assert the real intent (`buySide.none { it.price > 106.0 }` — the *distant* highs don't merge), not an incidental property. *Prevention:* when you write a test, compute the expected value from the actual algorithm, not from your assumption of it.

`RULE` When you change a class's constructor (e.g., adding an injected dependency), update **every** test that constructs it directly, and add the needed import. Grep: `grep -rn "ClassName(" app/src/test`.

`WARNING` Adding an injected dependency to a `@HiltViewModel` needs no test change if tests don't construct it manually — but adding one to a plain `@Inject` class used in unit tests does. Check both.

---

## 7. Refactoring discipline

`RULE` **Break up god objects into cohesive controllers behind a slim orchestrator.** `ChartViewModel` went from 1,388 lines / 17 deps to ~399 lines by extracting `ChartDataController`, `ChartIndicatorCoordinator`, `ChartAiCoordinator`, `ChartDrawingController`, `ChartMultiChartController`. Each controller has one responsibility and its own tests; the ViewModel just wires them. Follow this pattern for any file that grows past a few hundred lines or gains too many dependencies.

`RULE` **Delete dead code; don't preserve it "just in case."** Git history is the archive. The orphaned real-time market engine (35 files, zero references) and five unreferenced engines were deleted because carrying tested-but-unwired code is the largest form of debt. Before deleting, confirm zero references: `grep -rn "\bClassName\b" app/src/main`. Before wiring instead of deleting, confirm every dependency it imports still exists.

`RULE` **Compute once, reuse.** `detectBreakers/detectIFVG/detectBPR` re-ran `detectOrderBlocks`/`detectFairValueGaps`; `findPriceClusters` was O(n²). The fix: `analyzeAll()` computes OB/FVG once and passes them down, and clustering is bucketed to O(n). Prove equivalence with a test (above).

---

## 8. PR & CI workflow

`RULE` Always work on a branch; never push to `main` directly. Use the platform's push/PR tooling, not raw `git push`.

`RULE` **If your PR was already merged, do not reuse its branch.** Create a fresh branch off the latest `main` and open a new PR. (This session merged PR #39, then continued on a new branch for later phases.)

`RULE` **Resolving conflicts after a competing PR merged:** pull latest `main`, merge it into your branch, and resolve in line with the recorded design decision. Example: a competing PR re-added files we had deliberately deleted (the orphaned market engine). Resolution = keep the deletion (accept our decision), then delete any *new* files that depend on the deleted infrastructure, because they can no longer compile. After resolving, re-verify there are **no dangling imports** to deleted classes (`grep -rn "import ...deletedpkg" app/src`).

`RULE` A CI-config change (`.github/workflows/*`) is only ever landed via PR, never pushed straight to `main`.

---

## 9. Pre-flight checklist (run before every push)

Copy-paste these and confirm each is clean:

```bash
# 1. No merge-conflict markers anywhere
grep -rnE "^<{7} |^={7}$|^>{7} " --include="*.kt" --include="*.kts" --include="*.toml" --include="*.xml" .

# 2. No TODO/FIXME left in production code
grep -rnE "//.*(TODO|FIXME)" app/src/main --include=*.kt

# 3. No default values on @Inject constructors
grep -rn "@Inject constructor" -A6 app/src/main --include=*.kt | grep -n "= "

# 4. No hardcoded forex lot in money math
grep -rn "100_000\|100000" app/src/main/java/com/foxtrader/app/domain/usecase/risk \
    app/src/main/java/com/foxtrader/app/domain/usecase/portfolio \
    app/src/main/java/com/foxtrader/app/domain/usecase/journal

# 5. No dangling imports to anything you deleted
grep -rn "import com.foxtrader.app.<deleted.package>" app/src

# 6. Every test that constructs a class whose constructor you changed is updated
grep -rn "ChangedClass(" app/src/test

# 7. Brace balance on each file you edited (quick sanity)
#    { count == } count
```

`RULE` If any of 1–5 returns an unexpected hit, fix it before pushing. Green locally-verifiable checks first; only then rely on CI for the full compile+test.

---

## 10. Failure catalog (every CI break we hit, and how we prevent it)

| # | Symptom in CI | Root cause | Permanent prevention |
|---|---|---|---|
| 1 | `Dagger/MissingBinding: RiskEngine cannot be provided` | default value on `@Inject` constructor param | §4.1 — never default `@Inject` params; update test call sites |
| 2 | `testDebugUnitTest` failing test | new test asserted an incidental property (`buySide.isEmpty()`) that the algorithm legitimately violated | §6 — compute expected values from the real algorithm |
| 3 | Gradle script fails on `fileTree(...)` | passed `Provider<Directory>` where a `File` was required | §4.4 — use `"$buildDir/..."` for `fileTree` |
| 4 | `assembleDebug` blocked by detekt | detekt expanded app-wide surfaced pre-existing issues | §4.4 — `ignoreFailures = true` until a baseline exists |
| 5 | Merge conflict on deleted files | competing PR modified files we deleted | §8 — keep the decision, delete new dependents, clear dangling imports |

---

## 11. A worked example — adding a new indicator end-to-end (the safe way)

1. **Design:** it's pure analysis → `domain/usecase/indicators/` (or `analysis/`). Decide inputs (a `List<Candle>`) and output (an immutable result type in `domain/model/`).
2. **Implement** a pure class, non-repainting (index `i` reads only `[0..i]`), `@Inject constructor()` (no defaults). No Android imports.
3. **Register/inject:** add it to the coordinator/use-case that renders indicators (e.g., `ComputeIndicatorsUseCase` or `ChartIndicatorCoordinator`) via its constructor. If a `@HiltViewModel` needs it transitively, it flows automatically.
4. **Test:** parametrized unit tests with hand-computed expected values; a non-repainting test; an equivalence test if you refactored anything existing.
5. **UI (optional):** surface it through the existing indicator toggle/state in the chart feature; externalize any new user-facing string into `strings.xml`.
6. **Pre-flight (§9), commit, PR, watch CI.**

`RULE` If at any point you feel the urge to hardcode a number (a lot size, a pip value, a magic threshold), stop and check whether an existing resolver/config already owns that concept (§5).

---

## 12. Definition of Done (applies to every change)

- Compiles; `:app:assembleDebug` and `:app:testDebugUnitTest` pass in CI.
- No new `TODO`/`FIXME`, no placeholders, no dead code, no new tech debt.
- The four invariants (§3) are intact.
- Tests added/updated and hand-verified; behavior-preserving refactors have equivalence tests.
- Pre-flight checklist (§9) clean.
- One logical change per commit, Conventional Commit message.
- Docs updated when behavior or architecture changed (this guide, `DEVELOPMENT.md`, or the master plan as appropriate).

---

*Follow this guide and your enhancement lands green, correct, and debt-free — the same logic and method used to build the current FoxTrader.*
