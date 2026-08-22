#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail=0
pass() { printf 'PASS: %s\n' "$1"; }
block() { printf 'BLOCK: %s\n' "$1"; fail=1; }

REST="app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivRestClient.kt"
WS="app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivWebSocketClient.kt"
BUILD="app/src/main/java/com/foxtrader/app/domain/usecase/deriv/DerivRequestBuilder.kt"
REPO="app/src/main/java/com/foxtrader/app/data/repository/DerivRepositoryImpl.kt"
NET="app/src/main/java/com/foxtrader/app/di/NetworkModule.kt"
NAV="app/src/main/java/com/foxtrader/app/ui/navigation/FoxNavHost.kt"

if grep -q 'https://api.derivws.com' "$REST" && grep -q 'wss://api.derivws.com/trading/v1/options/ws/public' "$WS"; then pass 'New Deriv REST + public WebSocket hosts'; else block 'New Deriv hosts missing'; fi
if ! grep -R -q 'ws.binaryws.com\|api.deriv.com' app/src/main/java/com/foxtrader/app/data/remote/deriv app/src/main/java/com/foxtrader/app/domain/usecase/deriv; then pass 'No legacy Deriv transport host in native layer'; else block 'Legacy Deriv host found'; fi
if grep -q 'put("underlying_symbol"' "$BUILD" && ! grep -q 'put("loginid"' "$BUILD"; then pass 'New proposal/account-context field rules'; else block 'Legacy proposal/loginid request field found'; fi
if grep -q '@DerivApiClient private val client' "$REST" && grep -q '@DerivApiClient private val client' "$WS"; then pass 'Dedicated zero-log Deriv client injected'; else block 'Deriv transport not isolated'; fi
DERIV_PROVIDER="$(awk '/fun provideDerivApiClient/{flag=1} flag{print} flag && /\.build\(\)/{exit}' "$NET")"
if ! grep -q 'HttpLoggingInterceptor' <<<"$DERIV_PROVIDER"; then pass 'Deriv client has no HTTP logger (OTP URL protected)'; else block 'Deriv client logs HTTP'; fi
if grep -q 'EncryptedSharedPreferences' app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivCredentialStore.kt; then pass 'Deriv bearer token encrypted at rest'; else block 'Encrypted credential store missing'; fi
if grep -q 'authorization.canSubmitFor(account, now)' "$REPO" && grep -q 'confirmationEpochMs >= sessionStartedAt' "$REPO" && grep -q 'account.accountId != accountId' app/src/main/java/com/foxtrader/app/domain/model/deriv/DerivModels.kt; then pass 'REAL execution freshness/account/session gate'; else block 'REAL execution gate missing'; fi
if grep -q 'DERIV_NATIVE' "$NAV" && grep -q 'Phase9DerivScreen' "$NAV"; then pass 'Phase 9 navigation route'; else block 'Phase 9 navigation missing'; fi
if grep -q 'reset-demo-balance' "$REST" && grep -q '/v1/health' "$REST"; then pass 'Health + demo lifecycle REST endpoints'; else block 'Health/demo lifecycle endpoints missing'; fi
if grep -q 'contractUpdateHistory' "$REPO" && grep -q 'DerivRequestBuilder.sell' "$REPO" && grep -q 'DerivRequestBuilder.contractUpdate' "$REPO"; then pass 'Position management: history/sell/update'; else block 'Position management incomplete'; fi
if grep -q 'DerivRequestBuilder.profitTable' "$REPO" && grep -q 'DerivRequestBuilder.statement' "$REPO"; then pass 'Authenticated account history: profit table + statement'; else block 'Account history endpoints missing'; fi
if grep -q '/wallet/v1/wallets' "$REST" && grep -q '/wallet/v1/transactions/' "$REST" && grep -q 'WALLET_TYPES' "$REST"; then pass 'Read-only wallet visibility + transaction history'; else block 'Wallet read layer missing'; fi
if grep -q 'walletTransactionsPage' "$REST" && grep -q 'normalizeWalletPageUrl' "$REST" && grep -q 'api.derivws.com' "$REST"; then pass 'Wallet cursor pagination constrained to Deriv origin'; else block 'Wallet pagination origin guard missing'; fi
if grep -q 'Current Deriv Options account schema supports USD' "$REST" && grep -q 'Current Deriv Options account schema supports the row group' "$REST"; then pass 'Create-demo schema pinned to production OpenAPI'; else block 'Create-demo request too permissive'; fi
if grep -q 'URI(url)' "$REST" && grep -q 'isExpectedAuthenticatedUrl' "$WS"; then pass 'OTP WebSocket URL exact host/path validation'; else block 'OTP URL validation incomplete'; fi
if ! grep -R -q '/wallet/v1/transfers\|bulk-purchase/real\|auto_start\|"auto_start"' app/src/main/java/com/foxtrader/app; then pass 'No wallet transfer / bulk REAL / unattended auto-start path'; else block 'Unsafe money-movement or bulk live path present'; fi

exit "$fail"
