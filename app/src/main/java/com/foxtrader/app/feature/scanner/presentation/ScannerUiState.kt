package com.foxtrader.app.feature.scanner.presentation

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType

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
    val results: List<ScreenerResult> = emptyList(),
    val selectedStrategy: StrategyType = StrategyType.CONFLUENCE,
    val selectedAssetClass: AssetClass? = null, // null = ALL
    val selectedRiskLevel: ScannerRiskLevel? = null, // null = ALL
    val selectedSortMode: ScannerSortMode = ScannerSortMode.SCORE,
    val isLoading: Boolean = true,
    val lastScanTime: Long = 0L,
    val error: String? = null,
) {
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
