# FoxTrader — Privacy & Data Safety

This document is the source of truth for the Google Play **Data safety** form and for the app's privacy posture. It reflects what the code actually does.

## What FoxTrader is

FoxTrader is an **educational market-analysis tool**. It visualizes charts, computes technical/Smart-Money analysis, and produces *advisory* signals. **It does not place trades and is not financial advice.** Users are solely responsible for their own decisions. This disclaimer is surfaced in-app as a **first-run gate that must be acknowledged before any analysis is shown**, and remains available afterwards in Settings → Privacy.

## Data collection & sharing summary

| Question | Answer |
|---|---|
| Does the app collect personal data? | No personal data is collected or transmitted by default. |
| Does the app share data with third parties? | No. |
| Is data transmitted off-device automatically? | No. Market data is fetched *from* providers; nothing about the user is sent to FoxTrader servers (there is no FoxTrader backend in this build). |
| Is collected data encrypted in transit? | All network calls use HTTPS (enforced in release). |
| Can the user request deletion? | All app data is local; uninstalling the app removes it. |

## Data stored locally on the device (never uploaded)

| Data | Where | Purpose | Encryption |
|---|---|---|---|
| Journal entries, drawings, watchlists | Room database | Core app features | Android app-sandbox |
| Settings/preferences | DataStore | Persist user configuration | Android app-sandbox |
| Provider API keys | EncryptedSharedPreferences | Authenticate to the user's chosen market-data provider | AES-256 (Jetpack Security) |
| Crash diagnostics (opt-in only) | `filesDir/crash_logs/` | Let the user share a diagnostic with support | Android app-sandbox |

## Third-party market-data providers

When the user selects a live provider (e.g., Binance, Bybit, Alpha Vantage) and supplies their own API key, the app connects **directly** to that provider to fetch market data. The user's API key is sent only to that provider over HTTPS and is stored encrypted on-device. FoxTrader does not proxy, log, or receive that key. Review each provider's own privacy policy.

## MT4 / MetaApi connectivity and token lifecycle

When the user connects a MetaTrader 4 account, the app talks directly to MetaApi (Agilium Trade) using the user's own MetaApi auth token.

- **Where the token goes:** The MetaApi auth token is sent only to MetaApi's hosts over TLS — both on the provisioning REST API (as an `auth-token` header) and on the streaming WebSocket (as an `auth-token` query parameter, which is MetaApi's required contract for the upgrade). FoxTrader never proxies or receives the token.
- **No credential logging:** Neither the MetaApi REST client nor the MetaApi WebSocket client installs an HTTP logging interceptor, in debug or release. WebSocket request URLs are only ever rendered through a redactor that replaces the token value with a fixed `[REDACTED]` placeholder, so the live token cannot leak to logcat, crash reports, or diagnostics.
- **In-memory lifecycle:** The token and account ID are held only while a stream is active and are wiped from memory on disconnect. A credential rejection (HTTP 401/403) stops all reconnection attempts until the user re-authenticates.
- **On-device storage:** The token is stored with AES-256 via Jetpack Security, the same as other provider keys. Account/position data is fetched from MetaApi on demand and is not retained by FoxTrader beyond what the user's own features keep locally.
- **What FoxTrader does with it:** Account info, open positions, and quotes are used to render the user's own MT4 view. **Live trading is opt-in and off by default:** only after the user explicitly enables Live mode on the MT4 account screen and confirms each order in a two-step dialog does FoxTrader send a market order to MetaApi for execution on the user's account. Every order attempt is recorded in a local append-only audit log so an ambiguous outcome is reconciled rather than silently re-submitted. FoxTrader does not send this data anywhere else. Review MetaApi's own privacy policy as well.

## Crash reporting (opt-in, privacy-preserving)

- **Default: OFF.** The user must explicitly enable it in Settings → Privacy.
- When enabled, an uncaught exception writes a small local file containing **only** the exception type chain and code stack frames (class/method/line).
- **Exception messages are deliberately excluded** because they can contain runtime values that qualify as user data.
- **Nothing is uploaded.** Files stay in the app's private storage, rotate (max 5), and are removed on uninstall.
- Implemented by `LocalCrashReporter` behind the `CrashReporter` seam; a future remote backend would also be strictly opt-in.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Fetch market data from the selected provider |
| `POST_NOTIFICATIONS` | Show price/scan alerts the user configured |

No location, contacts, camera, microphone, or storage permissions are requested.

## Security posture

- HTTPS enforced for release network clients; a separate no-auth client ensures FoxTrader auth tokens are never sent to third-party market hosts.
- API keys stored with AES-256 via Jetpack Security.
- Release builds are minified/shrunk (R8) and signed from CI secrets (never committed).

*Keep this document in sync with the code. If a change adds any data collection or transmission, update this file and the Play Data safety form in the same PR.*
