# FOX Trader Phase 11 — Full Audit, Stability & Execution Hardening

## Scope

Phase 11 is a deep review/fix pass over the Phase 10 codebase. The priority is correctness and fail-closed behavior on live-money paths, then crash/race/error handling, credential protection, persistence integrity, and UI state isolation. Gradle/full Android build execution is explicitly out of scope for this pass by owner request.

## Critical fixes

### MT4/MT5 / MetaApi

- Corrected MetaApi architecture: provisioning, regional trading/client REST, and regional historical-market-data routing are separated.
- Replaced the unreliable ad-hoc raw WebSocket quote protocol with protocol-correct bounded current-price REST polling (`keepSubscription=true`) until an official/tested Socket.IO client is integrated.
- Cached MetaApi account IDs are reused only after login/server/platform identity validation. A terminal-startup failure no longer creates a duplicate provisioned cloud account.
- Existing `CREATED`/`UNDEPLOYED` accounts are deployed idempotently and terminal readiness is checked with bounded retries.
- MT4/MT5 account login uses `Long` throughout the live path, preserving larger account numbers and legacy preference compatibility.
- Dynamic broker symbols are URL-path encoded rather than restricted to an unsafe/overly narrow symbol regex.
- Regional host resolution now supports validated MetaApi region DNS labels while keeping every generated client/market-data host beneath the fixed `agiliumtrade.ai` suffix; legacy `vint-hill` maps to `new-york`.
- MetaApi trade result classification now recognizes documented success states and preserves timeout/disconnection/ambiguous outcomes as `UNKNOWN` instead of falsely declaring rejection.
- A reported trade success without a usable numeric order/position ID is treated as `UNKNOWN`, forcing reconciliation instead of a blind retry.
- Pending-order types are rejected at both repository and data-source boundaries because this market-order path has no explicit pending `openPrice`. They cannot silently become market BUY/SELL orders.
- Broker symbol specifications are account-scoped in cache and include broker point/base/profit currency metadata where available.
- Live execution rejects estimated broker specifications; volume min/max/step validation is broker-authoritative.

### Live execution safety

- Added durable write-ahead `UNKNOWN` reservation before broker submission. If the audit database cannot persist the reservation, the broker is never contacted.
- If the broker may have accepted a request but final local persistence fails, `UNKNOWN` remains durable and blind retry is blocked until reconciliation.
- Idempotency keys are scoped by broker account and operation (`OPEN` vs `CLOSE:<ticket>`), preventing cross-account collisions and open/close receipt overwrites.
- Local safety `REJECTED` receipts do not permanently poison an idempotency key; a fresh corrected review may be re-evaluated because the broker never saw the rejected attempt.
- Fresh confirmation rejects stale and future timestamps.
- Market BUY review/execution uses ask; market SELL uses bid. Mid-price is no longer used for executable-entry validation.
- Review-to-submit price drift is enforced in broker points. The UI freezes the reviewed executable price and uses an adaptive cap (minimum 20 points, or 5× current spread); excessive movement forces a fresh review.
- SL/TP direction is revalidated against the current executable ask/bid immediately before submit.
- Missing live quote, enabled daily-loss source, enabled free-margin source, or broker specification fails closed.
- Close-position is a two-step review/confirm flow. It snapshots the exact ticket/account, uses a durable `UNKNOWN` reservation before broker close, and never substitutes pre-close floating P/L for authoritative realized P/L.
- Realized close P/L is recovered from MetaApi history deals; if history has not synchronized, the P/L remains unknown and the daily-loss gate stays conservative.
- Reconciliation handles UNKNOWN opens/closes, uniquely matches evidence before promoting to ACCEPTED, and independently backfills accepted-close realized P/L.
- Audit-store failure during reconciliation is surfaced as an integrity error instead of being misreported as zero unresolved orders.

### MT4/MT5 lifecycle / UI

- Connection/account-switch operations are session-epoch guarded so old callbacks cannot overwrite the new account UI.
- Quote polling is generation-bound and clears cached quotes/routing at every account boundary.
- Subscriptions created before login are re-evaluated after credentials become available; removing the final timeframe for a symbol removes that symbol from polling.
- Broker account type is no longer guessed as LIVE merely because the server name lacks `demo`. `DEMO`, `LIVE`, and `UNKNOWN` are distinct; UNKNOWN is visibly treated as LIVE for warnings.
- Order/close result UI is bound to the exact session, login/server, and pending review snapshot.

### Native Deriv

- API credential edits invalidate the authenticated account session and all pending execution reviews immediately.
- Credential replacement is verified before persistence; failed verification does not overwrite the saved working API identity.
- Account switching always creates a fresh OTP/authenticated session and clears stale order confirmations.
- REAL actions remain account-bound, session-bound, and fresh manual-confirmation only; old-session confirmations cannot be replayed after reconnect.
- WebSocket generation/timeout/disconnect races are closed; a late `onOpen` cannot resurrect a timed-out session.
- Subscription buffer overflow is not silently ignored on transaction-sensitive streams; the session is failed/reconciled instead.
- Demo-account creation/reset and symbol/category loads are epoch/credential guarded so stale async responses cannot overwrite a newer API/account state.
- Deriv REST/WebSocket traffic uses a dedicated zero-HTTP-logging client so bearer tokens and OTP URLs do not reach logcat.

### Authentication / backend

- Android refresh handling is deterministic single-flight; concurrent 401s do not race refresh-token rotation.
- New backend access/refresh tokens are stored only as SHA-256 lookup keys, never raw bearer values. Legacy raw rows are migrated/revoked on use for compatibility.
- SQLite refresh-token consume uses `BEGIN IMMEDIATE`, making refresh rotation genuinely single-use across concurrent workers.
- Concurrent registration uniqueness is handled by SQLite as the final authority and translated to a deterministic duplicate-account result instead of a server error.

### Persistence / release/security hygiene

- Execution audit database is schema v10 with account scope + operation identity, preserving legacy rows through additive migration.
- `scripts/verify_room_migrations.py` independently verifies the v1→v10 SQL migration chain, persistence invariants, FK behavior, and execution-audit v8→v10 defaults/indexes.
- Debug emulator cleartext is explicitly isolated to the debug manifest; release remains cleartext-disabled and requires a production HTTPS backend configuration.
- Alpha Vantage, Twelve Data, Polygon, MetaApi, and Deriv credential-bearing transports have no URL/body logger that would expose query/API keys or broker tokens.
- Release version/config parsing was hardened against empty/unsafe environment values and missing HTTPS production backend configuration.
- No `TODO()` / `NotImplementedError` remains in Android production Kotlin; no merge-conflict markers were found.

## Verification performed without Gradle

- Backend pytest suite: PASS.
- Phase 9 Deriv native preflight: PASS.
- Phase 10 Deriv API/account switcher preflight: PASS.
- Phase 11 audit preflight: PASS.
- Room SQL migration v1→v10 verifier: PASS.
- Targeted MetaApi Kotlin compile: PASS.
- MetaApi regional resolver smoke: PASS.
- Execution safety Kotlin compile/smoke: PASS.
- Execution coordinator durable-reservation/account-scope smoke: PASS.
- Android manifest XML parsing: PASS.
- Shell preflight syntax: PASS.
- Python syntax compilation for modified backend/migration verifier: PASS.
- Static secret/placeholder/conflict-marker checks: PASS.

## Deliberate limitations / acceptance boundaries

- Full Gradle/Android build, lint, instrumentation and official Room schema export are not claimed in Phase 11 because Gradle was explicitly excluded by owner instruction. The SQL migration chain is verified independently.
- No real-money trade was sent and no user credential was used. Broker-authenticated Demo/REAL end-to-end acceptance therefore still requires credentials in a controlled test environment.
- MetaApi live chart quotes currently use 1-second REST polling. This is protocol-correct and safer than the removed raw WebSocket implementation, but it is not equivalent to MetaApi Socket.IO low-latency streaming for sub-second scalping.
- Pending MT4/MT5 orders remain intentionally disabled in the market-order API path until a dedicated UI/domain model carries an explicit broker `openPrice` and its own review/confirmation semantics.
- Wallet transfer/withdrawal and unattended REAL Deriv execution remain intentionally unavailable.
