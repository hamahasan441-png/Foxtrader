# Phase 9 — Native Deriv API Integration

Date: 2026-08-21

## Goal

Phase 9 adds a native Deriv integration beside the existing Phase 6 MT4/MT5/MetaApi path. It targets Deriv's current `api.derivws.com` REST + Options WebSocket architecture and preserves the Phase 6–8 risk, confirmation, audit and release-hardening principles.

This phase is intentionally **fail-closed for REAL money**. A native Deriv REAL contract action cannot be submitted by the Phase 7 unattended automation path. Buy, early sell, contract update and cancel actions require a fresh account-bound manual confirmation.

## Implemented architecture

### Native REST transport

- Base origin: `https://api.derivws.com`
- Dedicated `@DerivApiClient` OkHttp client with **zero HTTP logging**.
- REST authentication uses `Deriv-App-ID` plus bearer token.
- Bearer token stored with Android `EncryptedSharedPreferences`.
- Corrupted encrypted preference recovery is fail-safe.
- Health endpoint support.
- Options account list.
- Demo Options account creation using the currently supported USD / `row` schema.
- Demo balance reset, guarded so REAL accounts cannot call the reset path.
- Per-account OTP request for authenticated Options WebSocket sessions.
- OTP WebSocket URL origin/path validation before connection.

### Native WebSocket transport

- Public endpoint: `wss://api.derivws.com/trading/v1/options/ws/public`.
- Authenticated account endpoint is obtained only through a fresh Deriv OTP session.
- Request correlation via `req_id`.
- Request timeout and connection timeout.
- Keep-alive ping.
- Generation guard rejects stale callbacks after reconnect/account switch.
- Authenticated sessions are not silently reused across accounts.
- No automatic authenticated reconnect with an old OTP.

### Market data

- Active symbols using the new `underlying_symbol` field family.
- Contracts-for-symbol discovery.
- Contract-category discovery (`contracts_list`).
- Tick subscription / unsubscribe.
- Historical candles via ticks-history.
- Server time / ping / trading-time request builders.

### Account data

- Balance.
- Open Options portfolio.
- Live transaction stream.
- Profit table for closed contract history.
- Statement history.

### Contract trading and management

- Proposal request using `underlying_symbol`; no legacy `loginid` request field.
- Manual buy from a returned proposal ID.
- Open-contract inspection.
- Early sell; minimum price `0` is supported as the explicit market-sell input.
- Stop-loss / take-profit contract update.
- Contract update history.
- Cancel request where the contract/API supports cancellation.

### Wallet visibility — read only

- Wallet list.
- Wallet balances and approximate converted totals when returned by Deriv.
- Wallet transaction history for `main`, `p2p`, `partner`, and `payment_agent` wallet types.
- Cursor pagination with `Load more`.
- Pagination URLs are accepted only when they resolve to Deriv's HTTPS origin and `/wallet/v1/transactions/` path; foreign origins, user-info and fragments are rejected.
- Wallet calls remain read-only and require the caller's token to have the required Deriv payment permission/scope.

## Safety invariants

1. REAL buy/sell/update/cancel requires a fresh `DerivExecutionAuthorization`.
2. Authorization is bound to the currently connected account type; changing account invalidates the action.
3. REAL confirmation expires after the bounded freshness window.
4. Account switching clears the current authenticated account before requesting a new OTP.
5. OTP URLs are never sent through an HTTP logging interceptor.
6. Tokens are never written to the normal plaintext settings store.
7. WebSocket hosts and wallet pagination origins are explicitly constrained.
8. Input amounts/durations/contract IDs are validated before transport.
9. New API request builders do not add legacy `loginid` fields.
10. Phase 7 unattended automation does not gain a native REAL auto-fire path.

## Deliberately not implemented

These are exclusions, not forgotten endpoints:

- Wallet money transfer / withdrawal execution.
- Payment-agent write operations.
- Bulk REAL multi-account purchase.
- Deriv `auto_*` live strategy execution.
- Any bypass of manual REAL confirmation.
- Interactive in-app OAuth2/PKCE browser login. Phase 9 accepts a PAT/OAuth bearer token supplied by the user and stores it encrypted.
- Legacy Binary/Deriv WebSocket host compatibility. Native Phase 9 targets the current API architecture only.

## Primary files

- `app/src/main/java/com/foxtrader/app/domain/model/deriv/DerivModels.kt`
- `app/src/main/java/com/foxtrader/app/domain/repository/DerivRepository.kt`
- `app/src/main/java/com/foxtrader/app/domain/usecase/deriv/DerivRequestBuilder.kt`
- `app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivCredentialStore.kt`
- `app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivRestClient.kt`
- `app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivWebSocketClient.kt`
- `app/src/main/java/com/foxtrader/app/data/repository/DerivRepositoryImpl.kt`
- `app/src/main/java/com/foxtrader/app/feature/deriv/presentation/DerivUiState.kt`
- `app/src/main/java/com/foxtrader/app/feature/deriv/presentation/DerivViewModel.kt`
- `app/src/main/java/com/foxtrader/app/feature/deriv/presentation/Phase9DerivScreen.kt`
- `scripts/phase9_deriv_preflight.sh`

## Verification status

See `VALIDATION_RESULTS_PHASE9.txt` for the exact status. Source/domain smoke tests and Phase 9 security preflight pass. A complete Android Gradle test/build could not start because this execution environment cannot resolve `services.gradle.org` to download the Gradle 8.9 wrapper distribution. No live Deriv trade was submitted during implementation; real/demo end-to-end broker verification therefore remains a release-environment test.
