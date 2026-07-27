package com.foxtrader.app.domain.model

/**
 * Thrown when the user has selected a [DataProvider] that has no working
 * candle-fetch implementation.
 *
 * This is deliberately a hard failure. The previous behaviour was to fall
 * through to the default fetch path, fail, and land in the synthetic-data
 * seeder — so selecting "Polygon.io" and pasting a paid API key produced a
 * chart of fabricated prices that looked exactly like real ones. Failing
 * loudly with an actionable message is strictly safer than degrading quietly.
 */
class ProviderNotImplementedException(
    providerName: String,
) : IllegalStateException(
    "$providerName is not implemented yet. Open Settings → Data Provider and " +
        "choose one of: ${DataProvider.implemented().joinToString { it.displayName }}."
)
