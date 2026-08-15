package com.foxtrader.app.domain.usecase.mt4

import com.foxtrader.app.domain.model.Mt4Broker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Curated, searchable directory of well-known MT4 brokers and their server
 * names. This exists so the MT4 login form can auto-fill the exact `server`
 * string MetaApi requires, instead of relying on the user to type it.
 *
 * The list is intentionally a representative seed, not an exhaustive registry.
 * It can be extended over time without touching the UI.
 */
@Singleton
class Mt4BrokerDirectory @Inject constructor() {

    private val brokers: List<Mt4Broker> = buildBrokerList()

    /** All known brokers. */
    fun all(): List<Mt4Broker> = brokers

    /**
     * Returns brokers matching [query] by name or server, in display order.
     * A blank query returns the whole directory.
     */
    fun search(query: String): List<Mt4Broker> =
        if (query.isBlank()) brokers else brokers.filter { it.matches(query) }

    private fun buildBrokerList(): List<Mt4Broker> = listOf(
        Mt4Broker("IC Markets", listOf("ICMarkets-Live", "ICMarkets-Demo", "ICMarketsSC-Demo"), "Raw Spread + Standard, AU-regulated", "Australia"),
        Mt4Broker("Pepperstone", listOf("Pepperstone-MT4-Live", "Pepperstone-Demo"), "FX, indices, commodities, AU-regulated", "Australia"),
        Mt4Broker("Exness", listOf("Exness-MT4Real", "Exness-MT4Demo", "Exness-Real"), "Flexible leverage, global clients", "International"),
        Mt4Broker("XM", listOf("XMGlobal-MT4 Real", "XMGlobal-MT4 Demo", "XMGlobal-MT4"), "Forex, CFD, multiple account types", "International"),
        Mt4Broker("FXGT", listOf("FXGT-Live", "FXGT-Demo"), "Crypto + forex CFDs", "International"),
        Mt4Broker("FXTM", listOf("ForexTime-Live", "ForexTime-Demo", "ForexTime-MT4"), "Forex & CFDs, multi-platform", "International"),
        Mt4Broker("FBS", listOf("FBS-Real", "FBS-Demo", "FBS-Real-4"), "Cent & standard accounts", "International"),
        Mt4Broker("XM Global", listOf("XMGlobal-MT4 Real"), "Forex, CFD, multiple account types", "International"),
        Mt4Broker("Axi", listOf("AxiTrader-Live", "AxiTrader-Demo", "AxiTrader-MT4"), "Forex & CFDs, UK/AU regulated", "United Kingdom"),
        Mt4Broker("FXGT Global", listOf("FXGTGlobal-Live", "FXGTGlobal-Demo"), "Crypto + forex CFDs", "International"),
        Mt4Broker("OANDA", listOf("OANDA-Live", "OANDA-Demo"), "FX & CFDs, US/UK/CA regulated", "United States"),
        Mt4Broker("ThinkMarkets", listOf("ThinkForex-Live", "ThinkForex-Demo", "ThinkMarkets-Live"), "Forex, indices, commodities", "Australia"),
        Mt4Broker("FP Markets", listOf("FPMarkets-Demo", "FP Markets-Real", "FP Markets-Demo"), "Raw ECN + standard", "Australia"),
        Mt4Broker("Admiral Markets", listOf("AdmiralMarkets-Live", "AdmiralMarkets-Demo"), "Forex, shares, commodities", "Estonia"),
        Mt4Broker("FXOpen", listOf("FXOpen-ECN Live", "FXOpen-ECN Demo", "FXOpen-MT4"), "ECN accounts, STP", "United Kingdom"),
        Mt4Broker("InstaForex", listOf("InstaForex-Eurica", "InstaForex-Real", "InstaForex-Demo"), "Forex, high leverage options", "International"),
        Mt4Broker("HotForex", listOf("HFMarkets-Real", "HFMarkets-Demo", "Hotforex-Real"), "FX, metals, CFDs", "International"),
        Mt4Broker("RoboForex", listOf("RoboForex-ECN", "RoboForex-Pro", "RoboForex-Demo"), "Forex, stocks, crypto", "Belize"),
        Mt4Broker("OctaFX", listOf("OctaFX-Real", "OctaFX-Demo"), "Forex, zero-spread ECN option", "International"),
        Mt4Broker("FXCM", listOf("FXCM-MT4 Server", "FXCM-MT4 Demo Server"), "Forex & CFDs, multi-regulated", "United States"),
        Mt4Broker("TradersWay", listOf("TradersWay-Live", "TradersWay-Demo"), "FX, metals, futures", "International"),
        Mt4Broker("Dukascopy", listOf("Dukascopy-Real", "Dukascopy-Demo"), "Swiss bank, ECN", "Switzerland"),
    )
}
