package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.SymbolCorrelationCluster
import com.foxtrader.app.domain.model.tradepro.SymbolCorrelationMatrix
import com.foxtrader.app.domain.model.tradepro.SymbolCorrelationPair
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrelationScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: CorrelationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Correlation Matrix", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::compute, enabled = !state.isComputing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recompute", tint = FoxAmber50)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            TimeframeRow(state, viewModel)

            when {
                state.isComputing -> Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                state.error != null -> LabCard { Text(state.error ?: "Failed.", color = FoxBearishText, fontSize = 13.sp) }

                state.hasMatrix -> {
                    SummaryCard(state.matrix)
                    HeatmapCard(state.matrix)
                    LegendCard()
                    if (state.matrix.clusters.isNotEmpty()) ClustersCard(state.matrix.clusters)
                    if (state.matrix.notablePairs.isNotEmpty()) NotablePairsCard(state.matrix.notablePairs)
                }

                else -> LabCard {
                    Text(state.matrix.narrative, color = FoxNeutral60, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimeframeRow(state: CorrelationUiState, viewModel: CorrelationViewModel) {
    LabCard {
        Text("Timeframe", fontSize = 12.sp, color = FoxNeutral60)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf ->
                Chip(tf.label, state.timeframe == tf) { viewModel.setTimeframe(tf) }
            }
        }
    }
}

@Composable
private fun SummaryCard(matrix: SymbolCorrelationMatrix) {
    LabCard {
        SectionTitle("Concentration Risk")
        Spacer(Modifier.height(8.dp))
        Text(matrix.narrative, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HeatmapCard(matrix: SymbolCorrelationMatrix) {
    LabCard {
        SectionTitle("Heatmap (${matrix.windowBars} bars)")
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                // Header row: corner + column labels.
                Row {
                    HeatCorner()
                    matrix.symbols.forEach { HeatLabel(it) }
                }
                matrix.symbols.forEachIndexed { i, rowSymbol ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HeatLabel(rowSymbol)
                        matrix.symbols.indices.forEach { j ->
                            HeatCell(matrix.values[i][j], isDiagonal = i == j)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(value: Double, isDiagonal: Boolean) {
    Box(
        modifier = Modifier
            .padding(1.dp)
            .size(width = 44.dp, height = 34.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isDiagonal) FoxNeutral15 else correlationColor(value)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            String.format(Locale.US, "%.2f", value),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDiagonal) FoxNeutral60 else Color.White,
        )
    }
}

@Composable
private fun HeatLabel(symbol: String) {
    Box(
        modifier = Modifier.padding(1.dp).size(width = 44.dp, height = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol.take(6), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = FoxNeutral60, maxLines = 1)
    }
}

@Composable
private fun HeatCorner() {
    Box(modifier = Modifier.padding(1.dp).size(width = 44.dp, height = 34.dp))
}

@Composable
private fun LegendCard() {
    LabCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LegendSwatch(correlationColor(-0.9), "Inverse")
            LegendSwatch(correlationColor(0.0), "Independent")
            LegendSwatch(correlationColor(0.9), "Together")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Strong positive correlation between open positions = hidden concentration. Diversify direction.",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, fontSize = 10.sp, color = FoxNeutral60)
    }
}

@Composable
private fun ClustersCard(clusters: List<SymbolCorrelationCluster>) {
    LabCard {
        SectionTitle("Correlated Clusters")
        Spacer(Modifier.height(8.dp))
        clusters.forEach { cluster ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    cluster.symbols.joinToString(" \u00B7 "),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "avg ${String.format(Locale.US, "%.2f", cluster.averageCorrelation)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = correlationColor(cluster.averageCorrelation),
                )
            }
        }
    }
}

@Composable
private fun NotablePairsCard(pairs: List<SymbolCorrelationPair>) {
    LabCard {
        SectionTitle("Strongest Links")
        Spacer(Modifier.height(8.dp))
        pairs.take(8).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${pair.symbolA} / ${pair.symbolB}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    String.format(Locale.US, "%+.2f", pair.correlation),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = correlationColor(pair.correlation),
                )
            }
        }
    }
}

// --- Shared private composables ---

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (selected) FoxAmber50 else FoxNeutral15)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun LabCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FoxAmber50)
}

/**
 * Maps a correlation (-1..1) to a colour: strong positive -> red (concentration danger),
 * near-zero -> neutral grey, strong negative -> green (a natural hedge).
 */
private fun correlationColor(r: Double): Color {
    val magnitude = abs(r).coerceIn(0.0, 1.0).toFloat()
    return if (r >= 0.0) {
        lerp(FoxNeutral15, FoxBearishText, magnitude)
    } else {
        lerp(FoxNeutral15, FoxBullishText, magnitude)
    }
}

private fun lerp(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)
