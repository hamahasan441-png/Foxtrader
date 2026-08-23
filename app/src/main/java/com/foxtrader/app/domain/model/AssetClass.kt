package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/** Generic market asset classes used by watchlists, portfolio and market tooling. */
@Serializable
enum class AssetClass {
    FOREX,
    CRYPTO,
    STOCKS,
    INDICES,
    METALS,
    ENERGY,
    COMMODITIES,
    /** Provider-native synthetic markets such as Deriv synthetic indices. */
    SYNTHETIC,
    /** Explicit fallback when a provider cannot classify an instrument safely. */
    OTHER,
}
