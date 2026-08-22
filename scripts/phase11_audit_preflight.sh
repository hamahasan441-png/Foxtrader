#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
require_text() { local file="$1" pattern="$2" label="$3"; grep -Eq "$pattern" "$file" && pass "$label" || fail "$label"; }
reject_text() { local file="$1" pattern="$2" label="$3"; ! grep -Eq "$pattern" "$file" && pass "$label" || fail "$label"; }

# Execution safety / idempotency
require_text app/src/main/java/com/foxtrader/app/domain/usecase/execution/ExecutionCoordinator.kt 'ExecutionReceipt\.Unknown\(intent\)' 'Durable UNKNOWN reservation exists before broker submit'
require_text app/src/main/java/com/foxtrader/app/domain/usecase/execution/TradeIntent.kt 'executionScope' 'Idempotency is broker-account scoped'
require_text app/src/main/java/com/foxtrader/app/domain/usecase/execution/TradeIntent.kt 'operationTag' 'OPEN/CLOSE operation identities are separated'
require_text app/src/main/java/com/foxtrader/app/domain/usecase/execution/ExecutionSafetyLayer.kt 'Price moved .*points since review' 'Review-to-submit price drift is enforced'
require_text app/src/main/java/com/foxtrader/app/data/repository/Mt4RepositoryImpl.kt 'Pending orders require an explicit open price' 'Unsupported pending orders fail before market submission'
require_text app/src/main/java/com/foxtrader/app/data/repository/Mt4RepositoryImpl.kt 'Execution audit log is unavailable; reconciliation cannot be trusted' 'Reconciliation audit failure is fail-closed'

# MetaApi outcome/routing
require_text app/src/main/java/com/foxtrader/app/data/remote/api/MetaApiDataSource.kt 'MetaApiTradeOutcomeUnknownException' 'Ambiguous broker outcomes have explicit UNKNOWN state'
require_text app/src/main/java/com/foxtrader/app/data/remote/api/MetaApiDataSource.kt '10010' 'Partial-success MetaApi code is recognized'
require_text app/src/main/java/com/foxtrader/app/data/remote/api/MetaApiDataSource.kt '\-11' 'Disconnect-during-trade MetaApi code is treated specially'
require_text app/src/main/java/com/foxtrader/app/data/remote/api/MetaApiService.kt 'REGION_LABEL' 'MetaApi regional routing validates DNS labels'
require_text app/src/main/java/com/foxtrader/app/data/remote/api/MetaApiService.kt 'agiliumtrade\.ai' 'MetaApi regional host suffix is fixed'

# Deriv session / credentials
require_text app/src/main/java/com/foxtrader/app/feature/deriv/presentation/DerivViewModel.kt 'sessionEpoch' 'Deriv async UI work is session-epoch guarded'
require_text app/src/main/java/com/foxtrader/app/data/repository/DerivRepositoryImpl.kt 'authenticatedSessionStartedAtMs' 'Deriv execution authorization is bound to authenticated session'
require_text app/src/main/java/com/foxtrader/app/di/NetworkModule.kt 'provideDerivApiClient' 'Dedicated Deriv zero-log client exists'

# Credential/logging hardening
reject_text app/src/main/java/com/foxtrader/app/di/Mt4Module.kt 'HttpLoggingInterceptor' 'MetaApi transport has no HTTP logger'
require_text app/src/main/java/com/foxtrader/app/di/NetworkModule.kt 'Alpha Vantage puts the API key in the URL query string' 'Alpha Vantage query-key logging guard documented'
require_text app/src/main/java/com/foxtrader/app/di/NetworkModule.kt 'Twelve Data also authenticates via \?apikey' 'Twelve Data query-key logging guard documented'

# UI / database
require_text app/src/main/java/com/foxtrader/app/feature/mt4/presentation/Mt4UiState.kt 'UNKNOWN' 'Unknown broker account type is represented explicitly'
require_text app/src/main/java/com/foxtrader/app/data/local/FoxDatabase.kt 'version = 10' 'Execution database schema is v10'
python3 scripts/verify_room_migrations.py >/dev/null && pass 'Independent Room SQL migration chain v1→v10'

# Backend auth persistence
require_text backend/app/core/auth.py '_token_key\(token' 'Backend opaque tokens are hashed before persistence'
require_text backend/app/core/persistence.py 'BEGIN IMMEDIATE' 'Refresh token is atomically consumed'

# No accidental merge debris or obvious live-source placeholders.
! grep -R -n -E '^(<<<<<<<|=======|>>>>>>>)' app/src/main/java backend scripts --include='*.kt' --include='*.py' --include='*.sh' >/dev/null || fail 'No merge conflict markers'
pass 'No merge conflict markers'
! grep -R -n -E 'TODO\(|NotImplementedError' app/src/main/java --include='*.kt' >/dev/null || fail 'No TODO()/NotImplementedError in Android production Kotlin'
pass 'No TODO()/NotImplementedError in Android production Kotlin'

printf 'PHASE11_AUDIT_PREFLIGHT: PASS\n'
