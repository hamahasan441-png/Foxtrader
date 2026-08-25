package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.domain.model.AssetClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DukascopyInstrumentCatalogTest {
    private val catalog = DukascopyInstrumentCatalog()

    @Test
    fun `friendly index aliases resolve to verified native ids and API codes`() {
        val us30 = catalog.require("US30")
        val nas100 = catalog.require("NAS100")

        assertEquals("USA30IDXUSD", us30.providerSymbol)
        assertEquals("USA30.IDX-USD", us30.apiCode)
        assertEquals("USATECHIDXUSD", nas100.providerSymbol)
        assertEquals("USATECH.IDX-USD", nas100.apiCode)
        assertEquals(AssetClass.INDICES, nas100.assetClass)
    }

    @Test
    fun `directory contains only unique exact provider ids`() {
        val directory = catalog.discoverSymbols()
        assertTrue(directory.size >= 38)
        assertEquals(directory.size, directory.distinctBy { it.providerSymbol }.size)
        assertTrue(directory.any { it.providerSymbol == "XAUUSD" && it.assetClass == AssetClass.METALS })
    }
}
