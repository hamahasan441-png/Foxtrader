# FoxTrader Phase 1 Hotfix

Date: 2026-08-21

This hotfix targets the chart/data problems visible in the supplied screenshot.

## Fixed in this package

1. **Real-data-first default for the default EURUSD chart**
   - The initial provider is now Dukascopy instead of SAMPLE.
   - This prevents a fresh install from immediately opening EURUSD on fabricated bars.

2. **Synthetic/real candle contamination**
   - After a successful real provider fetch, old `SYNTHETIC` rows for the same symbol/timeframe are removed before real candles are committed.
   - Prevents synthetic bars from remaining beside real bars and creating an apparent second candle track / wrong price regime.
   - Prevents the whole series from staying labelled `SYNTHETIC` after connectivity returns.

3. **Synthetic history discontinuity**
   - Deeper-history pagination is disabled while the active series is synthetic.
   - Independently generated random-walk pages are no longer prepended to each other, avoiding discontinuous synthetic price tracks when zoomed out.

4. **Incorrect Twelve Data live capability**
   - Twelve Data remains available for REST candles, but it is no longer advertised as live because no Twelve Data streaming socket is wired into `ProviderMarketWebSocket` yet.

## Validation status

Static source inspection completed. A full Gradle compile could not run in the execution environment because the Gradle 8.9 distribution is not cached and `services.gradle.org` is unreachable from the sandbox.

## Next phase

- Add symbol-aware automatic provider routing/failover.
- Add a genuine live Forex stream (not synthetic or historical polling).
- Add data freshness age/status (`LIVE`, `DELAYED`, `CACHED`, `SIMULATED`) to the chart header.
- Trace indicator/strategy activation end-to-end and guarantee selected studies render on-chart.
- Add chart render regression tests for duplicate/discontinuous price tracks.
