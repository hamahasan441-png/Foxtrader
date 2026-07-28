package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.chart.ChartLayout
import com.foxtrader.app.domain.usecase.chart.ChartPanel
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import com.foxtrader.app.domain.usecase.chart.MultiChartManager
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.preferences.PersistedCrosshairSource
import com.foxtrader.app.domain.usecase.preferences.PersistedMultiChartPanel
import com.foxtrader.app.domain.usecase.preferences.PersistedMultiChartState
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manages multi-chart panel layout, crosshair synchronisation, and preference persistence. */
internal class ChartMultiChartController(
    private val multiChartManager: MultiChartManager,
    private val repository: MarketRepository,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val appPreferences: AppPreferences,
    private val defaultDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val symbolFlow: () -> String,
    private val timeframeFlow: () -> Timeframe,
    private val onUiSyncedCrosshairChange: (Long?) -> Unit,
) {

    val _multiChartState = MutableStateFlow(MultiChartUiState())

    private val multiChartPanelJobs = linkedMapOf<String, Job>()
    private val multiChartPanels = linkedMapOf<String, MultiChartPanelUiState>()
    private var multiChartLinkedToPrimary: Boolean = true
    private var multiChartSymbolLinkEnabled: Boolean = true
    private var multiChartTimeframeLinkEnabled: Boolean = true
    private var primarySyncedCrosshairTimestamp: Long? = null
    private val panelSyncedCrosshairTimestamps = linkedMapOf<String, Long?>()
    private val panelPublishedFingerprints = linkedMapOf<String, String>()
    private var primaryViewportState: ChartViewportState? = null
    private val panelViewportStates = linkedMapOf<String, ChartViewportState?>()
    private var lastCrosshairSource: PersistedCrosshairSource = PersistedCrosshairSource.NONE
    private var lastCrosshairSourcePanelId: String? = null
    private var persistMultiChartJob: Job? = null
    private var hasRestoredMultiChartPreferences: Boolean = false

    fun observePersistedMultiChartPreferences() {
        appPreferences.multiChartPreferences
            .onEach { persisted ->
                if (persisted == null || hasRestoredMultiChartPreferences) return@onEach
                restorePersistedMultiChartPreferences(persisted)
                hasRestoredMultiChartPreferences = true
            }
            .launchIn(scope)
    }

    private fun restorePersistedMultiChartPreferences(state: PersistedMultiChartState) {
        multiChartLinkedToPrimary = state.linkedToPrimary
        multiChartSymbolLinkEnabled = state.symbolLinkEnabled
        multiChartTimeframeLinkEnabled = state.timeframeLinkEnabled
        primaryViewportState = state.primaryViewport
        panelViewportStates.clear()
        multiChartManager.restoreState(
            layout = state.layout, crosshairSync = state.crosshairSyncEnabled,
            panels = state.panelSeeds(), activePanelIndex = state.activePanelIndex,
        )
        multiChartManager.getPanels().forEachIndexed { index, panel ->
            panelViewportStates[panel.id] = state.panels.getOrNull(index)?.viewport
        }
        clearSyncedCrosshairs()
        restorePersistedCrosshairState(state)
        if (multiChartLinkedToPrimary) syncMultiChartPanelsToPrimary() else refreshMultiChartPanels()
    }

    private fun restorePersistedCrosshairState(state: PersistedMultiChartState) {
        val timestamp = state.syncedCrosshairTimestamp ?: return
        if (!state.crosshairSyncEnabled) return
        when (state.syncedCrosshairSource) {
            PersistedCrosshairSource.PRIMARY -> {
                lastCrosshairSource = PersistedCrosshairSource.PRIMARY
                multiChartManager.getPanels().forEach { panel ->
                    panelSyncedCrosshairTimestamps[panel.id] = timestamp
                }
            }
            PersistedCrosshairSource.PANEL -> {
                val sourcePanel = multiChartManager.getPanels().getOrNull(
                    state.syncedCrosshairPanelIndex ?: return
                ) ?: return
                lastCrosshairSource = PersistedCrosshairSource.PANEL
                lastCrosshairSourcePanelId = sourcePanel.id
                primarySyncedCrosshairTimestamp = timestamp
                panelSyncedCrosshairTimestamps.putAll(
                    multiChartManager.syncCrosshairTime(sourcePanel.id, timestamp)
                )
            }
            PersistedCrosshairSource.NONE -> Unit
        }
    }

    fun persistMultiChartPreferences() {
        persistMultiChartJob?.cancel()
        persistMultiChartJob = scope.launch {
            delay(PERSIST_MULTI_CHART_DEBOUNCE_MS)
            val panels = multiChartManager.getPanels()
            val activeIndex = panels.indexOfFirst { it.isActive }.coerceAtLeast(0)
            val persistedTs = when (lastCrosshairSource) {
                PersistedCrosshairSource.PRIMARY -> panelSyncedCrosshairTimestamps.values.firstOrNull()
                PersistedCrosshairSource.PANEL -> primarySyncedCrosshairTimestamp
                PersistedCrosshairSource.NONE -> null
            }
            val crosshairPanelIdx = if (lastCrosshairSource == PersistedCrosshairSource.PANEL) {
                panels.indexOfFirst { it.id == lastCrosshairSourcePanelId }.takeIf { it >= 0 }
            } else null
            appPreferences.setMultiChartPreferences(PersistedMultiChartState(
                layout = multiChartManager.getLayout(),
                linkedToPrimary = multiChartLinkedToPrimary,
                symbolLinkEnabled = multiChartSymbolLinkEnabled,
                timeframeLinkEnabled = multiChartTimeframeLinkEnabled,
                crosshairSyncEnabled = multiChartManager.isCrosshairSynced(),
                activePanelIndex = activeIndex,
                primaryViewport = primaryViewportState,
                syncedCrosshairTimestamp = persistedTs,
                syncedCrosshairSource = lastCrosshairSource,
                syncedCrosshairPanelIndex = crosshairPanelIdx,
                panels = panels.map { PersistedMultiChartPanel(it.symbol, it.timeframe, panelViewportStates[it.id]) },
            ))
        }
    }

    fun currentPrimaryViewportState(): ChartViewportState? = primaryViewportState
    fun currentMultiChartPanelViewportState(panelId: String): ChartViewportState? = panelViewportStates[panelId]

    fun onPrimaryViewportStateChange(state: ChartViewportState) {
        primaryViewportState = state; persistMultiChartPreferences()
    }

    fun onMultiChartPanelViewportStateChange(panelId: String, state: ChartViewportState) {
        panelViewportStates[panelId] = state; persistMultiChartPreferences()
    }

    fun clearSyncedCrosshairs() {
        primarySyncedCrosshairTimestamp = null; panelSyncedCrosshairTimestamps.clear()
        lastCrosshairSource = PersistedCrosshairSource.NONE; lastCrosshairSourcePanelId = null
        onUiSyncedCrosshairChange(null)
    }

    private fun clearPanelViewportIfContextChanged(
        panelId: String, prevSymbol: String, prevTf: Timeframe, newSymbol: String, newTf: Timeframe,
    ) {
        if (prevSymbol != newSymbol || prevTf != newTf) panelViewportStates[panelId] = null
    }

    fun setMultiChartLayout(layout: ChartLayout) {
        multiChartManager.setLayout(layout)
        ensureMultiChartPanelCount(layout)
        syncMultiChartPanelsToPrimary(); persistMultiChartPreferences()
    }

    fun addMultiChartPanel() {
        val seed = if (multiChartLinkedToPrimary) {
            val nextTf = linkedPanelTimeframes(timeframeFlow(), multiChartManager.getPanels().size + 1)
                .getOrElse(multiChartManager.getPanels().size) { timeframeFlow() }
            symbolFlow() to nextTf
        } else {
            val active = multiChartManager.getActivePanel()
            (active?.symbol ?: symbolFlow()) to (active?.timeframe ?: timeframeFlow())
        }
        multiChartManager.addPanel(seed.first, seed.second) ?: return
        refreshMultiChartPanels(); persistMultiChartPreferences()
    }

    fun removeMultiChartPanel(panelId: String) {
        if (!multiChartManager.removePanel(panelId)) return
        clearSyncedCrosshairs(); panelViewportStates.remove(panelId)
        refreshMultiChartPanels(); persistMultiChartPreferences()
    }

    fun moveMultiChartPanelToIndex(panelId: String, targetIndex: Int) {
        if (!multiChartManager.movePanel(panelId, targetIndex)) return
        refreshMultiChartPanels(); persistMultiChartPreferences()
    }

    fun toggleMultiChartLinking() {
        multiChartLinkedToPrimary = !multiChartLinkedToPrimary
        if (multiChartLinkedToPrimary) syncMultiChartPanelsToPrimary() else publishMultiChartState()
        persistMultiChartPreferences()
    }

    fun toggleMultiChartSymbolLink() {
        multiChartSymbolLinkEnabled = !multiChartSymbolLinkEnabled
        if (multiChartLinkedToPrimary && multiChartSymbolLinkEnabled) syncMultiChartPanelsToPrimary() else publishMultiChartState()
        persistMultiChartPreferences()
    }

    fun toggleMultiChartTimeframeLink() {
        multiChartTimeframeLinkEnabled = !multiChartTimeframeLinkEnabled
        if (multiChartLinkedToPrimary && multiChartTimeframeLinkEnabled) syncMultiChartPanelsToPrimary() else publishMultiChartState()
        persistMultiChartPreferences()
    }

    fun toggleMultiChartCrosshairSync() {
        multiChartManager.toggleCrosshairSync()
        if (!multiChartManager.isCrosshairSynced()) clearSyncedCrosshairs()
        publishMultiChartState()
        persistMultiChartPreferences()
    }

    fun setActiveMultiChartPanel(panelId: String) {
        multiChartManager.setActivePanel(panelId)
        publishMultiChartState(); persistMultiChartPreferences()
    }

    fun setMultiChartPanelSymbol(panelId: String, symbol: String) {
        if (multiChartLinkedToPrimary) return
        val panel = multiChartManager.getPanels().firstOrNull { it.id == panelId } ?: return
        clearPanelViewportIfContextChanged(panelId, panel.symbol, panel.timeframe, symbol, panel.timeframe)
        multiChartManager.updatePanel(panelId, symbol = symbol)
        refreshMultiChartPanels(); persistMultiChartPreferences()
    }

    fun setMultiChartPanelTimeframe(panelId: String, timeframe: Timeframe) {
        if (multiChartLinkedToPrimary) return
        val panel = multiChartManager.getPanels().firstOrNull { it.id == panelId } ?: return
        clearPanelViewportIfContextChanged(panelId, panel.symbol, panel.timeframe, panel.symbol, timeframe)
        multiChartManager.updatePanel(panelId, timeframe = timeframe)
        refreshMultiChartPanels(); persistMultiChartPreferences()
    }

    fun resetMultiChartPanelToPrimary(panelId: String) {
        val panel = multiChartManager.getPanels().firstOrNull { it.id == panelId } ?: return
        clearPanelViewportIfContextChanged(panelId, panel.symbol, panel.timeframe, symbolFlow(), timeframeFlow())
        multiChartManager.updatePanel(id = panelId, symbol = symbolFlow(), timeframe = timeframeFlow())
        if (multiChartLinkedToPrimary) syncMultiChartPanelsToPrimary() else refreshMultiChartPanels()
        persistMultiChartPreferences()
    }

    fun onPrimaryCrosshairTimestampChange(timestamp: Long?) {
        if (!multiChartManager.isCrosshairSynced()) return
        primarySyncedCrosshairTimestamp = null; panelSyncedCrosshairTimestamps.clear()
        if (timestamp != null) {
            lastCrosshairSource = PersistedCrosshairSource.PRIMARY; lastCrosshairSourcePanelId = null
            multiChartManager.getPanels().forEach { panelSyncedCrosshairTimestamps[it.id] = timestamp }
        } else {
            lastCrosshairSource = PersistedCrosshairSource.NONE; lastCrosshairSourcePanelId = null
        }
        publishMultiChartState(primaryCrosshairTimestamp = null); persistMultiChartPreferences()
    }

    fun onMultiChartPanelCrosshairTimestampChange(panelId: String, timestamp: Long?) {
        if (!multiChartManager.isCrosshairSynced()) return
        primarySyncedCrosshairTimestamp = timestamp; panelSyncedCrosshairTimestamps.clear()
        if (timestamp != null) {
            lastCrosshairSource = PersistedCrosshairSource.PANEL; lastCrosshairSourcePanelId = panelId
            panelSyncedCrosshairTimestamps.putAll(multiChartManager.syncCrosshairTime(panelId, timestamp))
        } else {
            lastCrosshairSource = PersistedCrosshairSource.NONE; lastCrosshairSourcePanelId = null
        }
        publishMultiChartState(primaryCrosshairTimestamp = timestamp); persistMultiChartPreferences()
    }

    fun ensureMultiChartPanelCount(layout: ChartLayout) {
        val targetCount = when (layout) {
            ChartLayout.SINGLE -> 1
            ChartLayout.HORIZONTAL_SPLIT, ChartLayout.VERTICAL_SPLIT -> 2
            ChartLayout.THREE_TOP -> 3
            ChartLayout.GRID_2X2 -> 4
        }
        while (multiChartManager.getPanels().size < targetCount) {
            multiChartManager.addPanel(symbolFlow(), timeframeFlow())
        }
        while (multiChartManager.getPanels().size > targetCount) {
            multiChartManager.getPanels().lastOrNull()?.id?.let { multiChartManager.removePanel(it) }
        }
    }

    fun syncMultiChartPanelsToPrimary() {
        ensureMultiChartPanelCount(multiChartManager.getLayout())
        val panels = multiChartManager.getPanels()
        val linkedTimeframes = linkedPanelTimeframes(timeframeFlow(), panels.size)
        panels.forEachIndexed { index, panel ->
            val target = targetPanelContext(index, panel, linkedTimeframes) ?: return@forEachIndexed
            clearPanelViewportIfContextChanged(panel.id, panel.symbol, panel.timeframe, target.first, target.second)
            multiChartManager.updatePanel(id = panel.id, symbol = target.first, timeframe = target.second)
        }
        refreshMultiChartPanels()
        if (hasRestoredMultiChartPreferences) persistMultiChartPreferences()
    }

    fun refreshMultiChartPanels() {
        multiChartPanelJobs.values.forEach { it.cancel() }
        multiChartPanelJobs.clear(); multiChartPanels.clear(); panelPublishedFingerprints.clear()
        val panels = multiChartManager.getPanels()
        panels.forEach { panel ->
            multiChartPanels[panel.id] = MultiChartPanelUiState(
                id = panel.id, symbol = panel.symbol, timeframe = panel.timeframe,
                isActive = panel.isActive, isLoading = true,
            )
            multiChartPanelJobs[panel.id] = repository.observeSourcedCandles(panel.symbol, panel.timeframe)
                .onEach { sourced ->
                    val compactCandles = sourced.candles.takeLast(PANEL_RENDER_BARS)
                    val biasInput = sourced.candles.takeLast(PANEL_BIAS_BARS)
                    val bias = withContext(defaultDispatcher) {
                        if (biasInput.size >= 50) analyzeStructure(biasInput).bias else Bias.NEUTRAL
                    }
                    val last = compactCandles.lastOrNull()
                    val fingerprint = "${panel.symbol}:${panel.timeframe.label}:${sourced.source}:" +
                        "${compactCandles.size}:${last?.timestamp}:${last?.close}:$bias:${panel.isActive}"
                    if (panelPublishedFingerprints[panel.id] == fingerprint) return@onEach
                    panelPublishedFingerprints[panel.id] = fingerprint
                    multiChartPanels[panel.id] = MultiChartPanelUiState(
                        id = panel.id, symbol = panel.symbol, timeframe = panel.timeframe,
                        candles = compactCandles.asCandleSeries(), dataSource = sourced.source,
                        bias = bias, isActive = panel.isActive,
                        syncedCrosshairTimestamp = panelSyncedCrosshairTimestamps[panel.id],
                        isLoading = false, error = null,
                    )
                    publishMultiChartState()
                }
                .launchIn(scope)

            scope.launch {
                repository.refreshCandles(panel.symbol, panel.timeframe, PANEL_REFRESH_BARS).onFailure { error ->
                    val current = multiChartPanels[panel.id] ?: return@onFailure
                    multiChartPanels[panel.id] = current.copy(isLoading = false, error = error.message ?: "Failed to load panel")
                    publishMultiChartState()
                }
            }
        }
        publishMultiChartState()
    }

    fun publishMultiChartState(primaryCrosshairTimestamp: Long? = primarySyncedCrosshairTimestamp) {
        val ordered = multiChartManager.getPanels().map { panel ->
            (multiChartPanels[panel.id] ?: MultiChartPanelUiState(
                id = panel.id, symbol = panel.symbol, timeframe = panel.timeframe,
                isActive = panel.isActive, isLoading = true,
            )).copy(
                symbol = panel.symbol, timeframe = panel.timeframe, isActive = panel.isActive,
                syncedCrosshairTimestamp = panelSyncedCrosshairTimestamps[panel.id],
            )
        }
        _multiChartState.value = MultiChartUiState(
            layout = multiChartManager.getLayout(),
            linkedToPrimary = multiChartLinkedToPrimary,
            symbolLinkEnabled = multiChartSymbolLinkEnabled,
            timeframeLinkEnabled = multiChartTimeframeLinkEnabled,
            crosshairSyncEnabled = multiChartManager.isCrosshairSynced(),
            panels = ordered.toPersistentList(),
        )
        onUiSyncedCrosshairChange(primaryCrosshairTimestamp)
    }

    private fun linkedPanelTimeframes(primary: Timeframe, count: Int): List<Timeframe> {
        val ordered = listOf(
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1, Timeframe.MN,
        )
        val startIndex = ordered.indexOf(primary).coerceAtLeast(0)
        return List(count) { offset ->
            ordered.getOrElse(startIndex + offset) { ordered.last() }
        }
    }

    private fun targetPanelContext(
        index: Int, panel: ChartPanel, linkedTimeframes: List<Timeframe>,
    ): Pair<String, Timeframe>? = when {
        index == 0 -> symbolFlow() to timeframeFlow()
        !multiChartLinkedToPrimary -> null
        else -> {
            val targetSymbol = if (multiChartSymbolLinkEnabled) symbolFlow() else panel.symbol
            val targetTimeframe = if (multiChartTimeframeLinkEnabled) {
                linkedTimeframes.getOrElse(index) { timeframeFlow() }
            } else {
                panel.timeframe
            }
            targetSymbol to targetTimeframe
        }
    }

    fun resetPrimaryViewportState() { primaryViewportState = null; clearSyncedCrosshairs() }
    fun cancelAllPanelJobs() { multiChartPanelJobs.values.forEach { it.cancel() } }

    companion object {
        const val PANEL_RENDER_BARS = 240
        const val PANEL_BIAS_BARS = 320
        const val PANEL_REFRESH_BARS = 320
        const val PERSIST_MULTI_CHART_DEBOUNCE_MS = 250L
    }
}
