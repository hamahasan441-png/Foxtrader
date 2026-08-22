# Phase 10 — Deriv API & Account Switcher

## Goal
Allow the user to replace Deriv API credentials and switch Deriv Options accounts entirely inside FOX Trader without reinstalling the app or clearing app data.

## Implemented

- Editable Deriv **App ID** and **PAT/OAuth access token** inside the Native Deriv screen.
- Explicit **Apply API & verify accounts** flow. New credentials are persisted only after the Deriv accounts request succeeds.
- **Revert API** restores the last encrypted, verified credential set.
- Editing either credential immediately invalidates the previous authenticated WebSocket session and cancels tick streaming.
- All account-bound UI state and pending REAL/Demo execution confirmations are cleared on credential edits.
- Account cards now expose an explicit **Connect / Switch / Connected** state.
- Every account switch obtains a fresh Deriv OTP and authenticated WebSocket session.
- Remembered account IDs are cleared when the App ID/token identity changes, preventing cross-credential account reuse.
- Credential-verification response race protection: a response for stale edited credentials cannot overwrite or save newer field values.
- Deriv encrypted preferences are excluded from Android cloud backup and device-transfer extraction.
- Fixed a pre-existing duplicate `profit` local declaration in `DerivViewModel.loadAccountHistory()`.

## Safety invariants

1. A dirty/unapplied API configuration is never considered authenticated.
2. REAL order actions retain Phase 9 fresh manual confirmation requirements.
3. Changing API identity disconnects the old account before the new identity is persisted.
4. Failed API verification leaves the previously saved credentials untouched.
5. Account selection is saved only after a successful authenticated connection.

## Build status

Full Android Gradle compilation remains environment-blocked when Gradle 8.9 is not locally cached and `services.gradle.org` cannot be resolved. Phase 10 therefore does not claim a full Android build pass in this sandbox.
