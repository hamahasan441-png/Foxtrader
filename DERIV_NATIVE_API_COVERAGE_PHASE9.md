# Native Deriv API Coverage — Phase 9

Target reviewed: Deriv's current `api.derivws.com` Options REST/WebSocket API family, 2026-08-21.

| Area | Capability | Phase 9 status | Notes |
|---|---|---:|---|
| System | Health | Implemented | Native REST health check |
| Auth | App ID + bearer token | Implemented | PAT/OAuth token input; encrypted at rest |
| Auth | Interactive OAuth2/PKCE browser flow | Not implemented | Explicitly outside Phase 9 |
| Accounts | List Options accounts | Implemented | REST |
| Accounts | Create demo Options account | Implemented | Current USD + `row` schema pinned |
| Accounts | Reset demo balance | Implemented | Demo-only guard |
| Accounts | Obtain account OTP | Implemented | Fresh per-account OTP |
| Session | Public Options WebSocket | Implemented | Current Deriv public WS origin |
| Session | Authenticated account WebSocket | Implemented | OTP URL validation + stale-session guard |
| Market | Active symbols | Implemented | New underlying-symbol model |
| Market | Contracts for symbol | Implemented | Public WS |
| Market | Contract categories/list | Implemented | Public WS |
| Market | Ticks | Implemented | Stream + forget |
| Market | Ticks history/candles | Implemented | Bounded count |
| Market | Server time / trading times / ping | Request support implemented | Utility builders |
| Account | Balance | Implemented | Authenticated WS |
| Account | Open portfolio | Implemented | Authenticated WS |
| Account | Transactions | Implemented | Authenticated stream |
| Account | Profit table | Implemented | Closed-history read |
| Account | Statement | Implemented | Account-history read |
| Trading | Proposal | Implemented | Current `underlying_symbol` request shape |
| Trading | Buy | Implemented | Fresh REAL confirmation gate |
| Trading | Open contract | Implemented | Read |
| Trading | Early sell | Implemented | Fresh REAL confirmation gate |
| Trading | Contract SL/TP update | Implemented | Fresh REAL confirmation gate |
| Trading | Contract update history | Implemented | Read |
| Trading | Cancel contract | Implemented | Fresh REAL confirmation gate; contract/API dependent |
| Wallet | List wallets | Implemented, read only | Requires appropriate payment permission/scope |
| Wallet | Wallet transactions | Implemented, read only | Cursor pagination + safe-origin validation |
| Wallet | Fund transfer / withdrawal | Intentionally excluded | No money-movement path |
| Bulk | Bulk REAL purchase | Intentionally excluded | No multi-account real-money execution path |
| Automation | Native REAL unattended auto-fire | Intentionally excluded | REAL remains manual/fail-closed |
| Legacy API | Legacy Binary/old Deriv transport | Not targeted | Phase 9 is new API only |

## What “complete” means for this phase

Phase 9 is complete for the **safe native Deriv Options core used by FOX Trader**: account bootstrap, authenticated session, public market data, single-account manual contract lifecycle, account history, and read-only wallet visibility. It is not a claim that every Deriv product/API family or every money-movement/admin endpoint has been copied into FOX Trader.

## Release acceptance still required

Before enabling production REAL trading in a release APK:

1. Build with the real Android/Gradle toolchain and pass unit/lint/instrumentation gates.
2. Run against a Deriv demo account first.
3. Verify active-symbol and contract availability for the intended region/account.
4. Verify proposal → buy → open contract → update/sell/cancel lifecycle on demo.
5. Verify reconnect/account-switch handling with fresh OTPs.
6. Verify payment-scope behavior for wallet read-only screens.
7. Only then run a minimum-size REAL manual transaction with explicit operator confirmation and existing Phase 6/8 risk controls.
