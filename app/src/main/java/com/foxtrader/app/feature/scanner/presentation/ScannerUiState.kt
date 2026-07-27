package com.foxtrader.app.feature.scanner.presentation

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.heatmap.MarketHeatmap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** How the scanner renders its results. */
enum class ScannerViewMode { LIST, HEATMAP }

/** Scanner list sort modes. */
enum class ScannerSortMode {
    SCORE,
    TREND,
    MOMENTUM,
    VOLATILITY,
}

/**
 * Immutable UI state for the Scanner screen.
 */
data class ScannerUiState(
    val results: ImmutableList<ScreenerResult> = persistentListOf(),
    val selectedStrategy: StrategyType = StrategyType.CONFLUENCE,
    val selectedAssetClass: AssetClass? = null, // null = ALL
    val selectedRiskLevel: ScannerRiskLevel? = null, // null = ALL
    val selectedSortMode: ScannerSortMode = ScannerSortMode.SCORE,
    val viewMode: ScannerViewMode = ScannerViewMode.LIST,
    val heatmap: MarketHeatmap.HeatmapResult? = null,
    /**
     * Worst provenance across the scanned symbols. A heatmap built from
     * generated bars shows sector rotation that never happened, so it must be
     * labelled (Sprint 6 contract).
     */
    val dataSource: CandleSource = CandleSource.CACHED,
    val isLoading: Boolean = true,
    val lastScanTime: Long = 0L,
    val error: String? = null,
) {
    val isSyntheticData: Boolean get() = hasData && dataSource == CandleSource.SYNTHETIC

    /**
     * Heatmap cells honouring the asset-class filter, so switching view modes
     * does not silently widen what the user is looking at.
     */
    val filteredHeatmapCells: List<MarketHeatmap.HeatmapCell>
        get() = heatmap?.cells.orEmpty().let { cells ->
            if (selectedAssetClass == null) cells
            else cells.filter { it.assetClass == selectedAssetClass }
        }

    val filteredResults: List<ScreenerResult>
        get() {
            val assetFiltered = if (selectedAssetClass == null) {
                results
            } else {
                results.filter { it.assetClass == selectedAssetClass }
            }
            val riskFiltered = if (selectedRiskLevel == null) {
                assetFiltered
            } else {
                assetFiltered.filter { it.riskLevel == selectedRiskLevel }
            }
            return when (selectedSortMode) {
                ScannerSortMode.SCORE -> riskFiltered.sortedByDescending { it.score }
                ScannerSortMode.TREND -> riskFiltered.sortedByDescending { it.trendStrength }
                ScannerSortMode.MOMENTUM -> riskFiltered.sortedByDescending { it.momentum }
                ScannerSortMode.VOLATILITY -> riskFiltered.sortedByDescending { it.volatility }
            }
        }

    val hasData: Boolean get() = results.isNotEmpty()
}
