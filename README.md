# FoxTrader v0.1.0

**Institutional-grade AI trading analysis for Android.**

A native Android trading-analysis app built with **Kotlin 2.0, Jetpack Compose, Material 3, MVVM + Clean Architecture, Hilt, and Room**. Designed for serious traders who want institutional-quality tools on their phone.

> **Disclaimer:** FoxTrader is an educational and analytical tool. It helps you analyze markets and evaluate strategies using historical and simulated data. It does **not** promise future results or guaranteed profit.

---

## Screenshots

| Chart (Dark) | Scanner | Settings |
|:---:|:---:|:---:|
| GPU candlestick chart with pan/pinch | Multi-asset screener with scores | Timeframe, risk, theme settings |

---

## Features

### Chart Engine
- Hardware-accelerated candlestick chart (Compose Canvas on GPU RenderThread)
- Single-finger drag to pan, two-finger pinch to zoom
- Viewport culling (only visible bars drawn — handles 100,000+ candles at 120fps)
- Auto-scaling price axis with institutional grid lines (1-2-5 progression)
- Live last-price dashed reference line
- 9 timeframes: 1m, 5m, 15m, 30m, 1H, 4H, 1D, 1W, 1M

### Market Structure Analysis (Non-Repainting)
- Break of Structure (BOS) detection
- Change of Character (CHOCH) detection
- Directional bias engine (BULLISH / BEARISH / NEUTRAL)
- Zero look-ahead — only uses confirmed past data

### Technical Indicators
- EMA, SMA, VWAP
- RSI, MACD, ADX (+DI/-DI)
- ATR, Momentum, Relative Volume, Volatility

### Risk Management Engine
- 6 position sizing methods: Fixed Lots, Fixed Risk, Percentage, Kelly Criterion, ATR-Based, Volatility
- 4 stop-loss calculation methods: Fixed, ATR, Volatility, Structure
- Pre-trade risk gating: daily/weekly loss limits, max drawdown, consecutive loss protection
- Auto-halt with configurable thresholds
- Kelly Criterion estimation from trade history

### Backtesting Engine
- Bar-by-bar execution with NO look-ahead bias
- Variable spread modeling (widens with volatility)
- Commission and slippage simulation
- Full metrics: Sharpe, Sortino, Calmar, Profit Factor, Win Rate, Max Drawdown, Expectancy, R-Multiples
- Equity curve with per-bar drawdown tracking

### Multi-Asset Scanner
- 30 pre-configured watchlist symbols (Forex, Crypto, Stocks, Indices, Metals, Energy)
- Composite scoring: trend strength + momentum + volatility + setup quality
- Category badges: Best Buy, Best Sell, Best Swing, Best Scalp, Best Long-Term
- Tag system: TRENDING, OVERBOUGHT, OVERSOLD, HIGH_VOL, MOVER

### Alert System
- Priority-based filtering (LOW, MEDIUM, HIGH, CRITICAL)
- Cooldown deduplication
- Hourly rate limiting
- Acknowledgment tracking

### Navigation & UX
- Bottom navigation bar (Chart, Scanner, Settings)
- Interactive timeframe selector with tappable chips
- Pull-to-refresh for live data reload
- Fox Design System (dark-first Material 3 theme with amber accent)
- Edge-to-edge display

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 (JVM target 17) |
| UI Framework | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture (domain / data / presentation) |
| DI | Hilt (Dagger) |
| Local Database | Room (offline-first single source of truth) |
| Networking | Retrofit + OkHttp + kotlinx.serialization |
| Async | Kotlin Coroutines + Flow |
| Chart Rendering | Hardware-accelerated Compose Canvas (viewport-culled) |
| Build System | Gradle 8.9 + Version Catalog |
| CI/CD | GitHub Actions (APK build + unit tests) |
| Min SDK | 29 (Android 10+) |
| Target SDK | 34 (Android 14) |

---

## Quick Start

### Download the APK (Easiest)

1. Go to the repo's **Actions** tab
2. Open the latest **Android CI - Build APK** run (green check)
3. Download the **`foxtrader-debug-apk`** artifact
4. Install the `.apk` on any Android 10+ device

### Build Locally

Requires **JDK 17** + **Android SDK** (API 34).

```bash
# Clone the repo
git clone https://github.com/hamahasan441-png/Foxtrader.git
cd Foxtrader

# Build the debug APK
./gradlew :app:assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Run unit tests
./gradlew :app:testDebugUnitTest
```

### Alpha Vantage Provider Setup

FoxTrader now supports **Alpha Vantage** as a chart data provider for historical candles.

1. In app settings, choose **Data Provider → Alpha Vantage**.
2. Paste your key into **Alpha Vantage API Key** and tap **Save Settings**.

Official API documentation: https://www.alphavantage.co/documentation/

### Open in Android Studio

1. Open Android Studio (Hedgehog or newer)
2. File > Open > select the `Foxtrader` directory
3. Wait for Gradle sync to complete
4. Run on emulator or device (API 29+)

---

## Project Structure

```
Foxtrader/
├── app/
│   ├── build.gradle.kts          # App module build config
│   ├── proguard-rules.pro        # R8 minification rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/foxtrader/app/
│       │   │   ├── FoxTraderApp.kt              # @HiltAndroidApp entry point
│       │   │   ├── MainActivity.kt              # Single-activity Compose host
│       │   │   ├── ui/
│       │   │   │   ├── theme/                   # Fox Design System (Color, Type, Theme)
│       │   │   │   └── navigation/              # Bottom nav + NavHost
│       │   │   ├── di/                          # Hilt DI modules
│       │   │   │   ├── DatabaseModule.kt        # Room database provider
│       │   │   │   ├── NetworkModule.kt         # Retrofit + OkHttp
│       │   │   │   ├── RepositoryModule.kt      # Repository bindings
│       │   │   │   └── DispatcherModule.kt      # Coroutine dispatchers
│       │   │   ├── domain/                      # Pure Kotlin (no Android deps)
│       │   │   │   ├── model/                   # Candle, MarketStructure, Bias, Risk...
│       │   │   │   ├── repository/              # MarketRepository interface
│       │   │   │   └── usecase/                 # Business logic
│       │   │   │       ├── AnalyzeMarketStructureUseCase.kt
│       │   │   │       ├── MultiTimeframeAnalysisUseCase.kt
│       │   │   │       ├── indicators/          # EMA, RSI, MACD, ATR, ADX, VWAP...
│       │   │   │       ├── risk/                # Position sizing, SL, risk gating
│       │   │   │       ├── backtest/            # Bar-by-bar backtester
│       │   │   │       ├── scanner/             # Multi-asset screener
│       │   │   │       ├── alerts/              # Alert engine
│       │   │   │       └── patterns/            # Candle pattern detector
│       │   │   ├── data/                        # Android/framework layer
│       │   │   │   ├── local/                   # Room DB, DAO, entities
│       │   │   │   ├── remote/                  # Retrofit API, DTOs
│       │   │   │   ├── mapper/                  # Entity <-> Domain mappers
│       │   │   │   ├── repository/              # MarketRepositoryImpl (offline-first)
│       │   │   │   └── alerts/                  # AlertDispatcher (Android notifications)
│       │   │   └── feature/                     # Feature screens
│       │   │       ├── chart/presentation/      # ChartScreen, ViewModel, CandleChart
│       │   │       ├── scanner/presentation/    # ScannerScreen, ViewModel
│       │   │       └── settings/presentation/   # SettingsScreen, ViewModel
│       │   └── res/                             # Android resources (drawables, values)
│       └── test/                                # Unit tests
├── gradle/
│   ├── libs.versions.toml                       # Version catalog (single source of truth)
│   └── wrapper/                                 # Gradle wrapper (committed)
├── reference/                                   # TypeScript porting source (not built)
│   └── typescript-src/                          # Original WebGL2 engine for Kotlin port
├── .github/workflows/android.yml                # CI: Build APK + run tests
├── build.gradle.kts                             # Root build script
├── settings.gradle.kts                          # Project settings
├── gradle.properties                            # Build performance config
├── gradlew / gradlew.bat                        # Gradle wrapper scripts
└── resources/icon.svg                           # App icon source
```

---

## CI / CD

Every push to `main` or any `feat/**` / `fix/**` branch triggers the **Android CI** workflow:

1. Checks out code
2. Sets up JDK 17 + Android SDK
3. Builds the debug APK (`./gradlew :app:assembleDebug`)
4. Uploads APK as a downloadable artifact
5. Runs unit tests (`./gradlew :app:testDebugUnitTest`)
6. Uploads test reports

The APK artifact is retained for 30 days.

---

## Roadmap

- [ ] Live WebSocket data feed (Binance, Bybit)
- [ ] Full SMC/ICT engine (order blocks, fair value gaps, liquidity sweeps)
- [ ] LIT (Liquidity-Inducement Theory) agent
- [ ] Backtesting Lab UI screen
- [ ] Trade journal with statistics
- [ ] Push notification alerts
- [ ] FastAPI backend (PostgreSQL + Redis)
- [ ] Multi-timeframe confluence overlay on chart
- [ ] Social / copy-trading features
- [ ] Release on Google Play Store

---

## Contributing

1. Fork the repository
2. Create a feature branch (`feat/your-feature`)
3. Make your changes
4. Run tests: `./gradlew :app:testDebugUnitTest`
5. Push and open a Pull Request

See [DEVELOPMENT.md](DEVELOPMENT.md) for detailed development guide.

---

## License

This project is for educational purposes. See LICENSE file for details.

---

**Built with precision by Fox.**
