package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketType
import com.foxtrader.app.domain.model.ProviderMarketSymbol
import javax.inject.Inject
import javax.inject.Singleton

data class DukascopyInstrument(
    val providerSymbol: String,
    val apiCode: String,
    val canonicalSymbol: String,
    val displayName: String,
    val assetClass: AssetClass,
    val pricePrecision: Int,
    val pipSize: Double,
    val bi5PointValue: Double,
    val aliases: Set<String> = emptySet(),
)

/** Curated, deterministic directory for Dukascopy's free public data service. */
@Singleton
class DukascopyInstrumentCatalog @Inject constructor() {

    private val instruments = buildList {
        FOREX_PAIRS.forEach { pair ->
            add(
                DukascopyInstrument(
                    providerSymbol = pair,
                    apiCode = "${pair.take(3)}-${pair.drop(3)}",
                    canonicalSymbol = pair,
                    displayName = "${pair.take(3)}/${pair.drop(3)}",
                    assetClass = AssetClass.FOREX,
                    pricePrecision = if (pair.endsWith("JPY")) 3 else 5,
                    pipSize = if (pair.endsWith("JPY")) 0.01 else 0.0001,
                    bi5PointValue = if (pair.endsWith("JPY")) 1_000.0 else 100_000.0,
                )
            )
        }
        add(metal("XAUUSD", "Gold / US Dollar", 3, 0.01))
        add(metal("XAGUSD", "Silver / US Dollar", 3, 0.001))
        add(index("USA30IDXUSD", "USA30.IDX-USD", "US30", "US 30 Index", setOf("DJ30", "DOW30")))
        add(index("USATECHIDXUSD", "USATECH.IDX-USD", "NAS100", "US 100 Tech Index", setOf("US100", "USTEC")))
        add(index("USA500IDXUSD", "USA500.IDX-USD", "SP500", "US 500 Index", setOf("US500", "SPX500")))
        add(index("DEUIDXEUR", "DEU.IDX-EUR", "GER40", "Germany 40 Index", setOf("DE40", "DAX40", "DAX")))
        add(index("GBRIDXGBP", "GBR.IDX-GBP", "UK100", "FTSE 100 Index", setOf("FTSE100")))
        add(index("JPNIDXJPY", "JPN.IDX-JPY", "JP225", "Japan 225 Index", setOf("JPN225", "NIKKEI225")))
        add(index("AUSIDXAUD", "AUS.IDX-AUD", "AUS200", "Australia 200 Index"))
        add(index("HKGIDXHKD", "HKG.IDX-HKD", "HK50", "Hong Kong Index", setOf("HKG50")))
    }

    private val byAlias = buildMap {
        instruments.forEach { instrument ->
            val keys = instrument.aliases + instrument.providerSymbol + instrument.canonicalSymbol + instrument.apiCode
            keys.forEach { put(normalize(it), instrument) }
        }
    }

    fun resolve(symbol: String): DukascopyInstrument? = byAlias[normalize(symbol)]

    fun require(symbol: String): DukascopyInstrument = resolve(symbol)
        ?: throw IllegalArgumentException("Dukascopy does not support $symbol in FoxTrader's verified directory.")

    fun discoverSymbols(): List<ProviderMarketSymbol> = instruments.map { instrument ->
        ProviderMarketSymbol(
            provider = DataProvider.DUKASCOPY,
            providerSymbol = instrument.providerSymbol,
            canonicalSymbol = instrument.canonicalSymbol,
            displayName = instrument.displayName,
            assetClass = instrument.assetClass,
            marketType = MarketType.CFD,
            baseAsset = instrument.canonicalSymbol.takeIf { instrument.assetClass != AssetClass.INDICES }?.take(3),
            quoteAsset = instrument.canonicalSymbol.takeIf { instrument.assetClass != AssetClass.INDICES }?.drop(3),
            pricePrecision = instrument.pricePrecision,
            pipSize = instrument.pipSize,
            category = when (instrument.assetClass) {
                AssetClass.FOREX -> "Forex"
                AssetClass.METALS -> "Metals"
                AssetClass.INDICES -> "Indices"
                else -> "Dukascopy"
            },
        )
    }

    private fun metal(symbol: String, name: String, precision: Int, pipSize: Double) = DukascopyInstrument(
        providerSymbol = symbol,
        apiCode = "${symbol.take(3)}-${symbol.drop(3)}",
        canonicalSymbol = symbol,
        displayName = name,
        assetClass = AssetClass.METALS,
        pricePrecision = precision,
        pipSize = pipSize,
        bi5PointValue = 1_000.0,
    )

    private fun index(
        providerSymbol: String,
        apiCode: String,
        canonicalSymbol: String,
        name: String,
        aliases: Set<String> = emptySet(),
    ) = DukascopyInstrument(
        providerSymbol = providerSymbol,
        apiCode = apiCode,
        canonicalSymbol = canonicalSymbol,
        displayName = name,
        assetClass = AssetClass.INDICES,
        pricePrecision = 3,
        pipSize = 0.1,
        bi5PointValue = 1_000.0,
        aliases = aliases,
    )

    private fun normalize(symbol: String): String = symbol.trim().uppercase()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
        .replace(".", "")
        .replace(" ", "")

    private companion object {
        val FOREX_PAIRS = listOf(
            "AUDCAD", "AUDCHF", "AUDJPY", "AUDNZD", "AUDUSD",
            "CADCHF", "CADJPY", "CHFJPY",
            "EURAUD", "EURCAD", "EURCHF", "EURGBP", "EURJPY", "EURNZD", "EURUSD",
            "GBPAUD", "GBPCAD", "GBPCHF", "GBPJPY", "GBPNZD", "GBPUSD",
            "NZDCAD", "NZDCHF", "NZDJPY", "NZDUSD",
            "USDCAD", "USDCHF", "USDJPY",
        )
    }
}
