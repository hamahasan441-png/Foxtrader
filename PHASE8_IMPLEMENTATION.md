# FOX Trader — Phase 8: Production Hardening, QA & Release Readiness

## Implemented

- Added a deterministic `ReleaseReadinessEvaluator` with PASS / WARNING / BLOCKER results.
- Production blockers include missing release signing, cleartext traffic, release HTTP logging, unencrypted credentials, unattended live-money execution, failed unit tests, and failed lint.
- Added `SensitiveDataRedactor` for bearer tokens, passwords, access/refresh tokens, API keys, authorization values, and common JSON secret fields.
- Hardened `RemoteCrashReporter` context metadata: bounded entry count/key/value sizes, safe key characters, and secret redaction before remote transmission.
- Added Phase 8 **Release readiness** UI under More → Live trading.
- Added per-build `BuildConfig.FOXTRADER_RELEASE_SIGNING_READY`; release never silently claims signing is configured.
- Added `scripts/release_preflight.sh` for static production checks and committed-secret heuristics.
- Preserved Phase 7 fail-closed rule: unattended automation is never eligible for LIVE execution.

## Release gate semantics

The runtime dashboard intentionally leaves unit-test and Android-lint status as WARNING because an installed APK cannot truthfully infer CI results. CI/Gradle must execute those gates. A release should only be promoted after the preflight script, unit tests, lint, and release assembly/bundle all pass in a networked Android build environment.

Suggested release sequence:

```bash
./scripts/release_preflight.sh
./gradlew clean testDebugUnitTest lintRelease assembleRelease
# or: ./gradlew clean testDebugUnitTest lintRelease bundleRelease
```

For a production-signed build, provide the signing environment variables already consumed by `app/build.gradle.kts`.

## Validation performed in this environment

- Phase 8 pure Kotlin compilation: PASS.
- Release-readiness behavioral smoke test: PASS.
- Sensitive-data redaction behavioral smoke test: PASS.
- Release preflight script syntax: PASS.
- Release preflight static checks: PASS.
- Navigation / MoreAction mapping static sanity: PASS.
- AndroidManifest XML parse: PASS.
- Full Gradle/Android build: NOT EXECUTED because the Gradle 8.9 wrapper distribution is not cached and `services.gradle.org` cannot be resolved from this sandbox.

No claim of a full Android compile or signed APK is made.
