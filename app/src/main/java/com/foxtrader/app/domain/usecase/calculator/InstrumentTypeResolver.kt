package com.foxtrader.app.domain.usecase.calculator

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.AssetClassifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a symbol to the [PositionCalculator.InstrumentType] whose contract size
 * and pip size actually apply to it.
 *
 * This matters more than it looks. `InstrumentType` drives both `pipSize` and
 * `contractSize`, and those feed position size directly — defaulting everything
 * to `FOREX_STANDARD` (pip 0.0001, contract 100k) would size a gold or index
 * trade by two orders of magnitude wrong, in the one calculation a trader
 * trusts to keep them solvent.
 */
@Singleton
class InstrumentTypeResolver @Inject constructor() {

    fun resolve(rawSymbol: String): PositionCalculator.InstrumentType {
        val symbol = rawSymbol.trim().uppercase()
        if (symbol.isEmpty()) return PositionCalculator.InstrumentType.FOREX_STANDARD

        return when (AssetClassifier.classify(symbol)) {
            AssetClass.METALS -> PositionCalculator.InstrumentType.GOLD
            AssetClass.ENERGY -> PositionCalculator.InstrumentType.OIL
            AssetClass.INDICES -> PositionCalculator.InstrumentType.INDEX_STANDARD
            AssetClass.CRYPTO ->
                // BTC moves in whole dollars; alts need cent granularity, so
                // sharing one pip size would misprice both.
                if (symbol.startsWith("BTC")) {
                    PositionCalculator.InstrumentType.CRYPTO_BTC
                } else {
                    PositionCalculator.InstrumentType.CRYPTO_ALT
                }

            AssetClass.FOREX ->
                // JPY quotes are 2-decimal: a pip is 0.01, not 0.0001.
                if (symbol.endsWith("JPY")) {
                    PositionCalculator.InstrumentType.FOREX_JPY
                } else {
                    PositionCalculator.InstrumentType.FOREX_STANDARD
                }

            AssetClass.STOCKS, AssetClass.COMMODITIES ->
                // A share is one unit priced in whole currency, which is what
                // INDEX_STANDARD already models (contract 1, pip 1).
                PositionCalculator.InstrumentType.INDEX_STANDARD
        }
    }
}
