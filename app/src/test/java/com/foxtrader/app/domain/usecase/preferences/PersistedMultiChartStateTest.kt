package com.foxtrader.app.domain.usecase.preferences

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.chart.ChartLayout
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedMultiChartStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `panelSeeds preserves persisted slot order`() {
        val state = PersistedMultiChartState(
            panels = listOf(
                PersistedMultiChartPanel("EURUSD", Timeframe.M15),
                PersistedMultiChartPanel("BTCUSDT", Timeframe.H1),
                PersistedMultiChartPanel("XAUUSD", Timeframe.H4),
            )
        )

        val seeds = state.panelSeeds()

        assertEquals(listOf("EURUSD", "BTCUSDT", "XAUUSD"), seeds.map { it.symbol })
        assertEquals(listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4), seeds.map { it.timeframe })
    }

    @Test
    fun `json round trip preserves link groups viewport and crosshair session`() {
        val state = PersistedMultiChartState(
            layout = ChartLayout.GRID_2X2,
            linkedToPrimary = true,
            symbolLinkEnabled = false,
            timeframeLinkEnabled = true,
            crosshairSyncEnabled = true,
            activePanelIndex = 2,
            primaryViewport = ChartViewportState(
                startIndex = 120f,
                visibleBars = 80f,
                priceHigh = 123.4,
                priceLow = 111.1,
            ),
            syncedCrosshairTimestamp = 1_700_000_000_000L,
            syncedCrosshairSource = PersistedCrosshairSource.PANEL,
            syncedCrosshairPanelIndex = 1,
            panels = listOf(
                PersistedMultiChartPanel("EURUSD", Timeframe.M15, ChartViewportState(10f, 50f, 2.0, 1.0)),
                PersistedMultiChartPanel("BTCUSDT", Timeframe.H1, ChartViewportState(20f, 60f, 70000.0, 62000.0)),
            ),
        )

        val encoded = json.encodeToString(state)
        val decoded = json.decodeFromString<PersistedMultiChartState>(encoded)

        assertEquals(state.layout, decoded.layout)
        assertEquals(state.linkedToPrimary, decoded.linkedToPrimary)
        assertEquals(state.symbolLinkEnabled, decoded.symbolLinkEnabled)
        assertEquals(state.timeframeLinkEnabled, decoded.timeframeLinkEnabled)
        assertEquals(state.crosshairSyncEnabled, decoded.crosshairSyncEnabled)
        assertEquals(state.activePanelIndex, decoded.activePanelIndex)
        assertEquals(state.syncedCrosshairTimestamp, decoded.syncedCrosshairTimestamp)
        assertEquals(state.syncedCrosshairSource, decoded.syncedCrosshairSource)
        assertEquals(state.syncedCrosshairPanelIndex, decoded.syncedCrosshairPanelIndex)
        assertNotNull(decoded.primaryViewport)
        assertEquals(state.primaryViewport?.startIndex, decoded.primaryViewport?.startIndex)
        assertEquals(state.panels.size, decoded.panels.size)
        assertEquals("BTCUSDT", decoded.panels[1].symbol)
        assertTrue(decoded.panels[1].viewport?.priceHigh == 70000.0)
    }
}
