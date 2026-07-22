# FoxTrader Development Guide

A complete point-by-point guide for developers working on FoxTrader.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Environment Setup](#2-environment-setup)
3. [Architecture Overview](#3-architecture-overview)
4. [Project Layers Explained](#4-project-layers-explained)
5. [Build & Run](#5-build--run)
6. [Feature Development Workflow](#6-feature-development-workflow)
7. [Chart Engine Development](#7-chart-engine-development)
8. [Domain Engine Development](#8-domain-engine-development)
9. [Data Layer Development](#9-data-layer-development)
10. [Testing](#10-testing)
11. [CI / CD Pipeline](#11-ci--cd-pipeline)
12. [Code Style & Conventions](#12-code-style--conventions)
13. [Dependency Management](#13-dependency-management)
14. [Common Issues & Troubleshooting](#14-common-issues--troubleshooting)
15. [Release Process](#15-release-process)

---

## 1. Prerequisites

Before you start, ensure you have:

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK** | 17 (Temurin recommended) | Kotlin/Gradle compilation |
| **Android SDK** | API 34 (compileSdk) | Android platform APIs |
| **Android Studio** | Hedgehog (2023.1) or newer | IDE with Compose preview |
| **Git** | 2.30+ | Version control |
| **Device/Emulator** | API 29+ (Android 10) | Running/testing the app |

**Optional but recommended:**
- Android Studio's "Compose Layout Inspector" for debugging UI
- `scrcpy` for mirroring a physical device to your monitor

---

## 2. Environment Setup

### Step 1: Clone the Repository

```bash
git clone https://github.com/hamahasan441-png/Foxtrader.git
cd Foxtrader
```

### Step 2: Verify the Gradle Wrapper

The wrapper is committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`). No need to install Gradle separately.

```bash
./gradlew --version
# Should output: Gradle 8.9
```

### Step 3: Accept Android SDK Licenses

```bash
yes | sdkmanager --licenses
```

### Step 4: Open in Android Studio

- File > Open > select the `Foxtrader` root directory
- Wait for Gradle sync (first time takes 3-5 minutes)
- Android Studio will download all dependencies automatically

### Step 5: Run on Device

- Connect an Android 10+ device (USB debugging enabled) or start an emulator
- Click the Run button (green triangle) or:

```bash
./gradlew :app:installDebug
adb shell am start -n com.foxtrader.app.debug/.MainActivity
```

---

## 3. Architecture Overview

FoxTrader follows **MVVM + Clean Architecture** with three distinct layers:

```
┌─────────────────────────────────────────────────────┐
│                  PRESENTATION                        │
│  (Composables, ViewModels, UiState, Navigation)     │
│  Depends on: Domain                                 │
├─────────────────────────────────────────────────────┤
│                     DOMAIN                           │
│  (Models, Use Cases, Repository Interfaces)          │
│  Depends on: Nothing (pure Kotlin)                   │
├─────────────────────────────────────────────────────┤
│                      DATA                            │
│  (Room, Retrofit, DTOs, Mappers, Repo Impl)          │
│  Depends on: Domain (implements its interfaces)      │
└─────────────────────────────────────────────────────┘
```

**Key principles:**
- Domain layer has ZERO Android/framework dependencies
- Data flows up via Kotlin Flow (reactive)
- UI is a pure function of immutable state (UiState data class)
- Dependency Injection via Hilt binds everything together

---

## 4. Project Layers Explained

### 4.1 Domain Layer (`domain/`)

The **business logic core**. Pure Kotlin — no Android imports, no framework dependencies. Can be unit-tested without Android instrumentation.

**Contents:**

| Package | What it does |
|---------|-------------|
| `model/` | Data classes: `Candle`, `MarketStructure`, `Bias`, `Risk`, `Backtest`, `Alert`, `Scanner` |
| `repository/` | `MarketRepository` interface (contract for data layer) |
| `usecase/` | Business logic classes (single responsibility each) |

**Key Use Cases:**

| Use Case | Purpose |
|----------|---------|
| `AnalyzeMarketStructureUseCase` | Detects BOS/CHOCH, determines bias. Non-repainting. |
| `MultiTimeframeAnalysisUseCase` | Confluence across multiple timeframes |
| `TechnicalIndicators` | EMA, SMA, VWAP, RSI, MACD, ADX, ATR, Momentum, Relative Volume |
| `RiskEngine` | Position sizing, stop-loss, pre-trade gating, drawdown protection |
| `BacktestEngine` | Bar-by-bar strategy execution with full metrics |
| `ScannerUseCase` | Multi-asset screener with composite scoring |
| `AlertEngine` | Priority filtering, cooldown dedup, rate limiting |
| `CandlePatternDetector` | Engulfing, pin bar, doji, tweezers, morning/evening star |

### 4.2 Data Layer (`data/`)

Implements domain interfaces using Android frameworks. Follows the **offline-first** pattern.

| Package | What it does |
|---------|-------------|
| `local/` | Room database (`FoxDatabase`), DAOs, entities |
| `remote/` | Retrofit API interface, DTOs |
| `mapper/` | Bidirectional mappers: Entity <-> Domain, DTO <-> Domain |
| `repository/` | `MarketRepositoryImpl` — offline-first with sample data seeding |
| `alerts/` | `AlertDispatcher` — Android notification dispatch |

**Data flow:**
```
Network API → DTO → Mapper → Room Entity → Database (SSOT)
                                    ↓
                              Flow<Entity> → Mapper → Domain Model → ViewModel
```

### 4.3 Presentation Layer (`feature/` + `ui/`)

Jetpack Compose screens following MVVM pattern.

| Component | Role |
|-----------|------|
| `Screen` (Composable) | Pure function of UiState. No business logic. |
| `ViewModel` | Holds StateFlow<UiState>, calls use cases, handles events |
| `UiState` (data class) | Immutable snapshot of everything the UI needs |
| `Navigation` | Single NavHost with bottom navigation bar |
| `Theme` | Fox Design System (dark/light, amber accent, mono prices) |

**Screen lifecycle:**
```
ViewModel.init → observe repository Flow → transform to UiState → emit
    ↓
Composable → collectAsStateWithLifecycle() → recompose on change
    ↓
User gesture → ViewModel.onEvent() → use case → repository → database → Flow updates → repeat
```

### 4.4 DI Layer (`di/`)

Hilt modules bind interfaces to implementations:

| Module | Provides |
|--------|----------|
| `DatabaseModule` | Room `FoxDatabase` instance + DAOs |
| `NetworkModule` | Retrofit + OkHttp client |
| `RepositoryModule` | Binds `MarketRepository` → `MarketRepositoryImpl` |
| `DispatcherModule` | Named coroutine dispatchers (IO, Default, Main) |

---

## 5. Build & Run

### Build Commands

```bash
# Debug APK (fast, no minification)
./gradlew :app:assembleDebug

# Release APK (minified with R8, needs signing key)
./gradlew :app:assembleRelease

# Run unit tests
./gradlew :app:testDebugUnitTest

# Run all checks (lint + tests)
./gradlew :app:check

# Clean build
./gradlew clean :app:assembleDebug
```

### Output Locations

| Build Type | Path |
|-----------|------|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| Test Reports | `app/build/reports/tests/testDebugUnitTest/` |
| Lint Report | `app/build/reports/lint-results.html` |

### Build Performance Tips

- Gradle daemon runs in background (persistent JVM)
- Parallel execution enabled in `gradle.properties`
- Build cache enabled (incremental compilation)
- Configuration cache is **disabled** for Hilt/KSP compatibility

---

## 6. Feature Development Workflow

### Step 1: Create a Feature Branch

```bash
git checkout -b feat/your-feature-name
```

### Step 2: Define the Domain Model

Start in `domain/model/`. Create immutable data classes.

```kotlin
// domain/model/YourFeature.kt
data class YourModel(
    val id: String,
    val value: Double,
)
```

### Step 3: Define the Use Case

Create a class in `domain/usecase/` with `@Inject constructor`.

```kotlin
class YourUseCase @Inject constructor() {
    operator fun invoke(input: List<Candle>): YourModel {
        // Pure business logic — no Android imports
    }
}
```

### Step 4: Wire Up Data Layer (if needed)

- Add DAO methods for new queries
- Add mapper functions
- Update `MarketRepositoryImpl` or create new repository

### Step 5: Create the Screen

```kotlin
// feature/yourfeature/presentation/YourScreen.kt
@Composable
fun YourScreen(viewModel: YourViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // UI as pure function of state
}
```

### Step 6: Create ViewModel + UiState

```kotlin
@HiltViewModel
class YourViewModel @Inject constructor(
    private val useCase: YourUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(YourUiState())
    val uiState: StateFlow<YourUiState> = _uiState.asStateFlow()
}
```

### Step 7: Add Navigation Route

In `FoxNavHost.kt`:
```kotlin
composable("your_route") { YourScreen() }
```

### Step 8: Write Tests

```kotlin
class YourUseCaseTest {
    @Test
    fun `should calculate correctly`() {
        val useCase = YourUseCase()
        val result = useCase(testCandles)
        assertEquals(expected, result)
    }
}
```

### Step 9: Push and Create PR

```bash
git add .
git commit -m "feat: add your feature description"
git push origin feat/your-feature-name
# Create PR targeting main
```

---

## 7. Chart Engine Development

The chart is a custom Compose Canvas renderer — no third-party library.

### Architecture

```
CandleChart (Composable)
├── ChartViewport (camera: startIndex, visibleBars, priceHigh/Low)
├── Gesture Layer
│   ├── detectDragGestures → single-finger pan
│   └── detectTransformGestures → two-finger pinch zoom
├── Grid Renderer (nice 1-2-5 price levels)
├── Candle Renderer (viewport-culled loop)
└── Overlay Renderer (last-price line, annotations)
```

### Key Performance Rules

1. **Viewport culling**: Only draw candles where `index >= startIndex` and `index <= startIndex + visibleBars`. This bounds draw cost to ~120 bars regardless of total data size.

2. **No allocations in draw loop**: Pre-compute `Offset` values. Never create lists/objects inside `Canvas { }`.

3. **Snapshot state for recomposition**: The viewport is a plain class (not `mutableStateOf`). `invalidateTick` is bumped after gestures to trigger Canvas redraw.

4. **Gesture separation**: Single-finger drag uses `detectDragGestures`. Two-finger pinch uses `detectTransformGestures`. Both are separate `pointerInput` modifiers so they don't conflict.

### Adding Chart Overlays

To draw market structure, indicators, or annotations on the chart:

```kotlin
// Inside Canvas { } block, after candle rendering:
for (brk in structureBreaks) {
    val x = viewport.xForIndex(brk.index.toFloat(), w)
    val y = viewport.yForPrice(brk.price, h)
    // Draw BOS/CHOCH marker
    drawCircle(color = FoxAmber50, radius = 6f, center = Offset(x, y))
}
```

---

## 8. Domain Engine Development

### Non-Repainting Rule

**Critical**: All analysis engines MUST be non-repainting. This means:
- At bar index `i`, the engine may only read candles `[0..i]`
- Once a signal is confirmed at bar `i`, it must NEVER change when bar `i+1` arrives
- This is enforced in the backtest engine: `strategy(candles.subList(0, i + 1), i)`

### Adding a New Indicator

1. Add a function in `TechnicalIndicators.kt`:
```kotlin
fun calculateYourIndicator(candles: List<Candle>, period: Int): DoubleArray {
    val result = DoubleArray(candles.size)
    // ... computation ...
    return result
}
```

2. Test with various edge cases (empty, single candle, exactly `period` candles)

### Adding a New Strategy (for Backtesting)

```kotlin
val myStrategy: StrategyFunction = { candles, index ->
    if (index < 50) null  // Not enough data
    else {
        val rsi = TechnicalIndicators.calculateRSI(candles, 14)
        if (rsi[index] < 30) {
            StrategySignal(
                direction = Direction.BULLISH,
                entry = candles[index].close,
                stopLoss = candles[index].low - atr,
                takeProfit = candles[index].close + 2 * atr,
                index = index,
                timestamp = candles[index].timestamp,
            )
        } else null
    }
}

// Run backtest
val result = backtestEngine(candles, myStrategy, "EURUSD", Timeframe.M15)
println("Win rate: ${result.metrics.winRate}%")
println("Sharpe: ${result.metrics.sharpeRatio}")
```

---

## 9. Data Layer Development

### Adding a New API Endpoint

1. **Define the DTO** in `data/remote/dto/`:
```kotlin
@Serializable
data class YourDto(val field: String)
```

2. **Add to Retrofit interface** in `data/remote/api/`:
```kotlin
@GET("your/endpoint")
suspend fun fetchData(): List<YourDto>
```

3. **Create mapper** in `data/mapper/`:
```kotlin
fun YourDto.toDomain(): YourModel = YourModel(field = this.field)
```

4. **Add to repository implementation**

### Adding a New Room Table

1. **Define entity** in `data/local/entity/`:
```kotlin
@Entity(tableName = "your_table")
data class YourEntity(
    @PrimaryKey val id: String,
    val value: Double,
)
```

2. **Define DAO** in `data/local/dao/`:
```kotlin
@Dao
interface YourDao {
    @Query("SELECT * FROM your_table")
    fun observeAll(): Flow<List<YourEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<YourEntity>)
}
```

3. **Register in `FoxDatabase`**:
```kotlin
@Database(entities = [CandleEntity::class, YourEntity::class], version = 2)
abstract class FoxDatabase : RoomDatabase() {
    abstract fun yourDao(): YourDao
}
```

4. **Provide DAO from `DatabaseModule`**:
```kotlin
@Provides
fun provideYourDao(db: FoxDatabase): YourDao = db.yourDao()
```

---

## 10. Testing

### Unit Tests

Located at `app/src/test/java/com/foxtrader/app/`.

```bash
# Run all unit tests
./gradlew :app:testDebugUnitTest

# Run a specific test class
./gradlew :app:testDebugUnitTest --tests "*AnalyzeMarketStructureUseCaseTest"
```

### What to Test

| Layer | What to test | How |
|-------|-------------|-----|
| Domain models | Value equality, computed properties | Plain JUnit |
| Use cases | Business logic correctness | JUnit + test data |
| ViewModels | State transitions, event handling | JUnit + Turbine + MockK |
| Mappers | Correct field mapping | Plain JUnit |

### Testing a Use Case (Example)

```kotlin
class AnalyzeMarketStructureUseCaseTest {
    private val useCase = AnalyzeMarketStructureUseCase()

    @Test
    fun `bullish BOS detected when high exceeds previous swing high`() {
        val candles = buildTestCandles(trend = "up", size = 100)
        val result = useCase(candles)
        assertEquals(Bias.BULLISH, result.bias)
        assertTrue(result.breaks.any { it.type == BreakType.BOS })
    }
}
```

### Testing a ViewModel (Example)

```kotlin
@Test
fun `refresh updates loading state`() = runTest {
    val viewModel = ChartViewModel(mockRepository, mockUseCase)
    viewModel.uiState.test {
        val initial = awaitItem()
        assertTrue(initial.isLoading)
    }
}
```

---

## 11. CI / CD Pipeline

### Workflow File

`.github/workflows/android.yml` runs on:
- Push to `main`, `feat/**`, `fix/**` branches
- Pull requests targeting `main`
- Manual trigger (workflow_dispatch)

### Pipeline Steps

```
1. Checkout code
2. Set up JDK 17 (Temurin)
3. Set up Android SDK
4. Set up Gradle (uses committed wrapper)
5. Build debug APK
6. Upload APK artifact (30-day retention)
7. Run unit tests
8. Upload test reports
```

### CI Reliability Measures

- **Committed Gradle wrapper**: No runtime wrapper bootstrap
- **Configuration cache disabled**: Prevents Hilt/KSP cold-cache failures
- **`--no-daemon` flag**: Prevents stale daemon issues on shared runners
- **Concurrency group**: Cancels previous runs when new code is pushed
- **30-minute timeout**: Prevents stuck builds

---

## 12. Code Style & Conventions

### Kotlin Style

- **Official Kotlin coding conventions** (IntelliJ/Android Studio defaults)
- 4-space indentation
- Trailing commas in multi-line declarations
- `private` by default; only expose what's needed
- `val` over `var` everywhere possible
- `data class` for immutable value types

### Naming Conventions

| Type | Convention | Example |
|------|-----------|---------|
| Class | PascalCase | `ChartViewModel` |
| Function | camelCase | `calculateEMA()` |
| Constant | SCREAMING_SNAKE | `MAX_VISIBLE_BARS` |
| Package | lowercase | `com.foxtrader.app.domain` |
| File | PascalCase matching class | `ChartViewModel.kt` |
| Composable | PascalCase (noun) | `CandleChart()` |

### Architecture Rules

1. **Domain layer is framework-free** — no `android.*` imports, no Compose, no Hilt annotations (except `@Inject`)
2. **One ViewModel per screen** — no shared ViewModels across features
3. **UiState is a single immutable data class** — never expose multiple state flows
4. **Use cases are single-responsibility** — one public `invoke()` or method per class
5. **Repository is the single source of truth** — UI observes Room, never caches separately
6. **Mappers are pure functions** — no side effects, no network calls

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add backtesting lab UI screen
fix: prevent chart crash on empty candle list
refactor: extract viewport logic to ChartViewport class
ci: add unit test step to workflow
docs: update DEVELOPMENT.md with testing guide
```

---

## 13. Dependency Management

### Version Catalog

All versions live in `gradle/libs.versions.toml`. **Never** hardcode versions in `build.gradle.kts`.

### Adding a New Dependency

1. Add version in `[versions]` section:
```toml
newLib = "1.2.3"
```

2. Add library in `[libraries]` section:
```toml
new-lib = { group = "com.example", name = "new-lib", version.ref = "newLib" }
```

3. Use in `app/build.gradle.kts`:
```kotlin
implementation(libs.new.lib)
```

### Key Dependency Versions (v0.1.0)

| Dependency | Version | Notes |
|-----------|---------|-------|
| AGP | 8.5.2 | Android Gradle Plugin |
| Kotlin | 2.0.20 | Includes Compose compiler |
| KSP | 2.0.20-1.0.25 | For Room + Hilt annotation processing |
| Compose BOM | 2024.09.03 | Manages all Compose library versions |
| Material3 | 1.3.0 | Latest stable with PullToRefresh |
| Hilt | 2.52 | Dagger-based DI |
| Room | 2.6.1 | SQLite abstraction |
| Navigation | 2.8.1 | Compose navigation |
| Coroutines | 1.9.0 | Async + Flow |

---

## 14. Common Issues & Troubleshooting

### Build Fails with "Configuration Cache" Error

**Cause:** Hilt/KSP plugins have known configuration cache incompatibilities.
**Fix:** Already disabled in `gradle.properties`. If someone re-enables it, set:
```properties
org.gradle.configuration-cache=false
```

### "Unresolved reference" After Adding a New Hilt Module

**Cause:** KSP hasn't generated the Dagger components yet.
**Fix:** Run:
```bash
./gradlew :app:kspDebugKotlin
```

### Room Schema Changed — Migration Error

**Cause:** Added/modified a `@Entity` without providing a migration.
**Fix:** For debug builds, use `fallbackToDestructiveMigration()`. For release, write a proper `Migration(oldVersion, newVersion)`.

### Chart Not Responding to Touch

**Cause:** A parent composable is consuming touch events (e.g., `verticalScroll`).
**Fix:** Ensure the `CandleChart` has its own `pointerInput` modifiers and that gestures call `change.consume()`.

### Compose Preview Not Working

**Cause:** Hilt ViewModels can't be instantiated in preview.
**Fix:** Create preview-specific composables that pass data directly:
```kotlin
@Preview
@Composable
private fun ChartPreview() {
    CandleChart(candles = sampleCandles())
}
```

### Gradle Sync Takes Forever

**Fix:**
```bash
# Kill the daemon and retry
./gradlew --stop
./gradlew :app:assembleDebug --no-daemon
```

---

## 15. Release Process

### Version Numbering

Follows [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`

| Change | Example |
|--------|---------|
| Breaking change | 1.0.0 → 2.0.0 |
| New feature | 0.1.0 → 0.2.0 |
| Bug fix | 0.1.0 → 0.1.1 |

### Cutting a Release

1. **Update version** in `app/build.gradle.kts`:
```kotlin
versionCode = 2          // Increment by 1
versionName = "0.2.0"   // Semantic version
```

2. **Commit and push to main**:
```bash
git commit -am "release: v0.2.0"
git push origin main
```

3. **Create a GitHub Release**:
- Go to Releases > Draft a new release
- Tag: `v0.2.0` targeting `main`
- Title: `v0.2.0 — Feature Name`
- Attach the `foxtrader-debug-apk` artifact from the CI run
- Publish

4. **For Play Store** (future):
- Build a signed release APK/AAB
- Upload to Google Play Console

---

## Questions?

Open an issue on GitHub or check the existing [pull requests](https://github.com/hamahasan441-png/Foxtrader/pulls) for context on past decisions.
