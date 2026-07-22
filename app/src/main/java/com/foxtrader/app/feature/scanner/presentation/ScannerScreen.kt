package com.foxtrader.app.feature.scanner.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.WatchlistCategory
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
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
            // Asset class filter chips
            AssetClassFilter(
                selected = state.selectedAssetClass,
                onSelect = viewModel::selectAssetClass,
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.filteredResults, key = { it.symbol }) { result ->
                            ScannerResultCard(result)
                        }
                    }
                }
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
                }
                ScoreBadge(result.score)
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
