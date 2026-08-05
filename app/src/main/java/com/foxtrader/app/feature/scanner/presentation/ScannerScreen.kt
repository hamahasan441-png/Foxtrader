package com.foxtrader.app.feature.scanner.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.WatchlistCategory
import com.foxtrader.app.domain.usecase.heatmap.MarketHeatmap
import com.foxtrader.app.R
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onNavigateToOpportunityBoard: () -> Unit = {},
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Scanner",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToOpportunityBoard) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "Open TRADEPRO opportunity board",
                            tint = FoxAmber50,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StrategyFilter(
                selected = state.selectedStrategy,
                onSelect = viewModel::selectStrategy,
            )

            // Asset class filter chips
            AssetClassFilter(
                selected = state.selectedAssetClass,
                onSelect = viewModel::selectAssetClass,
            )

            ScannerControls(
                selectedRiskLevel = state.selectedRiskLevel,
                selectedSortMode = state.selectedSortMode,
                onRiskSelect = viewModel::selectRiskLevel,
                onSortSelect = viewModel::selectSortMode,
            )

            ViewModeToggle(
                selected = state.viewMode,
                onSelect = viewModel::selectViewMode,
            )

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = FoxAmber50)
                    }
                }
                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Error: ${state.error}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                !state.hasData -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No data available", color = FoxNeutral60)
                    }
                }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        if (state.isSyntheticData) {
                            SyntheticScanNotice()
                        }
                        when (state.viewMode) {
                            ScannerViewMode.LIST -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(state.filteredResults, key = { it.symbol }) { result ->
                                    ScannerResultCard(result)
                                }
                            }

                            ScannerViewMode.HEATMAP -> HeatmapGrid(
                                cells = state.filteredHeatmapCells,
                                sentiment = state.heatmap?.marketSentiment,
                                averageChange = state.heatmap?.averageChange ?: 0.0,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * LIST / HEATMAP switch. Both render the same scan, so toggling never refetches.
 */
@Composable
private fun ViewModeToggle(
    selected: ScannerViewMode,
    onSelect: (ScannerViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ScannerViewMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Text(
                text = if (mode == ScannerViewMode.LIST) "LIST" else "HEATMAP",
                color = if (isSelected) androidx.compose.ui.graphics.Color.Black else FoxAmber50,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) FoxAmber50 else FoxAmber50.copy(alpha = 0.25f))
                    .clickable { onSelect(mode) }
                    .padding(vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Sprint 6 contract. A heatmap over generated bars renders sector rotation
 * that never happened, which is more convincing — and so more dangerous —
 * than a single fabricated price.
 */
@Composable
private fun SyntheticScanNotice() {
    Text(
        text = stringResource(R.string.scanner_simulated_notice),
        color = FoxWarning,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FoxWarning.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * Treemap-style grid grouped by asset class.
 *
 * Cells are grouped rather than laid out in one flat grid because the whole
 * point of a heatmap is spotting *sector* rotation — a flat sort by change
 * mixes crypto and FX together and hides exactly that signal.
 */
@Composable
private fun HeatmapGrid(
    cells: List<MarketHeatmap.HeatmapCell>,
    sentiment: MarketHeatmap.MarketSentiment?,
    averageChange: Double,
) {
    if (cells.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No heatmap data", color = FoxNeutral60, fontSize = 12.sp)
        }
        return
    }

    val grouped = cells.groupBy { it.assetClass }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SentimentHeader(sentiment = sentiment, averageChange = averageChange)
        }
        grouped.forEach { (assetClass, groupCells) ->
            item(key = "header-${assetClass.name}") {
                Text(
                    text = assetClass.name,
                    color = FoxNeutral60,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Fixed 3-per-row: enough to compare at a glance without shrinking
            // the symbol text below legibility on a phone.
            groupCells.chunked(3).forEachIndexed { rowIndex, rowCells ->
                item(key = "${assetClass.name}-row-$rowIndex") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowCells.forEach { cell ->
                            HeatmapTile(cell = cell, modifier = Modifier.weight(1f))
                        }
                        // Keep the last row's tiles the same width as a full row.
                        repeat(3 - rowCells.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SentimentHeader(
    sentiment: MarketHeatmap.MarketSentiment?,
    averageChange: Double,
) {
    val label = when (sentiment) {
        MarketHeatmap.MarketSentiment.EXTREME_GREED -> "EXTREME GREED"
        MarketHeatmap.MarketSentiment.GREED -> "GREED"
        MarketHeatmap.MarketSentiment.NEUTRAL -> "NEUTRAL"
        MarketHeatmap.MarketSentiment.FEAR -> "FEAR"
        MarketHeatmap.MarketSentiment.EXTREME_FEAR -> "EXTREME FEAR"
        null -> "—"
    }
    val accent = when (sentiment) {
        MarketHeatmap.MarketSentiment.EXTREME_GREED,
        MarketHeatmap.MarketSentiment.GREED,
        -> FoxBullishText

        MarketHeatmap.MarketSentiment.FEAR,
        MarketHeatmap.MarketSentiment.EXTREME_FEAR,
        -> FoxBearishText

        else -> FoxNeutral60
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FoxNeutral10)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Market sentiment", color = FoxNeutral60, fontSize = 9.sp)
            Text(label, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Average move", color = FoxNeutral60, fontSize = 9.sp)
            Text(
                text = formatSignedPercent(averageChange),
                color = if (averageChange >= 0) FoxBullishText else FoxBearishText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun HeatmapTile(
    cell: MarketHeatmap.HeatmapCell,
    modifier: Modifier = Modifier,
) {
    val base = when (cell.color) {
        MarketHeatmap.HeatmapColor.STRONG_BULLISH,
        MarketHeatmap.HeatmapColor.BULLISH,
        -> FoxBullishText

        MarketHeatmap.HeatmapColor.STRONG_BEARISH,
        MarketHeatmap.HeatmapColor.BEARISH,
        -> FoxBearishText

        MarketHeatmap.HeatmapColor.NEUTRAL -> FoxNeutral60
    }
    // Floor the alpha so a ~0% mover is still a readable tile rather than a
    // near-invisible one.
    val alpha = (0.18f + cell.intensity * 0.62f).coerceIn(0.18f, 0.8f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(base.copy(alpha = alpha))
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .semantics {
                contentDescription = "${cell.symbol} ${formatSignedPercent(cell.changePercent)}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = cell.symbol,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Text(
            text = formatSignedPercent(cell.changePercent),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp,
        )
    }
}

private fun formatSignedPercent(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    val rounded = kotlin.math.round(value * 100) / 100.0
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded%"
}

@Composable
private fun StrategyFilter(
    selected: StrategyType,
    onSelect: (StrategyType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.scanner_strategy_label),
            fontSize = 12.sp,
            color = FoxNeutral60,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FoxNeutral10)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(StrategyType.entries) { strategy ->
                val isSelected = selected == strategy
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(strategy) },
                    label = {
                        Text(
                            strategy.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        selectedContainerColor = FoxAmber50.copy(alpha = 0.2f),
                        selectedLabelColor = FoxAmber50,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AssetClassFilter(
    selected: AssetClass?,
    onSelect: (AssetClass?) -> Unit,
) {
    val options = listOf<Pair<String, AssetClass?>>(
        "All" to null,
        "Forex" to AssetClass.FOREX,
        "Crypto" to AssetClass.CRYPTO,
        "Stocks" to AssetClass.STOCKS,
        "Indices" to AssetClass.INDICES,
        "Metals" to AssetClass.METALS,
        "Energy" to AssetClass.ENERGY,
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { (label, assetClass) ->
            FilterChip(
                selected = selected == assetClass,
                onClick = { onSelect(assetClass) },
                label = { Text(label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FoxAmber50.copy(alpha = 0.2f),
                    selectedLabelColor = FoxAmber50,
                ),
            )
        }
    }
}

@Composable
private fun ScannerControls(
    selectedRiskLevel: ScannerRiskLevel?,
    selectedSortMode: ScannerSortMode,
    onRiskSelect: (ScannerRiskLevel?) -> Unit,
    onSortSelect: (ScannerSortMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedRiskLevel == null,
                    onClick = { onRiskSelect(null) },
                    label = { Text("All Risk", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FoxAmber50.copy(alpha = 0.2f),
                        selectedLabelColor = FoxAmber50,
                    ),
                )
            }
            items(ScannerRiskLevel.entries) { risk ->
                FilterChip(
                    selected = selectedRiskLevel == risk,
                    onClick = { onRiskSelect(risk) },
                    label = { Text(formatEnumName(risk.name), fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FoxAmber50.copy(alpha = 0.2f),
                        selectedLabelColor = FoxAmber50,
                    ),
                )
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ScannerSortMode.entries) { sort ->
                FilterChip(
                    selected = selectedSortMode == sort,
                    onClick = { onSortSelect(sort) },
                    label = { Text("Sort: ${formatEnumName(sort.name)}", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FoxAmber50.copy(alpha = 0.2f),
                        selectedLabelColor = FoxAmber50,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ScannerResultCard(result: ScreenerResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Top row: symbol + direction + score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = result.symbol,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    DirectionBadge(result.direction)
                    Spacer(Modifier.width(8.dp))
                    TagChip(result.strategy.label)
                }
                Column(horizontalAlignment = Alignment.End) {
                    ScoreBadge(result.score)
                    Spacer(Modifier.height(2.dp))
                    RiskBadge(result.riskLevel)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Price + change
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "%.5f".format(result.lastPrice),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "%+.2f%%".format(result.changePercent),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (result.changePercent >= 0) FoxBullishText else FoxBearishText,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ScannerMetric("Trend", result.trendStrength.toInt().toString())
                ScannerMetric("Momentum", result.momentum.toInt().toString())
                ScannerMetric("Vol", result.volatility.toInt().toString())
                ScannerMetric("Setup", result.setupQuality.toInt().toString())
            }

            if (result.rationale.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result.rationale,
                    fontSize = 11.sp,
                    color = FoxNeutral60,
                    lineHeight = 14.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Tags row
            if (result.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.tags.forEach { tag ->
                        TagChip(tag)
                    }
                }
            }

            // Category badges
            if (result.categories.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.categories.forEach { cat ->
                        CategoryBadge(cat)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 10.sp, color = FoxNeutral60)
    }
}

@Composable
private fun RiskBadge(riskLevel: ScannerRiskLevel) {
    val (label, color) = when (riskLevel) {
        ScannerRiskLevel.LOW -> "LOW RISK" to FoxBullishText
        ScannerRiskLevel.MODERATE -> "MOD" to FoxAmber50
        ScannerRiskLevel.HIGH -> "HIGH RISK" to FoxBearishText
    }
    Text(
        text = label,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun DirectionBadge(direction: Direction) {
    val color = if (direction == Direction.BULLISH) FoxBullishText else FoxBearishText
    val label = if (direction == Direction.BULLISH) "BUY" else "SELL"
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ScoreBadge(score: Int) {
    val color = when {
        score >= 70 -> FoxBullishText
        score >= 50 -> FoxAmber50
        else -> FoxNeutral60
    }
    Text(
        text = "$score",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@Composable
private fun TagChip(tag: String) {
    Text(
        text = tag,
        fontSize = 10.sp,
        color = FoxNeutral60,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(FoxNeutral60.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CategoryBadge(category: WatchlistCategory) {
    val label = when (category) {
        WatchlistCategory.BEST_BUY -> "BEST BUY"
        WatchlistCategory.BEST_SELL -> "BEST SELL"
        WatchlistCategory.BEST_SWING -> "BEST SWING"
        WatchlistCategory.BEST_SCALP -> "BEST SCALP"
        WatchlistCategory.BEST_LONG_TERM -> "LONG TERM"
    }
    Text(
        text = label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = FoxAmber50,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(FoxAmber50.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatEnumName(name: String): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
