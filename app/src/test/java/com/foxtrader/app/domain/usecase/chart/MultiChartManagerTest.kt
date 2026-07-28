package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiChartManagerTest {

    @Test
    fun `starts with a single active panel`() {
        val manager = MultiChartManager()

        val panels = manager.getPanels()
        assertEquals(1, panels.size)
        assertTrue(panels.first().isActive)
        assertEquals(ChartLayout.SINGLE, manager.getLayout())
    }

    @Test
    fun `adding panels auto-selects matching layouts`() {
        val manager = MultiChartManager()

        manager.addPanel()
        assertEquals(ChartLayout.HORIZONTAL_SPLIT, manager.getLayout())

        manager.addPanel()
        assertEquals(ChartLayout.THREE_TOP, manager.getLayout())

        manager.addPanel()
        assertEquals(ChartLayout.GRID_2X2, manager.getLayout())
    }

    @Test
    fun `cannot exceed four panels`() {
        val manager = MultiChartManager()
        repeat(3) { manager.addPanel() }

        val rejected = manager.addPanel()

        assertEquals(4, manager.getPanels().size)
        assertTrue(rejected == null)
    }

    @Test
    fun `removing panels never drops below one`() {
        val manager = MultiChartManager()

        assertFalse(manager.removePanel(manager.getPanels().first().id))
        assertEquals(1, manager.getPanels().size)
    }

    @Test
    fun `update panel changes symbol and timeframe`() {
        val manager = MultiChartManager()
        val panel = manager.getPanels().first()

        manager.updatePanel(panel.id, symbol = "BTCUSDT", timeframe = Timeframe.H1)

        val updated = manager.getPanels().first()
        assertEquals("BTCUSDT", updated.symbol)
        assertEquals(Timeframe.H1, updated.timeframe)
    }

    @Test
    fun `set active panel deactivates the rest`() {
        val manager = MultiChartManager()
        val second = manager.addPanel(symbol = "XAUUSD", timeframe = Timeframe.H4)
        assertNotNull(second)

        manager.setActivePanel(second!!.id)

        val panels = manager.getPanels()
        assertTrue(panels.single { it.id == second.id }.isActive)
        assertEquals(1, panels.count { it.isActive })
    }

    @Test
    fun `crosshair sync excludes the source panel`() {
        val manager = MultiChartManager()
        val first = manager.getPanels().first()
        val second = manager.addPanel()!!

        val sync = manager.syncCrosshairTime(first.id, 123_456L)

        assertFalse(sync.containsKey(first.id))
        assertEquals(123_456L, sync[second.id])
    }

    @Test
    fun `new panels are inactive until explicitly selected`() {
        val manager = MultiChartManager()

        val second = manager.addPanel(symbol = "BTCUSDT", timeframe = Timeframe.H1)!!

        assertFalse(second.isActive)
        assertEquals(1, manager.getPanels().count { it.isActive })
    }

    @Test
    fun `crosshair sync can be disabled`() {
        val manager = MultiChartManager()
        val first = manager.getPanels().first()
        manager.addPanel()

        manager.toggleCrosshairSync()

        assertTrue(manager.syncCrosshairTime(first.id, 999L).isEmpty())
    }

    @Test
    fun `removing the active panel promotes another panel`() {
        val manager = MultiChartManager()
        val second = manager.addPanel(symbol = "BTCUSDT", timeframe = Timeframe.H1)!!
        manager.setActivePanel(second.id)

        assertTrue(manager.removePanel(second.id))
        assertEquals(1, manager.getPanels().size)
        assertTrue(manager.getPanels().first().isActive)
    }

    @Test
    fun `restore state recreates panel order and active selection`() {
        val manager = MultiChartManager()

        manager.restoreState(
            layout = ChartLayout.GRID_2X2,
            crosshairSync = false,
            panels = listOf(
                ChartPanelSeed("EURUSD", Timeframe.M15),
                ChartPanelSeed("BTCUSDT", Timeframe.H1),
                ChartPanelSeed("XAUUSD", Timeframe.H4),
            ),
            activePanelIndex = 2,
        )

        assertEquals(ChartLayout.GRID_2X2, manager.getLayout())
        assertFalse(manager.isCrosshairSynced())
        assertEquals(listOf("EURUSD", "BTCUSDT", "XAUUSD"), manager.getPanels().map { it.symbol })
        assertEquals("XAUUSD", manager.getActivePanel()?.symbol)
    }

    @Test
    fun `move panel reorders custom slot order`() {
        val manager = MultiChartManager()
        val second = manager.addPanel(symbol = "BTCUSDT", timeframe = Timeframe.H1)!!
        manager.addPanel(symbol = "XAUUSD", timeframe = Timeframe.H4)

        assertTrue(manager.movePanel(second.id, 0))
        assertEquals(listOf("BTCUSDT", "EURUSD", "XAUUSD"), manager.getPanels().map { it.symbol })
    }

    @Test
    fun `moving to same index is a no-op`() {
        val manager = MultiChartManager()
        val first = manager.getPanels().first()

        assertFalse(manager.movePanel(first.id, 0))
    }
}
