package com.foxtrader.app.feature.scanner.presentation

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType

/**
 * Immutable UI state for the Scanner screen.
 */
data class ScannerUiState(
    val results: List<ScreenerResult> = emptyList(),
    val selectedStrategy: StrategyType = StrategyType.CONFLUENCE,
    val selectedAssetClass: AssetClass? = null, // null = ALL
    val isLoading: Boolean = true,
    val lastScanTime: Long = 0L,
    val error: String? = null,
) {
    val filteredResults: List<ScreenerResult>
        get() = if (selectedAssetClass == null) results
                else results.filter { it.assetClass == selectedAssetClass }

    val hasData: Boolean get() = results.isNotEmpty()
}
