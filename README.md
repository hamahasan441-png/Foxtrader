# FoxTrader — Institutional AI Trading (Android)

A native Android trading-analysis app built with **Kotlin, Jetpack Compose,
Material 3, MVVM + Clean Architecture, Hilt, and Room**, backed by a
FastAPI / PostgreSQL / Redis service.

> Educational & analytical tool. It helps you analyze markets and evaluate
> strategies using historical and simulated data. It does **not** promise
> future results or guaranteed profit.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture (domain / data / presentation) |
| DI | Hilt |
| Local DB | Room (offline-first single source of truth) |
| Networking | Retrofit + OkHttp + kotlinx.serialization |
| Async | Coroutines + Flow |
| Chart | Hardware-accelerated Compose Canvas (viewport-culled) |
| Min SDK | 29 (Android 10+) · Target 34 |

## Module Structure

```
app/src/main/java/com/foxtrader/app/
├── FoxTraderApp.kt            # @HiltAndroidApp
├── MainActivity.kt            # Single-activity + Compose
├── ui/theme/                  # Fox Design System (Color, Type, Theme)
├── ui/navigation/             # FoxNavHost
├── di/                        # Hilt modules (DB, Network, Repository, Dispatchers)
├── domain/                    # Pure Kotlin — models, use cases, repo interfaces
│   ├── model/                 # Candle, MarketStructure, Bias…
│   ├── usecase/               # AnalyzeMarketStructureUseCase (non-repainting)
│   └── repository/            # MarketRepository (interface)
├── data/                      # Room + Retrofit + mappers + repo impl
│   ├── local/                 # FoxDatabase, DAO, entities
│   ├── remote/                # MarketApi, DTOs
│   └── repository/            # MarketRepositoryImpl (offline-first)
└── feature/chart/             # Chart feature (ViewModel, UiState, Composables)
```

## Building the APK

### Option 1 — GitHub Actions (recommended, zero local setup)
Every push to `main` triggers **`.github/workflows/android.yml`**, which builds
a real debug APK on GitHub's runners and uploads it as an artifact.

1. Go to the repo's **Actions** tab
2. Open the latest **Android CI — Build APK** run
3. Download the **`foxtrader-debug-apk`** artifact
4. Install the `.apk` on any Android 10+ device

### Option 2 — Local build (Android Studio or CLI)
Requires JDK 17 + Android SDK.

```bash
# Generate the Gradle wrapper once (needs a local gradle install)
gradle wrapper --gradle-version 8.9

# Build the debug APK
./gradlew :app:assembleDebug

# APK output:
#   app/build/outputs/apk/debug/app-debug.apk

# Run unit tests
./gradlew :app:testDebugUnitTest
```

## Status

Foundation is in place and building via CI:
- ✅ Gradle project (version catalog, Hilt/Compose/Room/Retrofit)
- ✅ Fox Design System (Material 3 theme)
- ✅ Clean Architecture with a working Chart feature end-to-end
- ✅ Non-repainting market-structure analysis (BOS/CHOCH) with unit tests
- ✅ Hardware-accelerated candlestick chart (pan/pinch, viewport culling)
- ✅ CI builds a real installable APK

Roadmap: full SMC/ICT/LIT engines, backtesting lab, scanner, alerts,
FastAPI backend, WebSocket live data — ported incrementally from the
reference engine in `reference/typescript-src/`, keeping every commit buildable.
