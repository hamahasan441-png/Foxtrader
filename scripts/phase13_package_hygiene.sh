#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0
bad_file() { printf 'FAIL: forbidden package file: %s\n' "$1" >&2; fail=1; }
while IFS= read -r -d '' f; do bad_file "$f"; done < <(
  find . -type f \( \
    -name '.env' -o -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' \
    -o -name '*.pem' -o -name '*.key' -o -name '*.db' -o -name '*.sqlite' -o -name '*.sqlite3' \
  \) -print0
)
while IFS= read -r -d '' d; do printf 'FAIL: forbidden package directory: %s\n' "$d" >&2; fail=1; done < <(
  find . -type d \( -name '__pycache__' -o -name '.pytest_cache' -o -name '.gradle' \) -print0
)
if find . -type f -path '*/build/*' -print -quit | grep -q .; then
  printf 'FAIL: build output present\n' >&2; fail=1
fi
if (( fail )); then
  echo 'PHASE13_PACKAGE_HYGIENE: FAIL' >&2
  exit 1
fi
echo 'PHASE13_PACKAGE_HYGIENE: PASS'
