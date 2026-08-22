# FOX Trader — Phase 6: Live Trading & Account Integration

## Scope completed

Phase 6 builds on the Phase 4 execution safety stack and the Phase 5 Pro Studio without replacing either one.

### Trading environments
- Added a dedicated Phase 6 trading hub.
- Paper trading remains fully local/simulated.
- Broker demo and live-money entry paths are visibly separated.
- Broker execution remains fail-closed and requires explicit enablement.

### MT4 / MT5 / Deriv
- Existing MetaApi broker adapter now exposes MT4 and MT5 selection in the connection UI.
- Added a Deriv broker seed for MT5 onboarding/search.
- Deriv support in this phase means a Deriv MT5 trading account connected through the existing MetaApi adapter. It is not a direct Deriv wallet/options API implementation.
- The exact broker server can still be entered manually because Deriv/MetaTrader server names can vary by account and region.

### Saved account selector
- Added password-free saved broker account profiles.
- A profile stores login, server, platform and display name only.
- Passwords are deliberately never persisted in the account selector.
- Selecting a saved profile pre-fills login/server/platform and still requires password entry.
- Up to 8 recent account profiles are retained in encrypted preferences.

### Critical account-switch safety fix
- MetaApi account IDs are now reused only when login + server + platform all match the currently selected profile.
- Switching broker profiles can no longer accidentally reuse the previous account ID.

### Account / position dashboard
- Connected account surface shows platform and inferred DEMO/LIVE account mode.
- Balance, equity, margin, free margin, leverage and currency remain visible.
- Refresh now reloads both account metrics and positions.
- Open positions, floating P&L and close actions remain available.

### Orders, SL/TP and execution safety
Existing Phase 4 safety controls remain in the live path:
- two-step user confirmation;
- persisted execution enable switch;
- emergency kill switch;
- stale quote rejection;
- broker-authoritative min/max/step volume validation when available;
- SL/TP direction validation;
- minimum free-margin gate;
- maximum daily realized-loss gate;
- idempotency / duplicate-submission protection;
- append-only execution audit receipts;
- UNKNOWN outcome classification and restart reconciliation;
- no automatic retry of ambiguous orders.

## Files added
- `app/src/main/java/com/foxtrader/app/feature/trading/presentation/Phase6TradingScreen.kt`
- `PHASE6_IMPLEMENTATION.md`

## Key files extended
- `domain/model/Mt4Account.kt`
- `domain/repository/Mt4Repository.kt`
- `domain/usecase/preferences/AppPreferences.kt`
- `domain/usecase/mt4/Mt4BrokerDirectory.kt`
- `data/repository/Mt4RepositoryImpl.kt`
- `feature/mt4/presentation/Mt4UiState.kt`
- `feature/mt4/presentation/Mt4ViewModel.kt`
- `feature/mt4/presentation/Mt4LoginScreen.kt`
- `feature/mt4/presentation/Mt4AccountScreen.kt`
- `feature/more/presentation/MoreScreen.kt`
- `ui/navigation/FoxNavHost.kt`

## Validation
- Source delimiter/static sanity checks: PASS.
- Navigation/action exhaustiveness manually checked for the new Phase 6 route.
- ZIP integrity is checked after packaging.
- Full Android Gradle compile could not run in this environment because the Gradle 8.9 distribution is not cached and `services.gradle.org` is unreachable from the sandbox. No compile success is claimed.

## Security note
The app does not save broker passwords in the saved-account selector. The MetaApi token and account profile metadata use the app's encrypted preference storage. Live-money execution still requires user opt-in and remains behind the existing execution-safety pipeline.
