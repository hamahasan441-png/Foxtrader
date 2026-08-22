# FOX Trader — Phase 5 Implementation

Date: 2026-08-21
Base: Phase 4 robust optimizer + MTF/SMT scanner + adaptive risk

## Goal

Phase 5 establishes a professional chart research workspace without weakening the existing non-repaint and execution-safety contracts.

## Delivered

### 1. Pro Studio
A new `Pro Studio · Phase 5` destination is accessible from both More > Research and directly from the chart toolbar. It is split into Strategy, Indicators and Signals surfaces so configuration work stays outside the price canvas.

### 2. Strategy Studio
- Reuses the persisted `StrategyBlueprint` architecture instead of creating a second strategy format.
- Adds installable Phase 5 scalp and intraday confluence templates.
- Keeps strategy execution/backtesting on the existing `ScriptEngine` / Backtesting Lab path.
- Strategy templates deliberately avoid SMT until peer-aware blueprint backtests can prove SMT without look-ahead.

### 3. Indicator Studio
- Adds typed `IndicatorStudioPreset` definitions.
- Includes EMA trend-stack, RSI confirmation, session VWAP and separate SuperTrend scalp/intraday presets.
- Presets declare overlay vs oscillator pane placement and parameter maps, matching the existing indicator SDK contract.

### 4. Signal Manager
- Adds a typed `SignalManagerPolicy` with defensive sanitisation.
- Supports Live Only, Confirmed History and All Research visibility modes.
- Provides minimum confidence, confirmed-bar research gate, Phase 4 confluence intent and maximum-visible-signal bounds.
- Adds `Phase5StudioEngine`, a fail-safe display filter that never manufactures a trade signal; it only filters signals computed by existing engines.

### 5. Chart integration
- Adds a compact Pro Studio button to the chart toolbar beside replay/signal-history controls.
- Leaves the main chart clean; authoring/configuration opens as a separate screen rather than adding floating panels over price.
- Existing multi-chart, drawing tools, replay, multi-pane indicators and signal history remain intact and are treated as Phase 5 foundations rather than duplicated.

## Non-repaint / safety contract

- Phase 5 does not convert research presets into broker orders.
- Studio signal logic never creates an entry and cannot override Phase 4 risk controls.
- Blueprint execution still uses the existing prefix-only `ScriptContext` contract.
- SMT remains unavailable in single-symbol blueprint backtests until peer-synchronised data is wired through that runner.
- Phase 4 confluence remains a stricter upstream execution/scanner concern; the Phase 5 UI exposes the policy rather than bypassing it.

## Build validation

Attempted: `./gradlew :app:compileDebugKotlin --offline --stacktrace`.

The Gradle wrapper still requires Gradle 8.9 from `services.gradle.org`, which cannot resolve in this environment (`UnknownHostException`). Therefore this package does **not** claim a full Android Gradle compile pass. Static source checks were performed on the added navigation, model and studio files.
