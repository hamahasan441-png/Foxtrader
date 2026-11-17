#!/usr/bin/env bash
# Run a Gradle command. Retry only when plugin resolution itself failed
# (the red check on PR #204). A real test or compile failure is returned
# on the first attempt.
set -uo pipefail

attempt=1
code=1
while [ "$attempt" -le 3 ]; do
  log="gradle-ci-${attempt}.log"
  ./gradlew "$@" --stacktrace --no-daemon --no-configuration-cache 2>&1 | tee "$log"
  code=${PIPESTATUS[0]}
  if [ "$code" -eq 0 ]; then
    exit 0
  fi
  if ! grep -q "UnknownPluginException\|could not resolve plugin artifact" "$log"; then
    exit "$code"
  fi
  if [ "$attempt" -eq 3 ]; then
    exit "$code"
  fi
  echo "::warning::Gradle plugin resolution failed on attempt ${attempt}. Retrying."
  attempt=$((attempt + 1))
  sleep 20
done
exit "$code"
