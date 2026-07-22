# FoxTrader — Institutional AI Trading Platform

> Enterprise-grade Smart Money trading intelligence with multi-AI agent architecture, built on TradingView Lightweight Charts.

## Architecture

**~24,000 lines of TypeScript** across 88+ source files implementing:

### Part 1 — Core Analysis Engine
- **Market Structure** — BOS, CHOCH, MSS, IDM, Internal/External/Swing/Fractal
- **Liquidity** — BSL, SSL, EQH, EQL, Pools, Sweeps, Engineered, Resting, Session
- **Order Blocks** — Bullish/Bearish OB, Mitigation, Breaker, Rejection, Flip Zones
- **Fair Value Gaps** — FVG, IFVG, BPR, Volume Imbalance, Liquidity Void
- **ICT Concepts** — OTE, Judas Swing, Kill Zones, Silver Bullet, AMD, Turtle Soup, NDOG/WOG
- **LIT Trading** — Liquidity Trap, Inducement, Shift, Institutional Trap
- **SMT Module** — Divergence, Multi-symbol Scanner, Correlation, Multi-TF
- **Sessions** — Asian, London, New York, Sydney with live H/L
- **Data Providers** — Dukascopy (primary) + Binance, OANDA, IB, Polygon, Twelve Data, Alpha Vantage
- **Chart Engine** — TradingView Lightweight Charts with multi-layout, replay mode
- **Non-Repainting Guard** — Validates all signals against look-ahead bias

### Part 2 — Advanced Trading
- **Execution Engine** — Market/Limit/Stop/StopLimit/OCO, partial TP, trailing stop, break-even, scaling, hedging
- **Risk Engine** — 6 sizing methods (Kelly, ATR, Volatility), drawdown protection, correlation monitoring
- **AI Probability Engine** — 10-dimension scoring (0-100 confidence with grades)
- **Confluence Engine** — 19 weighted factors (BOS+CHOCH+OB+FVG+SMT+LIT+OTE+KZ+VWAP+EMA+ADX+Volume...)
- **Market Scanner** — Ranked opportunities (Best Buy/Sell/Swing/Scalp)
- **AI Trade Planner** — Complete plan: bias, entry, SL, TP1/2/3, RR, reasoning
- **Trade Journal** — Auto-save with AI improvement suggestions
- **Backtester** — Tick data, spread, commission, slippage, Sharpe/Sortino/Calmar
- **Monte Carlo** — Risk-of-ruin analysis, probability distributions
- **Walk-Forward** — Out-of-sample robustness validation
- **AI Optimizer** — Genetic/Grid/Random with overfit detection
- **News Module** — Economic calendar with AI impact analysis
- **Heatmaps** — Forex/Crypto/Stock/Sector + 6 strength meters
- **Voice Assistant** — Web Speech API commands + TTS alerts
- **Cloud Sync** — Cross-device with conflict resolution
- **Security** — AES-256-GCM, WebAuthn biometric, certificate pinning

### Part 3 — Enterprise & Multi-AI Agents
- **10 AI Agents** — Market Structure, Smart Money, ICT, LIT, Volume, Trend, Risk, News, Psychology, Strategy
- **Agent Orchestrator** — Phase-based coordination with inter-agent communication
- **Master Decision Engine** — 9 required confluences (no single-indicator signals)
- **Multi-Timeframe Engine** — MN→M1 simultaneous analysis, automatic HTF bias
- **Auto Drawing Engine** — 12 automatic drawing types (S/R, trendlines, Fib, OB, FVG, OTE, POI)
- **Pattern Recognition** — 18 classic + 7 harmonic (Gartley/Bat/Butterfly/Crab/Cypher/Shark)
- **Candle Analysis** — 28 patterns with meaning, probability, context
- **Screener** — 30 symbols across 7 asset classes
- **Alert Engine** — 6 channels (Desktop/Push/Webhook/Telegram/Email/Mobile)
- **Broker Connectivity** — Binance, Bybit, Alpaca adapters (modular plugin pattern)
- **Database** — PostgreSQL + TimescaleDB + Redis schemas
- **API** — 45 REST endpoints + 3 WebSocket channels + JWT/OAuth2
- **Logging** — Structured, leveled, crash reporting
- **Testing** — 40+ test cases across 6 categories (Unit/Integration/Stress/Security/Performance/Load)

## Key Design Principles

1. **No Repainting** — Every signal uses only confirmed past data
2. **No Single-Indicator Signals** — Master Decision Engine requires 5+ of 9 confluences
3. **Multi-Agent Architecture** — 10 specialized AI agents with phased execution
4. **60 FPS Performance** — TradingView Lightweight Charts native rendering
5. **Non-Repainting Enforcement** — SafeCandleAccessor prevents look-ahead at every layer
6. **Responsible Design** — No unrealistic accuracy claims; historical/simulated testing only

## Technology Stack

- **Language:** TypeScript (strict mode)
- **Frontend:** Vite + TradingView Lightweight Charts
- **Database:** PostgreSQL + TimescaleDB + Redis
- **Auth:** JWT + OAuth2 + WebAuthn + PIN
- **Brokers:** Binance, Bybit, Alpaca (+ stubs for OKX/OANDA/IB)
- **Data:** Dukascopy tick data (primary) + 6 additional providers with failover
- **Security:** AES-256-GCM, PBKDF2, certificate pinning, anti-debug

## Getting Started

```bash
npm install
npm run dev
```

## Disclaimer

This platform is designed for educational and analytical purposes. It helps users analyze markets and evaluate strategies using historical and simulated testing. Past performance does not guarantee future results. Trade responsibly.
