# Phase 12 — Professional Execution & Real-Time Trading

## Scope

Phase 12 upgrades the Phase 11 MT4/MT5 path from a hardened market-order connection into a professional execution workflow while preserving the fail-closed safety model. Gradle/Android APK compilation is intentionally outside this phase's acceptance boundary at the project owner's request; this phase is validated with source preflights, independent Kotlin compilation/smoke tests, Room migration verification, backend tests, syntax/XML checks, and archive integrity checks.

## MetaApi real-time prices

- MetaApi Socket.IO is the primary low-latency quote channel.
- Market-data subscribe/unsubscribe requests are account- and generation-bound.
- The Socket.IO handshake uses the dedicated zero-HTTP-log client so the MetaApi token is not emitted through app network logging.
- Malformed, future, non-finite, crossed, stale-generation, and out-of-order prices are rejected.
- Quote-buffer overflow fails/reconnects the stream rather than silently presenting a healthy stream with missing events.
- REST current-price polling remains an independent 1-second watchdog/fallback only when Socket.IO is unavailable.
- A 30-second stream-rebuild watchdog recovers from exhausted Socket.IO reconnect attempts.
- Execution does not trust the UI stream alone: the repository requests a just-in-time broker quote before market submission and re-applies the price-drift/safety gates.

## Pending orders

Implemented broker-authoritative pending order support for:

- BUY LIMIT / SELL LIMIT
- BUY STOP / SELL STOP
- explicit `openPrice`
- stop-loss / take-profit
- GTC / DAY / SPECIFIED / SPECIFIED_DAY expiration modes
- pending order list/state
- modify price/SL/TP
- cancel

Pending orders never fall through the market-order API. Broker `allowedOrderTypes`, allowed expiration modes, `stopsLevel`, and `freezeLevel` are checked before submission/modification.

## Position manager

The connected-account UI now supports:

- modify SL / TP
- remove SL / TP with explicit zero semantics
- broker-side trailing stop distance
- break-even
- partial close
- full close through the existing reviewed close workflow

Partial close validates broker min/max/step volume and also verifies that the remaining position volume is valid.

## Review-state / concurrency protection

Phase 12 binds destructive management actions to the broker state the user actually reviewed:

- Position manager captures reviewed lots, SL, and TP.
- Pending manager captures reviewed open price, lots, SL, and TP.
- Immediately before modify/partial-close, the repository fetches broker state again.
- If the broker state changed externally (another device, terminal, EA, or stale UI), the action is rejected and the user must refresh/review again.

This prevents a stale management dialog from overwriting a newer broker-side change.

## Durable execution and idempotency

All broker management calls use the same conservative write-ahead principle as market execution:

1. derive an account-scoped/state-bound idempotency key;
2. persist an `UNKNOWN` reservation before contacting the broker;
3. submit only if durable reservation succeeded;
4. persist `ACCEPTED` or definitive `REJECTED` when proven;
5. keep `UNKNOWN` after timeout/disconnect/ambiguous persistence failure;
6. never blindly retry an UNKNOWN action.

Management idempotency includes the broker starting state for modifications. A previously accepted target therefore does not incorrectly suppress a later legitimate action after the broker state changed again.

## Reconciliation

UNKNOWN reconciliation now covers:

- market open
- full close
- pending create (including a uniquely provable fast fill)
- pending cancel
- pending modify
- position SL/TP modify
- break-even
- partial close

Promotion from UNKNOWN to ACCEPTED requires broker-state evidence. Trailing-stop modification intentionally remains UNKNOWN after an ambiguous result when the available position snapshot does not expose enough trailing configuration to prove the outcome. The app does not invent certainty.

## Chart → broker workflow

A valid chart/TradePro setup can stage a short-lived broker trade draft containing symbol, direction, reference entry, SL/TP, source, and confidence. The draft expires and is consumed once. It only pre-fills the broker screen; the chart never submits a broker order directly. Normal broker review, account binding, risk gates, price-drift checks, and explicit confirmation remain mandatory.

## Professional journal

Phase 12 adds a broker-synchronized journal workflow:

- broker-scoped open-position entries;
- close state only after authoritative history-deal evidence;
- authoritative exit price/time/realized P&L when available;
- no fabricated close or floating-P&L-as-realized fallback;
- win rate, expectancy, profit factor and aggregate analytics;
- CSV export with spreadsheet-formula injection protection.

`JournalEntry.isOpen` is derived from `exitPrice == null`, eliminating stale copied open/closed state.

## Safety invariants retained

- LIVE mode remains explicit and session-scoped.
- Emergency kill switch remains fail-closed.
- Fresh user confirmation remains required.
- Missing quote, broker spec, daily-loss data (when enabled), or free-margin data (when enabled) blocks execution.
- Estimated symbol specs do not authorize LIVE execution/management.
- BUY uses ask; SELL uses bid.
- Review-to-submit price drift is bounded using broker point size and current spread.
- Account/session switching invalidates stale UI results and execution state.
- REAL Deriv native execution remains manual-confirmation/fail-closed; Phase 12 does not add unattended REAL execution.

## Acceptance boundary

The source-level Phase 12 acceptance does **not** claim an Android/Gradle build or credentialed live-broker end-to-end acceptance. Those are deliberately outside this run. No real-money credentials or orders were used. The reproducible non-Gradle checks and exact results are recorded in `VALIDATION_RESULTS_PHASE12.txt` and `scripts/phase12_professional_execution_preflight.sh`.
