package com.foxtrader.app.domain.sdk.marketplace

/**
 * Marketplace models for the plugin ecosystem (H4+).
 *
 * A curated store for indicators, drawing tools, strategies, and scripts
 * with review/signing, ratings, and revenue share.
 */

/** A publishable plugin in the marketplace. */
data class MarketplacePlugin(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val type: PluginType,
    val version: String,
    val rating: Double,          // 0.0 - 5.0
    val downloadCount: Int,
    val price: PluginPrice,
    val verified: Boolean,       // signed + reviewed
    val iconUrl: String? = null,
)

enum class PluginType {
    INDICATOR, DRAWING_TOOL, STRATEGY, SCRIPT, DATA_PROVIDER, BROKER,
}

sealed class PluginPrice {
    data object Free : PluginPrice()
    data class Paid(val amount: Double, val currency: String = "USD") : PluginPrice()
    data class Subscription(val monthlyAmount: Double, val currency: String = "USD") : PluginPrice()
}

/** User's installed plugin state. */
data class InstalledPlugin(
    val pluginId: String,
    val installedVersion: String,
    val enabled: Boolean,
    val installedAt: Long,
)
