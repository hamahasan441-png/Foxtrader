# FoxTrader — Engineering Research & Competitive Benchmark

**Purpose:** Extract engineering *principles* (never code or proprietary algorithms) from professional trading platforms and top-tier open-source projects, benchmark FoxTrader against them, and decide where FoxTrader should be **redesigned with original implementations**.
**Method:** Public documentation, architecture write-ups, and engineering blogs. All content paraphrased for licensing compliance. Sources linked inline.
**Scope guard:** We extract *ideas* — architecture, workflow, rendering, UX, strategy organization, risk concepts, modular design. No proprietary code, indicators, or algorithms were copied.

---

## 1. What the professionals do (principles only)

### 1.1 TradingView — Lightweight Charts (rendering + data model)
- **Canvas over DOM.** High-performance financial charts render to an HTML5 canvas rather than manipulating DOM/SVG nodes, so thousands of bars and multiple ticks/second stay responsive. ([TradingView Lightweight Charts](https://www.tradingview.com/lightweight-charts/), [deepwiki overview](https://deepwiki.com/tradingview/lightweight-charts))
- **Pixel-perfect device mapping.** Their 4.0 rewrite adopted device-pixel-content-box measurement to render crisply across DPRs. ([TradingView blog, LWC 4.0](https://www.tradingview.com/blog/en/release-notes-for-lightweight-charts-4-0-36498/))
- **Plugin/primitive extensibility.** Custom series and drawing "primitives" attach to a pane and are handed the coordinate system to draw themselves — a clean seam for extension without forking the core. ([LWC skill notes](https://github.com/tradingview/lightweight-charts/blob/master/.github/skills/lightweight-charts/SKILL.md))
- **Designed for huge arrays** with incremental updates rather than full redraws. ([TradingView](https://www.tradingview.com/lightweight-charts/))

**FoxTrader vs. this:** FoxTrader's `ChartViewport` + `layers/` architecture already embodies canvas-style rendering, DPR-aware drawing, culling, and incremental camera math — **on par**. Where FoxTrader is *behind*: it has no first-class **drawing/series "primitive" plugin seam handed the coordinate system**; renderers are internal composables. *Redesign opportunity (medium):* formalize a `ChartPrimitive` interface that receives the viewport transform, so indicators/overlays/drawings are pluggable and independently testable.

### 1.2 Bookmap / ATAS — order-flow & heatmap rendering
- **Event-driven, non-lagging data environment.** Order-flow tools model the *full history* of the limit order book as a time-indexed heatmap, not a single snapshot. ([ATAS](https://atas.net/atas-possibilities/heatmap/), [Bookmap guide](https://bookmap.com/blog/heatmap-in-trading-the-complete-guide-to-market-depth-visualization/))
- **GPU shading for data-dense visuals** is being pursued for the heatmap because the data volume exceeds comfortable CPU raster budgets. ([Bookmap forum](https://bookmap.com/forum/viewtopic.php?t=5083))
- **Consolidated workspace.** A recurring pain point they solve: traders otherwise juggle separate apps for heatmap, footprint, DOM, and replay. One integrated surface is a feature. ([ATAS comparison](https://atas.net/blog/best-heatmap-trading-software-2026/))

**FoxTrader vs. this:** FoxTrader has a `MarketHeatmap`/`MarketProfile` but **no true order-book/DOM or footprint** (retail crypto/forex feeds rarely expose full L2 for free — a data constraint, not an engineering gap). *Redesign opportunity (postpone):* if/when an L2 feed is available, model depth as a time-indexed structure and render it as a dedicated `ChartPrimitive` layer; consider a GPU path only if the CPU raster budget is exceeded. Do **not** speculatively build this now.

### 1.3 NinjaTrader / Sierra Chart / Quantower — platform architecture
- **Two-layer design: surface + developer platform.** A normal user sees charts/DOM/strategy-builder; underneath, the *same* market-data, charting, and order-execution engine is exposed to add-ons. Extensions run on the identical engine the platform uses itself. ([NinjaTrader AddOn overview](https://ninjatrader.com/support/helpguides/nt8/addon_development_overview.htm), [NinjaScript docs](https://docs.ninjatrader.com/ninjascript), [surface vs developer layer](https://discourse.ninjatrader.com/t/ninjatrader-has-a-surface-layer-and-a-developer-layer/6750))
- **Data-feed abstraction.** Sierra Chart connects to many feeds (its own plus Rithmic/CQG/Teton) behind one interface; the platform is feed-agnostic. ([pipflow guide](https://pipflow.com/forum/Thread-getting-started-with-sierra-chart-a-practical-guide-for-futures))
- **Native-language performance where it matters.** Sierra Chart is C++ end-to-end for latency-sensitive behavior. ([propfirmapp](https://propfirmapp.com/trading-tools/sierra-chart))
- **Workspace persistence + composable windows/tabs.** Add-ons follow a window→tab→page hierarchy with saved workspaces. ([NinjaTrader DOM thread](https://discourse.ninjatrader.com/t/add-on-nt8-dom-built-with-claude-ama/6956))

**FoxTrader vs. this:** FoxTrader already has SDK registries (indicators, drawing tools) — a good start on the "developer layer." Where it's *behind*: the app **runs its analysis on a different path than any add-on would** (there is no single engine consumed by both app and extensions), and there is **no workspace persistence** (saved multi-chart layouts, per-symbol templates). *Redesign opportunities:* (a) **feed-agnostic provider interface** — FoxTrader has `DataProvider` + adapters, but the orphaned `data/market/*` engine fragments this; consolidate to *one* live path (see Master Plan T1.1). (b) **Workspace/template persistence** (medium, later phase).

### 1.4 MetaTrader / Thinkorswim / Interactive Brokers — workflow & risk
- **Contract specifications are first-class.** Every instrument carries exchange-defined tick size, tick value, and contract multiplier; these are the conversion rate between price and money. ([Optimus Futures](https://learn.optimusfutures.com/contract-specifications-and-values), [Schwab](https://www.schwab.com/futures/futures-contract))
- **Position sizing is derived, not guessed:** `Contracts = Risk Budget / (Stop Distance × Tick Value)`. ([Crosstrade](https://crosstrade.io/learn/risk-management/position-sizing), [QuantVPS cheatsheet](https://www.quantvps.com/blog/futures-tick-values/))
- **Tick value varies by asset class and contract** (e.g., an index micro future's tick ≠ an FX pair's pip). ([supertrade](https://supertrade.com/blog/points-pips-ticks-trading-guide/), [tradefundrr](https://tradefundrr.com/blog/tick-value-and-contract-specs-explained))

**FoxTrader vs. this — the key finding.** FoxTrader's `RiskEngine` hardcodes the **forex 100,000-unit standard lot** (`* 100_000`) in every sizing path. That is correct *only* for FX. For crypto, equities, indices, and metals — all supported — the position size and risk amount are wrong, which corrupts every downstream gate. **This is a confirmed correctness defect, and the professional standard above is exactly the fix.** *Redesign (P0, now): introduce an `InstrumentSpec` (asset class, contract size, tick size, tick/point value, quote currency) and derive sizing from `Risk Budget / (Stop Distance × value-per-unit)`.* → **Master Plan T0.1.**

### 1.5 Bloomberg Terminal — professional UX principles
- **Keyboard-command-first.** The Terminal is a command-driven environment (ticker → function → `<GO>`); expert throughput beats mouse-hunting. ([pineify](https://pineify.app/bloomberg-terminal/how-to-use-bloomberg-terminal), [Columbia guide](https://guides.library.columbia.edu/bloomberg/basic))
- **Customizable persistent workspace (Launchpad).** Users compose a permanent set of linked analytical tools tuned to their workflow. ([Bloomberg UX: Launchpad](https://www.bloomberg.com/ux/2017/11/10/relaunching-launchpad-disguising-ux-revolution-within-evolution/), [consistency](https://www.bloomberg.com/ux/2020/08/11/consistency-more-than-just-a-buzzword/))
- **Evolution, not disruption.** UI changes are rolled out so existing muscle-memory workflows are not broken. ([Bloomberg UX: change management](https://www.bloomberg.com/ux/2019/10/18/ux-and-change-management-bloombergs-4-guidelines-for-rolling-out-ui-product-updates/))
- **Color accessibility is a design requirement**, not an afterthought — critical for red/green P&L semantics. ([Bloomberg UX: color accessibility](https://www.bloomberg.com/ux/2021/10/14/designing-the-terminal-for-color-accessibility/))

**FoxTrader vs. this:** On mobile, keyboard-command UX doesn't map, but the **linked-workspace** and **color-accessibility** principles do. FoxTrader lacks **saved workspaces/templates** and its red/green semantics should be verified for color-vision deficiency (add a colorblind-safe palette option). *Redesign opportunities (later phase, UX):* workspace/template persistence; accessibility palette + content descriptions (already partly flagged as W11/T4.3).

### 1.6 LuxAlgo / Pine Script — strategy design philosophy
- **Non-repainting is the cardinal rule.** A signal must reflect only confirmed (closed-bar) data; real-time recalculation of past values makes backtests lie. ([TraderPost](https://blog.traderspost.io/article/how-to-avoid-repainting-in-pine-script), [Pineify](https://pineify.app/resources/blog/how-to-avoid-repainting-in-pine-script), [TradingView coders](https://my.tradingview.com/scripts/coders/))
- **Series/time-indexed model.** Indicators are pure functions over a bar-indexed series; the editor gives instant visual feedback on the chart. ([LuxAlgo Pine essentials](https://www.luxalgo.com/blog/pine-script-coding-essentials-for-traders/))
- **Workflow: chart structure → codified rules → validate/backtest → deploy.** AI accelerates *idea generation and screening*, but the human validates market logic; realistic costs (commissions, spread, slippage) must be modeled. ([LuxAlgo chart-to-code](https://www.luxalgo.com/blog/chart-to-code-turning-analysis-into-strategies/), [optimize with AI](https://www.luxalgo.com/blog/optimize-trading-strategies-with-ai/))
- **AI as assistant, not authority.** LuxAlgo positions AI to prototype/compare/refine, keeping the trader focused on market logic rather than debugging. ([LuxAlgo build strategies with AI](https://www.luxalgo.com/blog/how-to-use-ai-to-build-trading-strategies/))

**FoxTrader vs. this:** FoxTrader's `SmcDetector` is already strictly non-repainting, and `MasterDecisionEngine` keeps the LLM narration-only (AI-as-assistant) — **fully aligned, and ahead of many retail tools.** Gaps: the backtest engine should explicitly model **commissions/spread/slippage** as configurable inputs (verify coverage), and the strategy layer would benefit from LuxAlgo-style **workflow structure**: analysis → codified rule set → validate → deploy, with saved strategy definitions.

### 1.7 NautilusTrader / QuantConnect Lean / backtrader — engine architecture
- **Deterministic, event-driven core with backtest/live parity.** The *same* execution model runs in research and production with no code changes — actors, strategies, and execution algorithms are identical across backtest and live. ([NautilusTrader — why it exists](https://nautilustrader.io/blog/why-nautilustrader-exists/), [docs](https://nautilustrader.io/docs/latest/), [live parity](https://nautilustrader.io/docs/latest/concepts/live))
- **Message bus + cache + ports-and-adapters.** A backtest system is composed of engines, a Cache, a MessageBus, Portfolio, Actors, Strategies, and execution algorithms processing a historical data stream. ([NautilusTrader backtesting concepts](https://github.com/nautechsystems/nautilus_trader/blob/develop/docs/concepts/backtesting.md))
- **Same-timestamp settlement.** The engine drains queued venue commands and re-iterates matching so cascading orders (e.g., a hedge from `on_order_filled`) settle within the same timestamp — determinism as a first-class invariant. ([execution flow](https://github.com/nautechsystems/nautilus_trader/blob/develop/docs/concepts/backtesting/execution-flow.md))
- **Executable invariants.** Critical paths carry runtime assertions that verify behavior matches business requirements. ([architecture doc](https://github.com/nautechsystems/nautilus_trader/blob/develop/docs/concepts/architecture.md))
- **Modular plugin architecture (Lean).** Every major subsystem is defined by an interface and can be swapped via configuration; data flows source → algorithm → order execution → back out. ([QuantConnect Lean overview](https://zread.ai/QuantConnect/Lean))

**FoxTrader vs. this:** FoxTrader's domain is clean and interface-driven (good), but it does **not** share one execution model between backtest and live decisioning — `BacktestEngine` and the live `AgentOrchestrator`/`MasterDecisionEngine` are separate paths. *Redesign opportunity (medium, later):* unify on a single deterministic evaluation core so a strategy backtested is provably the strategy that runs live (backtest/live parity). Adopt **executable invariants** (`require`/`check`) on critical risk/decision paths — cheap, high-assurance, very much in FoxTrader's existing style.

### 1.8 Android / Compose / high-performance graphics (OSS)
- **Compose-native canvas gives direct draw + gesture control** — the right tool for financial charts, avoiding `AndroidView` interop overhead. ([Building interactive trading charts in Compose](https://naveenudesh.medium.com/building-interactive-trading-charts-in-jetpack-compose-7f067a1c2c83))
- **Recomposition discipline is the performance game:** minimize recomposition, layout thrash, and redraws; profile with the right tools. ([Compose rendering performance](https://sachankapil.medium.com/mastering-rendering-performance-in-jetpack-compose-jank-detection-and-prevention-in-android-9e0ebeaa7393))
- **Extensible chart libraries (Vico, MPAndroidChart, ComposeCharts)** succeed via clear separation of data model, axis/scale, and renderer, plus multiplatform reach. ([Vico](https://github.com/patrykandpatrick/vico), [MPAndroidChart](https://github.com/philjay/mpandroidchart), [ComposeCharts](https://github.com/ehsannarmani/ComposeCharts))

**FoxTrader vs. this:** FoxTrader's chart already applies these principles rigorously (hoisted `Paint`, `@Stable` viewport, `remember`-scoped state, immutable collections, adaptive quality) and is arguably **stronger than the popular OSS libraries for the candlestick use case** because it is purpose-built. No wholesale redesign warranted; the primitive-plugin seam (§1.1) is the one worthwhile borrow.

---

## 2. Benchmark scorecard (FoxTrader vs. professional standard)

| Dimension | Professional standard | FoxTrader today | Gap | Action |
|---|---|---|---|---|
| Chart rendering | Canvas, DPR-perfect, huge arrays, incremental | Allocation-free viewport, layers, adaptive quality, paging | **Small** | Add primitive-plugin seam |
| Order flow / DOM | Time-indexed L2 heatmap, GPU for density | Heatmap/profile only; no L2 | Large (data-limited) | **Postpone** |
| Platform architecture | Surface + shared engine, feed-agnostic, workspaces | Clean layers, SDK registries; **fragmented live path**, no workspaces | Medium | Consolidate engine (T1.1); workspaces later |
| Risk / sizing | Contract-spec driven, per-asset tick value | **Forex-only `*100_000`** | **Critical** | **T0.1 now** |
| Strategy philosophy | Non-repainting, validate w/ real costs, AI-assist | Non-repainting ✓, AI narration-only ✓ | Small | Verify cost modeling in backtest |
| Backtest/live parity | One deterministic engine both ways | Separate backtest vs live paths | Medium | Unify eval core (later) |
| High-assurance | Executable invariants on critical paths | Tests strong; few runtime invariants | Medium | Add `require`/`check` on risk/decision |
| UX | Linked workspaces, color-accessible, muscle-memory-safe | No workspaces; palette not verified for CVD | Medium | Workspace persistence + a11y palette (later) |
| Extensibility | Plugin subsystems behind interfaces | Indicator/Drawing SDKs ✓ | Small | Extend to chart primitives |

---

## 3. Decisions this research changes or confirms in the Master Plan

1. **Confirms T0.1 (risk math) as correct and P0.** The professional contract-spec/tick-value standard is precisely the redesign target. No change to priority — reinforced.
2. **Confirms T1.1 (consolidate the orphaned market engine).** "Feed-agnostic, single engine consumed by app *and* extensions" is the professional norm; two live-data paths is the anti-pattern. Reinforced.
3. **Adds a new, later-phase theme — "Backtest/Live Parity + Executable Invariants"** (from NautilusTrader). Fits FoxTrader's high-assurance culture; slot after debt cleanup (new Phase 3.5 / roadmap addendum below).
4. **Adds "Chart Primitive plugin seam"** (from TradingView LWC) as a bounded refactor to fold into the T2 chart work — pluggable, testable overlays.
5. **Adds later-phase UX: workspace/template persistence + color-accessibility palette** (from Bloomberg + NinjaTrader), extending T4.3.
6. **Reconfirms "no speculative backend / no L2 DOM now."** Data availability, not engineering, is the limiter; postpone.

### Roadmap addendum (folds into the existing phases)
- **T2.4 (new, Phase 2):** Extract a `ChartPrimitive` interface (receives viewport transform); migrate one overlay (e.g., SMC zones) to it as proof. *Impact: Medium · Complexity: M · Dep: T2.1.* **Acceptance:** a new overlay can be added without editing `CandleChart`; primitive unit-tested against a fake transform. **DoD:** ≥1 existing overlay migrated, no render regression in smoke/benchmark.
- **T3.4 (new, Phase 3):** Verify/add explicit commission+spread+slippage inputs in `BacktestEngine`; assert no look-ahead. *Impact: Medium · Complexity: S–M.* **Acceptance:** backtests accept configurable costs; a test proves cost sensitivity and bar-close-only fills. **DoD:** documented cost model, tests green.
- **T3.5 (new, Phase 3.5):** Executable invariants on risk/decision hot paths (`require`/`check` guarding: no synthetic data past the veto, position size ≤ risk budget, confidence within [0,1]). *Impact: Medium (assurance) · Complexity: S.* **Acceptance:** invariants fail fast in debug, are cheap in release. **DoD:** invariant tests; no measurable release regression.
- **T4.4 (new, Phase 4, later):** Workspace/template persistence + color-vision-deficiency-safe palette. *Impact: Medium (UX) · Complexity: M.* **Acceptance:** multi-chart layout + per-symbol template survive restart; a CVD-safe theme is selectable. **DoD:** persistence tested; accessibility scan passes.

---

## 4. Guardrails honored
- No proprietary code, indicators, or algorithms copied — only publicly documented engineering *principles*.
- Every external claim is attributed with an inline link.
- Content was rephrased for compliance with licensing restrictions.
- Where FoxTrader already matches or exceeds the standard, we **leave it unchanged** (chart engine, non-repainting discipline, AI-as-assistant). Redesign is proposed *only* where a demonstrably better approach exists.

---

*This research precedes implementation. The highest-priority sprint — Master Plan Phase 0, T0.1 (instrument/contract-spec risk redesign) — is validated by §1.4 and begins next.*
