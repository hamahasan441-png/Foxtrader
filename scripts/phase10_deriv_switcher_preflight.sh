#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

VM='app/src/main/java/com/foxtrader/app/feature/deriv/presentation/DerivViewModel.kt'
STATE='app/src/main/java/com/foxtrader/app/feature/deriv/presentation/DerivUiState.kt'
SCREEN='app/src/main/java/com/foxtrader/app/feature/deriv/presentation/Phase9DerivScreen.kt'
STORE='app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivCredentialStore.kt'
REPO='app/src/main/java/com/foxtrader/app/data/repository/DerivRepositoryImpl.kt'

for f in "$VM" "$STATE" "$SCREEN" "$STORE" "$REPO"; do [[ -f "$f" ]] || fail "Missing $f"; done

# Credential edits must invalidate the prior identity/session.
grep -q 'credentialsDirty = true' "$VM" && grep -q 'repository.disconnect()' "$VM" || fail 'Credential edits do not invalidate session'
pass 'Credential edits invalidate old Deriv session'

# Dirty credential state can never count as authenticated.
grep -q '!credentialsDirty && selectedAccount != null' "$STATE" || fail 'Dirty credentials can still authenticate'
pass 'Dirty API configuration is not authenticated'

# Save after verification only: getAccounts appears before saveCredentials in apply flow.
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/java/com/foxtrader/app/feature/deriv/presentation/DerivViewModel.kt').read_text()
a=s.index('fun applyCredentials()')
b=s.index('fun revertCredentials()', a)
block=s[a:b]
if block.index('repository.getAccounts') > block.index('repository.saveCredentials'):
    raise SystemExit(1)
if 'current.appId != state.appId || current.token != state.token' not in block:
    raise SystemExit(2)
PY
pass 'API credentials are verified and race-checked before persistence'

# Changing credential identity must drop old remembered account id.
grep -q 'if (credentialsChanged) remove(KEY_ACCOUNT_ID)' "$STORE" || fail 'Account id persists across credential replacement'
pass 'Old saved account id is cleared on API identity change'

# Repository has defense-in-depth disconnect on credential replacement.
grep -q 'val changed = credentials.appId() != normalizedAppId || credentials.token() != normalizedToken' "$REPO" || fail 'Repository credential change detection missing'
grep -A12 'override fun saveCredentials' "$REPO" | grep -q 'disconnect()' || fail 'Repository does not disconnect on replacement'
pass 'Repository enforces credential replacement boundary'

# Account switch UI is explicit and dirty state disables switching.
grep -q 'Switch to REAL account' "$SCREEN" && grep -q 'Switch to demo account' "$SCREEN" || fail 'Switch-account UI missing'
grep -q '!state.credentialsDirty && !isConnectedAccount' "$SCREEN" || fail 'Account switch not gated on clean API config'
pass 'Explicit safe account switching UI'

# Revert + apply controls exist.
grep -q 'Apply API & verify accounts' "$SCREEN" && grep -q 'Revert API' "$SCREEN" || fail 'API apply/revert controls missing'
pass 'In-app Deriv API replace/revert controls'

# Deriv encrypted preferences must not be backed up/transferred.
for f in app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml; do
  grep -q 'fox_deriv_credentials.xml' "$f" || fail "Deriv credential store missing backup exclusion in $f"
done
pass 'Deriv credential store excluded from backup/device transfer'

# Regression: duplicate Phase9 local declaration must be gone.
count=$(grep -c 'val profit = repository.profitTable' "$VM" || true)
[[ "$count" -eq 1 ]] || fail "Expected one profit declaration, found $count"
pass 'Pre-existing duplicate profit declaration fixed'

# REAL execution confirmation contract must remain present.
grep -q 'accountType == DerivAccountType.DEMO' app/src/main/java/com/foxtrader/app/domain/model/deriv/DerivModels.kt || fail 'DEMO/REAL authorization boundary missing'
grep -q 'account.accountId != accountId' app/src/main/java/com/foxtrader/app/domain/model/deriv/DerivModels.kt || fail 'Account-bound authorization guard missing'
grep -q 'userConfirmed' app/src/main/java/com/foxtrader/app/domain/model/deriv/DerivModels.kt || fail 'Manual confirmation guard missing'
pass 'REAL manual-confirmation safety preserved'
