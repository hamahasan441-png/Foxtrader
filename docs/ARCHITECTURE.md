# ARCHITECTURE — Institutional AI Trading Platform

## Overview

Production-ready, enterprise-grade trading platform with multi-AI agent
architecture, designed to rival TradingView, Bookmap, and NinjaTrader.
Scalable to millions of users.

## Part 1 — Core Analysis Engine (src/core, src/modules, src/engine)

| Module | Path | Purpose |
|--------|------|---------|
| Core Types | core/types.ts | 500+ type definitions |
| Event Bus | core/event-bus.ts | High-perf pub/sub |
| Non-Repainting | core/non-repainting.ts | Signal validation |
| Market Structure | modules/market-structure | BOS/CHOCH/MSS/IDM |
| Liquidity | modules/liquidity | BSL/SSL/EQH/Pools |
| Order Blocks | modules/order-blocks | OB/Breaker/Mitigation |
| Fair Value Gaps | modules/fair-value-gaps | FVG/IFVG/BPR/VI/LV |
| ICT Concepts | modules/ict-concepts | OTE/KZ/AMD/Turtle |
| LIT Trading | modules/lit-trading | Trap/Inducement/Shift |
| SMT | modules/smt | Divergence/Correlation |
| Sessions | modules/sessions | Asian/London/NY/Sydney |
| Templates | modules/templates | 10 preloaded templates |
| Data Providers | modules/data-provider | 7 providers + WS feed |
| Scanner | modules/scanner | Real-time multi-signal |
| AI Assistant | modules/ai-assistant | Setup analysis + scoring |
| Chart Engine | engine/chart-engine.ts | TradingView LWC |
| Visualization | engine/visualization.ts | Analysis → annotations |

## Part 2 — Advanced Trading (src/execution, src/risk, src/ai, etc.)

| Module | Path | Purpose |
|--------|------|---------|
| Execution Engine | execution/ | All order types + scaling |
| Risk Engine | risk/ | 6 sizing methods + halts |
| Probability Engine | ai/probability-engine | 10 scores, 0-100 grade |
| Confluence Engine | ai/confluence-engine | 19 weighted factors |
| Market Scanner | ai/market-scanner | Ranked opportunities |
| Trade Planner | ai/trade-planner | Full trade plan gen |
| Mentor Assistant | ai/mentor-assistant | NLP Q&A like a mentor |
| Trade Journal | journal/ | Auto-save + AI insights |
| Replay Engine | replay/ | Tick-Daily + commentary |
| Backtester | backtest/backtester | Spread/slip/commission |
| Monte Carlo | backtest/monte-carlo | Risk-of-ruin analysis |
| Walk-Forward | backtest/monte-carlo | OOS robustness |
| Optimizer | backtest/optimizer | Grid/Random/Genetic |
| News Module | news/ | Calendar + AI impact |
| Heatmaps | analytics/ | 4 heatmaps + 6 meters |
| Voice Assistant | voice/ | Web Speech API |
| Cloud Sync | sync/ | 7 syncable types |
| Customization | customization/ | Themes/shortcuts/gestures |
| Security | security/ | AES-256/WebAuthn/anti-debug |

## Part 3 — Enterprise (src/agents, src/mtf, src/patterns, etc.)

| Module | Path | Purpose |
|--------|------|---------|
| 10 AI Agents | agents/impl/ | Specialized analysis |
| Orchestrator | agents/orchestrator | Phase-based coordination |
| Decision Engine | agents/master-decision | 9-confluence gating |
| MTF Engine | mtf/ | MN→M1 simultaneous |
| Auto Drawing | drawing/ | 12 auto drawing types |
| Classic Patterns | patterns/classic | H&S/Triangle/Wedge/Flag |
| Harmonic Patterns | patterns/harmonic | ABCD/Gartley/Bat/Crab |
| Candle Patterns | patterns/candle | 28 patterns + meaning |
| Screener | screener/ | 30 symbols, 7 classes |
| Alert Engine | alerts/ | 6 channels + rate limit |
| Broker Registry | brokers/ | Binance/Bybit/Alpaca |
| Database | database/ | PG + TimescaleDB + Redis |
| API Layer | api/ | 45 REST + 3 WS channels |
| Logger | logging/ | Structured + crash report |
| Tests | testing/ | 6 test categories defined |


## Signal Flow (Critical Path)

```
Market Data (Dukascopy/Binance/OANDA)
    ↓
Non-Repainting Guard (validates, rejects future data)
    ↓
Multi-Timeframe Engine (MN → M1 simultaneous analysis)
    ↓
Agent Orchestrator (10 specialized AI agents in 3 phases)
    ├─ Phase 1: Market Structure, Volume, Trend, News
    ├─ Phase 2: Smart Money, ICT, LIT, Risk, Psychology
    └─ Phase 3: Strategy Agent (combines all)
    ↓
Master Decision Engine (9 required confluences)
    ↓ (only if approved: 5+ confluences, 60%+ confidence)
Probability Engine (10-dimension score) + Confluence Engine (19 factors)
    ↓
Trade Planner (Entry/SL/TP1-3/RR/Reasoning)
    ↓
Risk Engine (position sizing, daily/weekly limits, correlation check)
    ↓
Execution Engine (order placement, broker adapter)
    ↓
Auto-Journal (screenshot, indicators, emotion tracking)
    ↓
Alert Engine (push/desktop/telegram/webhook)
```

## Key Design Principles

1. **No Repainting** — Every signal uses only confirmed past data.
   SafeCandleAccessor restricts look-ahead. Validated at every layer.

2. **No Single-Indicator Signals** — Master Decision Engine requires
   minimum 5 of 9 confluences (Sweep + BOS/CHOCH + FVG + OB + SMT +
   Session + HTF Bias + Trend + Volume).

3. **Multi-Agent Architecture** — 10 specialized agents with
   inter-agent communication. Phase-based execution ensures
   foundational analysis (structure, trend) informs higher-level
   agents (LIT, strategy).

4. **60 FPS Performance** — TradingView Lightweight Charts handles
   rendering natively. Dirty-checking, ring buffers, object pools
   minimize GC pressure.

5. **Modular Broker Connectivity** — Plugin pattern via BrokerAdapter
   interface. New brokers added without touching core code.

6. **Enterprise Security** — AES-256-GCM encryption, WebAuthn
   biometric, PBKDF2 PIN hashing, certificate pinning, anti-debug,
   root/emulator detection.

7. **Scalable Data Layer** — TimescaleDB hypertables with automatic
   partitioning + retention policies. Redis for real-time cache and
   pub/sub. PostgreSQL for relational data.

## Deployment Architecture

```
┌─────────────────┐     ┌──────────────┐     ┌────────────────┐
│   Client (Web)  │◄───►│  API Server  │◄───►│  PostgreSQL +  │
│  LWC + Vite SPA │     │  (REST + WS) │     │  TimescaleDB   │
└─────────────────┘     └──────┬───────┘     └────────────────┘
                               │
                    ┌──────────┼──────────┐
                    │          │          │
              ┌─────▼───┐ ┌───▼────┐ ┌───▼──────────┐
              │  Redis   │ │ Worker │ │ Data Ingest  │
              │  Cache   │ │ (Agents│ │ (Dukascopy/  │
              │  Pub/Sub │ │ /Back  │ │  Binance WS) │
              └──────────┘ │  test) │ └──────────────┘
                           └────────┘
```

## File Count Summary

| Part | Files | Lines (approx) |
|------|-------|----------------|
| Part 1 | 53 | ~18,700 |
| Part 2 | 20 | ~8,500 |
| Part 3 | 28 | ~7,000 |
| **Total** | **~101** | **~34,200** |

## Technology Stack

- **Frontend:** TypeScript, Vite, TradingView Lightweight Charts
- **Backend:** Node.js / TypeScript (isomorphic modules)
- **Database:** PostgreSQL + TimescaleDB + Redis
- **Auth:** JWT + OAuth2 + WebAuthn + PIN
- **Brokers:** Binance, Bybit, Alpaca (OKX/OANDA/IB stubs)
- **Alerts:** Web Notifications, Telegram Bot, Webhooks, Email
- **Voice:** Web Speech API (recognition + synthesis)
- **Security:** AES-256-GCM, PBKDF2, certificate pinning
