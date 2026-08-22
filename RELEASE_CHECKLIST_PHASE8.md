# FOX Trader Release Checklist

- [ ] Run `./scripts/release_preflight.sh`.
- [ ] Supply production `FOXTRADER_BASE_URL` and certificate pins if the FoxTrader backend is used.
- [ ] Supply release signing variables; never use the debug signing key for production.
- [ ] Run `./gradlew clean testDebugUnitTest` and resolve failures.
- [ ] Run `./gradlew lintRelease` and resolve release-blocking lint findings.
- [ ] Run `./gradlew assembleRelease` or `bundleRelease` with R8/resource shrinking enabled.
- [ ] Install release build on a physical Android 10+ device.
- [ ] Test cold start, process death/restore, background/foreground, and network loss/reconnect.
- [ ] Test chart → confirmed signal → Phase 7 queue → Phase 6 review → order → SL/TP → close → audit/journal on Paper.
- [ ] Repeat broker path on a broker Demo account before any live-money validation.
- [ ] Verify kill switch, stale-price rejection, duplicate/idempotency protection, min/max/step volume rules, daily-loss gate, and free-margin gate.
- [ ] Confirm LIVE never auto-fires from Phase 7.
- [ ] Verify saved account profiles contain no password or token material.
- [ ] Verify crash/ANR telemetry opt-in and secret redaction.
- [ ] Run long-duration chart/scanner/replay test and inspect memory/leaks.
- [ ] Archive source ZIP, mapping file, signed artifact checksum, changelog, and validation outputs for the release.
