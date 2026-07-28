package com.foxtrader.app.feature.portfolio.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.portfolio.PositionExposure
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxWarning
import kotlin.math.abs

/**
 * Portfolio screen — open exposure, net directional bias, unrealised P&L,
 * concentration warnings and correlation clusters.
 *
 * Surfaces [com.foxtrader.app.domain.usecase.portfolio.PortfolioEngine], which
 * was fully implemented and tested but unreachable before Sprint 7.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Portfolio", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = FoxAmber50,
                )

                !state.hasPositions -> EmptyPortfolio()

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.isSyntheticData) {
                        item { SyntheticNotice() }
                    }
                    item { ExposureSummaryCard(state) }
                    if (state.warnings.isNotEmpty()) {
                        item { WarningsCard(state.warnings) }
                    }
                    if (state.correlationClusters.isNotEmpty()) {
                        item { ClusterHeader() }
                        items(state.correlationClusters) { cluster ->
                            CorrelationClusterCard(cluster)
                        }
                    }
                    item { PositionsHeader() }
                    items(state.snapshot?.positions.orEmpty()) { position ->
                        PositionRow(position)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPortfolio() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.portfolio_no_positions_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.portfolio_no_positions_subtitle),
            color = FoxNeutral60,
            fontSize = 12.sp,
        )
    }
}

/**
 * Sprint 6 contract: anything computed over generated bars must say so.
 * Correlation between synthetic series is an artefact of the generator seed,
 * not a real market relationship.
 */
@Composable
private fun SyntheticNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FoxWarning.copy(alpha = 0.16f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = FoxWarning)
        Text(
            text = stringResource(R.string.portfolio_synthetic_notice),
            color = FoxWarning,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ExposureSummaryCard(state: PortfolioUiState) {
    val snapshot = state.snapshot ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Exposure", color = FoxNeutral60, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatPercent(snapshot.totalExposurePercent),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.portfolio_of_equity, formatMoney(state.accountEquity)),
                color = FoxNeutral60,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(12.dp))
            LongShortBar(
                longPercent = snapshot.longExposurePercent,
                shortPercent = snapshot.shortExposurePercent,
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Metric("Net direction", formatSignedPercent(snapshot.netDirectionalExposurePercent))
                Metric(
                    label = "Unrealised P&L",
                    value = formatSignedMoney(snapshot.unrealizedPnl),
                    valueColor = pnlColor(snapshot.unrealizedPnl),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Metric(
                    label = "Largest position",
                    value = snapshot.largestSymbol?.let {
                        "$it ${formatPercent(snapshot.largestSymbolExposurePercent)}"
                    } ?: "—",
                )
                Metric("Correlated", formatPercent(snapshot.correlatedExposurePercent))
            }
        }
    }
}

/** Proportional long-vs-short bar; widths are relative, not absolute equity. */
@Composable
private fun LongShortBar(longPercent: Double, shortPercent: Double) {
    val total = longPercent + shortPercent
    val longFraction = if (total > 0.0) (longPercent / total).toFloat() else 0f
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .semantics {
                    contentDescription = "Long ${formatPercent(longPercent)}, " +
                        "short ${formatPercent(shortPercent)}"
                },
        ) {
            if (longFraction > 0f) {
                Box(
                    Modifier
                        .weight(longFraction.coerceAtLeast(0.001f))
                        .fillMaxSize()
                        .background(FoxBullishText),
                )
            }
            if (longFraction < 1f) {
                Box(
                    Modifier
                        .weight((1f - longFraction).coerceAtLeast(0.001f))
                        .fillMaxSize()
                        .background(FoxBearishText),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("L ${formatPercent(longPercent)}", color = FoxBullishText, fontSize = 10.sp)
            Text("S ${formatPercent(shortPercent)}", color = FoxBearishText, fontSize = 10.sp)
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxWarning.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Risk warnings", color = FoxWarning, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            warnings.forEach { warning ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", color = FoxWarning, fontSize = 12.sp)
                    Text(warning, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ClusterHeader() =
    SectionLabel("Correlation clusters", "Positions that move as one risk unit")

@Composable
private fun PositionsHeader() = SectionLabel("Open positions", null)

@Composable
private fun SectionLabel(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (subtitle != null) {
            Text(subtitle, color = FoxNeutral60, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CorrelationClusterCard(cluster: CorrelationCluster) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                Text(
                    text = cluster.symbols.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    text = formatPercent(cluster.combinedExposurePercent),
                    color = FoxAmber50,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (cluster.isHedge) {
                    "Inversely correlated (${formatCorrelation(cluster.peakCorrelation)}) — " +
                        "these positions partly offset each other."
                } else {
                    "Correlated ${formatCorrelation(cluster.peakCorrelation)} — " +
                        "treat as a single position for risk purposes."
                },
                color = FoxNeutral60,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PositionRow(position: PositionExposure) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = position.symbol,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    DirectionTag(position.direction)
                }
                Text(
                    text = formatSignedMoney(position.unrealizedPnl),
                    color = pnlColor(position.unrealizedPnl),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (position.weightPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = FoxAmber50,
                trackColor = FoxNeutral10,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(R.string.portfolio_lots_at_notional, trimZeros(position.volume), trimZeros(position.notional)),
                    color = FoxNeutral60,
                    fontSize = 10.sp,
                )
                Text(
                    text = stringResource(R.string.portfolio_percent_of_equity, formatPercent(position.exposurePercent)),
                    color = FoxNeutral60,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun DirectionTag(direction: Direction) {
    val bullish = direction == Direction.BULLISH
    val color = if (bullish) FoxBullishText else FoxBearishText
    Text(
        text = if (bullish) "LONG" else "SHORT",
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun Metric(label: String, value: String, valueColor: Color? = null) {
    Column {
        Text(label, color = FoxNeutral60, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

// ---------------------------------------------------------------- formatting

@Composable
private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> MaterialTheme.colorScheme.onSurface
}

private fun formatPercent(value: Double): String = "${roundTo(value, 1)}%"

private fun formatSignedPercent(value: Double): String =
    (if (value > 0) "+" else "") + formatPercent(value)

private fun formatCorrelation(value: Double): String = roundTo(value, 2).toString()

private fun formatMoney(value: Double): String = "$" + roundTo(value, 0).toLong().toString()

private fun formatSignedMoney(value: Double): String {
    val sign = if (value > 0) "+" else if (value < 0) "-" else ""
    return "$sign$" + roundTo(abs(value), 2)
}

private fun trimZeros(value: Double): String {
    val rounded = roundTo(value, 2)
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun roundTo(value: Double, decimals: Int): Double {
    if (value.isNaN() || value.isInfinite()) return 0.0
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return kotlin.math.round(value * factor) / factor
}
