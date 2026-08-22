# FOX Trader — Phase 7: Automation & Execution Cockpit

Date: 2026-08-21
Base: Phase 6 Live Trading + Deriv/MT4/MT5 Accounts + Execution Safety

## Goal

Phase 7 connects already-computed research signals to a controlled execution-routing workflow without weakening the Phase 4/6 non-repaint, risk, confirmation, idempotency, audit or reconciliation guarantees.

## Delivered

### 1. Automation policy model
- Added `AutomationEnvironment`: PAPER, BROKER_DEMO, LIVE.
- Added `AutomationMode`: OFF, REVIEW_QUEUE, AUTO_PAPER_DEMO.
- Added sanitised policy bounds for confidence, queue size and cooldown.
- Added typed automation candidates and decisions.

### 2. Fail-closed signal routing
`Phase7AutomationEngine` never creates a signal and never places an order.

A candidate is rejected when:
- automation is disabled;
- source provenance is not trustworthy;
- confidence is below the configured floor;
- a confirmed bar is required but missing;
- Phase 4 actionability is required but failed.

### 3. Live-money hard boundary
Even when `AUTO_PAPER_DEMO` is selected, a LIVE candidate is always converted to `QueuedForReview`.

There is no unattended live-money auto-submit path in Phase 7. The operator must continue through Phase 6, which retains fresh confirmation, stale-price checks, broker volume bounds, SL/TP validation, daily-loss/free-margin gates, idempotency, audit receipts, kill switch and UNKNOWN-order reconciliation.

### 4. Bounded review queue
Added `Phase7AutomationQueue`:
- thread-safe routing;
- candidate-ID deduplication;
- symbol+direction cooldown deduplication;
- bounded queue with deterministic oldest-item eviction;
- rejected/duplicate counters;
- immutable snapshots for presentation.

The queue is intentionally not treated as broker truth or an order ledger. Broker truth remains the Phase 6 audit/reconciliation stack.

### 5. Phase 7 cockpit UI
Added `Phase7AutomationScreen` and navigation from More > Live trading.

The cockpit exposes:
- Off / Review / Paper-Demo Auto modes;
- Phase 4 actionable gate;
- confirmed-bar gate;
- explicit notice that automatic routing is limited to paper/demo;
- direct navigation back to Phase 6 Trading and Phase 5 Pro Studio.

## Files added
- `app/src/main/java/com/foxtrader/app/domain/model/Phase7Automation.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/execution/Phase7AutomationEngine.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/execution/Phase7AutomationQueue.kt`
- `app/src/main/java/com/foxtrader/app/feature/automation/presentation/Phase7AutomationScreen.kt`
- `app/src/test/java/com/foxtrader/app/domain/usecase/execution/Phase7AutomationEngineTest.kt`
- `PHASE7_IMPLEMENTATION.md`
- `VALIDATION_RESULTS_PHASE7.txt`

## Files extended
- `app/src/main/java/com/foxtrader/app/feature/more/presentation/MoreScreen.kt`
- `app/src/main/java/com/foxtrader/app/ui/navigation/FoxNavHost.kt`

## Validation
- Phase 7 pure Kotlin compile with an isolated Direction stub: PASS.
- Behavioral smoke test for live-review enforcement, paper auto eligibility, fail-closed provenance and queue dedupe: PASS.
- Source delimiter sanity: PASS.
- More/navigation route wiring static check: PASS.
- Full Android Gradle compile could not run because Gradle 8.9 is not cached and `services.gradle.org` is unreachable from this sandbox. No Android Gradle compile success is claimed.

## Security / execution note
Phase 7 is an orchestration layer, not an execution bypass. It cannot turn research output directly into unattended live-money orders. Any future change to this boundary must preserve explicit user authorization and the Phase 6 safety coordinator as the final authority.
