# FoxTrader Master Development Directive

## Core Rules

1. Read the entire repository and all project documentation before writing a single line of code.
2. The existing FoxTrader project is the single source of truth.
3. Never ignore the current architecture.
4. Never replace working modules unless a measurable improvement exists.
5. Before implementing anything: read every document, understand the architecture, every dependency, every module, every package, analyze performance, memory usage, rendering, state management, scalability.
6. Only after a complete understanding may implementation begin.

## Project Goal

Build FoxTrader into one of the world's fastest and most professional Android trading platforms.
Target quality comparable to or better than: TradingView, Bookmap, ATAS, Quantower, NinjaTrader, Sierra Chart, Bloomberg Mobile.
Never copy. Study their strengths. Design a better FoxTrader experience.

## Platform

- Android ONLY. Always produce production-ready APK code.
- Never convert to a website. Never create another repository.
- Always continue improving the existing FoxTrader repository.

## Chart Engine (Highest Priority)

- 120 FPS on supported hardware, never below 60 FPS
- Zero input latency, GPU accelerated rendering
- Infinite scrolling, infinite zoom, viewport rendering only
- No frame drops, rendering spikes, memory leaks, unnecessary recompositions
- Hardware accelerated drawing, massive historical datasets
- Tick charts, replay mode, professional crosshair, drawing tools, multi-chart support
- Every interaction must feel premium

## UI/UX

- Unique Fox Design System: professional, institutional, luxury, minimal, fast, elegant
- Every animation natural, every screen premium, every pixel purposeful

## Performance Priority

Performance over visual effects. Continuously profile: CPU, GPU, Memory, Battery, Startup, Rendering, Network, Storage.

## Code Quality

Every new feature must include: Clean Architecture, MVVM, SOLID, Repository Pattern, Hilt, Kotlin Coroutines, Flow, Unit Tests, Integration Tests, Documentation.
Never introduce technical debt. Refactor first if existing code can be improved.

## AI Engineering Process

Before coding: think, compare multiple architectures, evaluate alternatives, choose the strongest solution.
Never implement the first idea. Continuously review own work. Refactor whenever a better solution exists.

## Git Workflow

After every completed feature: compile successfully, run tests, fix warnings, commit with Conventional Commits, push to existing FoxTrader repository. Never leave the repository in a broken state.

## Final Rule

Every decision must move FoxTrader closer to becoming the best Android trading platform in the world while remaining maintainable, secure, performant and production-ready.
