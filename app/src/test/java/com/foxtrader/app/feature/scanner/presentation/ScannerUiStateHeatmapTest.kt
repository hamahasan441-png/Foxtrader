package com.foxtrader.app.feature.scanner.presentation

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.heatmap.MarketHeatmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heatmap view state.
 *
 * The property that matters: switching LIST -> HEATMAP must not silently widen
 * what the user is looking at. If they have filtered to CRYPTO, the grid shows
 * crypto — otherwise the two views disagree about the same scan.
 */
class ScannerUiStateHeatmapTest {

    private fun cell(
        symbol: String,
        assetClass: AssetClass,
        change: Double,
    ) = MarketHeatmap.HeatmapCell(
        symbol = symbol,
        assetClass = assetClass,
        changePercent = change,
        volume = 1_000.0,
        relativeStrength = 0.0,
        color = MarketHeatmap.HeatmapColor.NEUTRAL,
        intensity = 0.5f,
    )

    private fun result(symbol: String, assetClass: AssetClass) = ScreenerResult(
        symbol = symbol,
        assetClass = assetClass,
        strategy = StrategyType.CONFLUENCE,
        direction = Direction.BULLISH,
        score = 70,
        bias = Bias.BULLISH,
        trendStrength = 1.0,
        momentum = 1.0,
        volatility = 1.0,
        setupQuality = 1.0,
        categories = emptyList(),
        tags = emptyList(),
        lastPrice = 1.0,
        changePercent = 1.0,
    )

    private val cells = listOf(
        cell("EURUSD", AssetClass.FOREX, 0.4),
        cell("GBPUSD", AssetClass.FOREX, -0.8),
        cell("BTCUSDT", AssetClass.CRYPTO, 3.2),
        cell("ETHUSDT", AssetClass.CRYPTO, 2.1),
    )

    private val heatmap = MarketHeatmap.HeatmapResult(
        cells = cells,
        bestPerformer = cells[2],
        worstPerformer = cells[1],
        averageChange = 1.225,
        marketSentiment = MarketHeatmap.MarketSentiment.GREED,
    )

    @Test
    fun `no asset filter shows every cell`() {
        val state = ScannerUiState(heatmap = heatmap)
        assertEquals(4, state.filteredHeatmapCells.size)
    }

    @Test
    fun `asset filter applies to the heatmap as well as the list`() {
        val state = ScannerUiState(heatmap = heatmap, selectedAssetClass = AssetClass.CRYPTO)
        val symbols = state.filteredHeatmapCells.map { it.symbol }
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), symbols)
    }

    @Test
    fun `asset filter with no matching cells yields empty, not everything`() {
        val state = ScannerUiState(heatmap = heatmap, selectedAssetClass = AssetClass.ENERGY)
        assertTrue(state.filteredHeatmapCells.isEmpty())
    }

    @Test
    fun `missing heatmap yields no cells rather than throwing`() {
        val state = ScannerUiState(heatmap = null, selectedAssetClass = AssetClass.CRYPTO)
        assertTrue(state.filteredHeatmapCells.isEmpty())
    }

    @Test
    fun `default view mode is the list`() {
        assertEquals(ScannerViewMode.LIST, ScannerUiState().viewMode)
    }

    @Test
    fun `synthetic flag requires actual results`() {
        // An empty scan is not "synthetic data", it is no data. Badging it
        // would train the user to ignore the warning.
        val empty = ScannerUiState(dataSource = CandleSource.SYNTHETIC)
        assertFalse(empty.isSyntheticData)

        val populated = ScannerUiState(
            results = listOf(result("EURUSD", AssetClass.FOREX)),
            dataSource = CandleSource.SYNTHETIC,
        )
        assertTrue(populated.isSyntheticData)
    }

    @Test
    fun `live and cached scans are not badged as synthetic`() {
        listOf(CandleSource.LIVE, CandleSource.CACHED).forEach { source ->
            val state = ScannerUiState(
                results = listOf(result("EURUSD", AssetClass.FOREX)),
                dataSource = source,
            )
            assertFalse("$source must not be badged synthetic", state.isSyntheticData)
        }
    }
}
