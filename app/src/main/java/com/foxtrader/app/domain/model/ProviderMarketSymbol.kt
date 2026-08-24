package com.foxtrader.app.domain.model

/**
 * Provider-native instrument discovered from the venue itself.
 *
 * [providerSymbol] is the exact value that must be sent back to that provider.
 * [canonicalSymbol] is a cross-provider comparison/search key only; it must never
 * replace [providerSymbol] on network requests.
 */
data class ProviderMarketSymbol(
    val provider: DataProvider,
    val providerSymbol: String,
    val canonicalSymbol: String?,
    val displayName: String,
    val assetClass: AssetClass,
    val marketType: MarketType,
    val baseAsset: String? = null,
    val quoteAsset: String? = null,
    val pricePrecision: Int? = null,
    val tickSize: Double? = null,
    val pipSize: Double? = null,
    val category: String? = null,
    val isTrading: Boolean = true,
) {
    init {
        require(providerSymbol.isNotBlank()) { "Provider symbol must not be blank" }
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        require(pricePrecision == null || pricePrecision >= 0) { "Price precision must be non-negative" }
        require(tickSize == null || (tickSize.isFinite() && tickSize > 0.0)) { "Tick size must be positive" }
        require(pipSize == null || (pipSize.isFinite() && pipSize > 0.0)) { "Pip size must be positive" }
    }
}

enum class MarketType {
    SPOT,
    PERPETUAL,
    FUTURE,
    OPTION,
    CFD,
    /** Provider-specific synthetic market/underlying. */
    SYNTHETIC,
    /** Tradable underlying used by provider derivative contracts. */
    DERIVATIVE_UNDERLYING,
    OTHER,
}
