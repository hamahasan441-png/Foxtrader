package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/**
 * Multi-Chart Manager — handles multiple simultaneous chart views.
 *
 * Supports:
 * - Up to 4 chart panels in split-screen layout
 * - Each panel has its own symbol + timeframe + viewport
 * - Synchronized crosshair across panels (optional)
 * - Layout presets (1x1, 2x1, 2x2, 3x1)
 *
 * Pure domain logic — UI layout handled by composables.
 */
@Singleton
class MultiChartManager @Inject constructor() {

    private val panels = mutableListOf<ChartPanel>()
    private var layout: ChartLayout = ChartLayout.SINGLE
    private var syncCrosshair: Boolean = true

    init {
        // Start with one panel
        panels.add(ChartPanel(
            id = UUID.randomUUID().toString(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
        ))
    }

    // ========================================================================
    // PANEL MANAGEMENT
    // ========================================================================

    fun addPanel(symbol: String = "EURUSD", timeframe: Timeframe = Timeframe.M15): ChartPanel? {
        if (panels.size >= 4) return null // Max 4 panels
        val panel = ChartPanel(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            timeframe = timeframe,
            isActive = false,
        )
        panels.add(panel)
        autoLayout()
        return panel
    }

    fun removePanel(id: String): Boolean {
        if (panels.size <= 1) return false // Must keep at least one
        val removedIndex = panels.indexOfFirst { it.id == id }
        if (removedIndex < 0) return false
        val removedWasActive = panels[removedIndex].isActive
        panels.removeAt(removedIndex)
        if (removedWasActive && panels.none { it.isActive }) {
            val nextActive = removedIndex.coerceAtMost(panels.lastIndex)
            panels[nextActive] = panels[nextActive].copy(isActive = true)
        }
        autoLayout()
        return true
    }

    fun updatePanel(id: String, symbol: String? = null, timeframe: Timeframe? = null) {
        val idx = panels.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val panel = panels[idx]
            panels[idx] = panel.copy(
                symbol = symbol ?: panel.symbol,
                timeframe = timeframe ?: panel.timeframe,
            )
        }
    }

    fun movePanel(id: String, targetIndex: Int): Boolean {
        val fromIndex = panels.indexOfFirst { it.id == id }
        if (fromIndex < 0) return false
        val clampedTarget = targetIndex.coerceIn(0, panels.lastIndex)
        if (fromIndex == clampedTarget) return false
        val panel = panels.removeAt(fromIndex)
        panels.add(clampedTarget, panel)
        return true
    }

    fun getPanels(): List<ChartPanel> = panels.toList()

    fun getActivePanel(): ChartPanel? = panels.firstOrNull { it.isActive }

    fun setActivePanel(id: String) {
        for (i in panels.indices) {
            panels[i] = panels[i].copy(isActive = panels[i].id == id)
        }
    }

    // ========================================================================
    // LAYOUT
    // ========================================================================

    fun setLayout(layout: ChartLayout) {
        this.layout = layout
    }

    fun getLayout(): ChartLayout = layout

    fun setCrosshairSync(enabled: Boolean) {
        syncCrosshair = enabled
    }

    fun restoreState(
        layout: ChartLayout,
        crosshairSync: Boolean,
        panels: List<ChartPanelSeed>,
        activePanelIndex: Int = 0,
    ) {
        val seeds = panels.ifEmpty { listOf(ChartPanelSeed("EURUSD", Timeframe.M15)) }.take(4)
        this.layout = layout
        this.syncCrosshair = crosshairSync
        this.panels.clear()
        seeds.forEachIndexed { index, seed ->
            this.panels += ChartPanel(
                id = UUID.randomUUID().toString(),
                symbol = seed.symbol,
                timeframe = seed.timeframe,
                isActive = index == activePanelIndex.coerceIn(0, seeds.lastIndex),
            )
        }
        if (this.panels.none { it.isActive }) {
            this.panels[0] = this.panels[0].copy(isActive = true)
        }
    }

    fun cycleLayout() {
        val layouts = ChartLayout.entries
        val current = layouts.indexOf(layout)
        layout = layouts[(current + 1) % layouts.size]
    }

    private fun autoLayout() {
        layout = when (panels.size) {
            1 -> ChartLayout.SINGLE
            2 -> ChartLayout.HORIZONTAL_SPLIT
            3 -> ChartLayout.THREE_TOP
            4 -> ChartLayout.GRID_2X2
            else -> ChartLayout.SINGLE
        }
    }

    // ========================================================================
    // CROSSHAIR SYNC
    // ========================================================================

    fun isCrosshairSynced(): Boolean = syncCrosshair

    fun toggleCrosshairSync() { syncCrosshair = !syncCrosshair }

    /**
     * When crosshair moves on one panel, sync to others at the same timestamp.
     */
    fun syncCrosshairTime(sourcePanel: String, timestamp: Long): Map<String, Long> {
        if (!syncCrosshair) return emptyMap()
        return panels
            .filter { it.id != sourcePanel }
            .associate { it.id to timestamp }
    }
}

/**
 * A single chart panel in a multi-chart layout.
 */
data class ChartPanel(
    val id: String,
    val symbol: String,
    val timeframe: Timeframe,
    val isActive: Boolean = true,
)

data class ChartPanelSeed(
    val symbol: String,
    val timeframe: Timeframe,
)

/**
 * Multi-chart layout presets.
 */
enum class ChartLayout(val columns: Int, val rows: Int) {
    SINGLE(1, 1),
    HORIZONTAL_SPLIT(2, 1),
    VERTICAL_SPLIT(1, 2),
    THREE_TOP(3, 1),
    GRID_2X2(2, 2),
}
