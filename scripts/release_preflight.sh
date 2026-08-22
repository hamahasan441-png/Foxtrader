#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0
say() { printf '%-44s %s\n' "$1" "$2"; }

if grep -q 'android:usesCleartextTraffic="false"' app/src/main/AndroidManifest.xml; then
  say "Cleartext traffic disabled" "PASS"
else
  say "Cleartext traffic disabled" "FAIL"; fail=1
fi

if grep -q 'HttpLoggingInterceptor.Level.NONE' app/src/main/java/com/foxtrader/app/di/NetworkModule.kt; then
  say "Release HTTP logging disabled" "PASS"
else
  say "Release HTTP logging disabled" "FAIL"; fail=1
fi

if grep -q 'EncryptedSharedPreferences' app/src/main/java/com/foxtrader/app/data/auth/TokenManager.kt; then
  say "Encrypted token storage" "PASS"
else
  say "Encrypted token storage" "FAIL"; fail=1
fi

if grep -q 'AutomationEnvironment.LIVE' app/src/main/java/com/foxtrader/app/domain/usecase/execution/Phase7AutomationEngine.kt; then
  say "Live automation guard present" "PASS"
else
  say "Live automation guard present" "FAIL"; fail=1
fi

# Catch likely committed secrets without matching documentation placeholders.
if grep -RInE --exclude-dir=.git --exclude='*.md' --exclude='*.txt' \
  '(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)[[:space:]]*=[[:space:]]*"[A-Za-z0-9._-]{12,}"' \
  app/src/main/java >/tmp/foxtrader-secret-scan.txt 2>/dev/null; then
  say "Committed-secret heuristic" "FAIL"
  cat /tmp/foxtrader-secret-scan.txt
  fail=1
else
  say "Committed-secret heuristic" "PASS"
fi

if [[ "${FOXTRADER_REQUIRE_PRODUCTION_CONFIG:-0}" == "1" && -z "${FOXTRADER_BASE_URL:-}" ]]; then
  say "FOXTRADER_BASE_URL configured" "FAIL"; fail=1
elif [[ -n "${FOXTRADER_BASE_URL:-}" && "${FOXTRADER_BASE_URL}" != https://* ]]; then
  say "FOXTRADER_BASE_URL uses HTTPS" "FAIL"; fail=1
else
  say "FOXTRADER_BASE_URL uses HTTPS" "PASS"
fi

if [[ "${FOXTRADER_REQUIRE_SIGNING:-0}" == "1" ]]; then
  for v in FOXTRADER_KEYSTORE_PATH FOXTRADER_KEYSTORE_PASSWORD FOXTRADER_KEY_ALIAS FOXTRADER_KEY_PASSWORD; do
    if [[ -z "${!v:-}" ]]; then say "$v" "MISSING"; fail=1; else say "$v" "SET"; fi
  done
fi

exit "$fail"
