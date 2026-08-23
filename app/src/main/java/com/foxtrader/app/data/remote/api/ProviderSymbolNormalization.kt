package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketType
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import java.math.BigDecimal

/** Shared normalization helpers for provider-native instrument directories. */
internal object ProviderSymbolNormalization {

    fun cryptoSpot(
        provider: DataProvider,
        providerSymbol: String,
        baseAsset: String,
        quoteAsset: String,
        displayName: String? = null,
        tickSizeText: String? = null,
        category: String? = null,
        isTrading: Boolean,
    ): ProviderMarketSymbol? {
        val exact = providerSymbol.trim()
        val base = baseAsset.trim().uppercase()
        val quote = quoteAsset.trim().uppercase()
        if (exact.isBlank() || base.isBlank() || quote.isBlank()) return null
        val tick = positiveDoubleOrNull(tickSizeText)
        return ProviderMarketSymbol(
            provider = provider,
            providerSymbol = exact,
            canonicalSymbol = base + quote,
            displayName = displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "$base/$quote",
            assetClass = AssetClass.CRYPTO,
            marketType = MarketType.SPOT,
            baseAsset = base,
            quoteAsset = quote,
            pricePrecision = decimalPrecision(tickSizeText),
            tickSize = tick,
            pipSize = null,
            category = category,
            isTrading = isTrading,
        )
    }

    fun decimalPrecision(step: String?): Int? {
        val raw = step?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            BigDecimal(raw).stripTrailingZeros().scale().coerceAtLeast(0)
        }.getOrNull()
    }

    fun positiveDoubleOrNull(value: String?): Double? =
        value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}
