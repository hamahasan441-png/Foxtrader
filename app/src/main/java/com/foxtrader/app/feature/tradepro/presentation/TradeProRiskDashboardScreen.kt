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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AlertSeverity
import com.foxtrader.app.domain.model.tradepro.CorrelationGroup
import com.foxtrader.app.domain.model.tradepro.PositionHeat
import com.foxtrader.app.domain.model.tradepro.PositionSizeResult
import com.foxtrader.app.domain.model.tradepro.RiskAlert
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

/**
 * TRADEPRO Risk Dashboard - real-time portfolio risk visualisation.
 *
 * Surfaces: risk utilization gauge, daily P&L card, open position heat map,
 * alert feed, correlation exposure, and the Kelly-criterion position sizer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeProRiskDashboardScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TradeProRiskDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Risk Dashboard",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = FoxAmber50,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = FoxAmber50,
                ),
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = FoxAmber50)
            }

            state.error != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                LabCard {
                    Text(
                        text = state.error ?: "Unknown error.",
                        color = Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                if (state.priceDataIsStale) {
                    StalePriceDataBanner()
                }
                RiskUtilizationGauge(utilization = state.riskUtilization)
                DailyPnlCard(state = state)
                OpenRiskBreakdown(positionHeat = state.positionHeat)
                AlertFeed(alerts = state.alerts)
                CorrelationExposureCard(groups = state.correlationGroups)
                PositionSizerCard(
                    symbol = state.positionSizerSymbol,
                    direction = state.positionSizerDirection,
                    result = state.positionSizerResult,
                    onSymbolSelect = viewModel::setPositionSizerSymbol,
                    onDirectionSelect = viewModel::setPositionSizerDirection,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Risk Utilization Gauge
// ---------------------------------------------------------------------------

@Composable
private fun RiskUtilizationGauge(utilization: Float) {
    val gaugeColor = when {
        utilization < 0.4f -> FoxBullish
        utilization < 0.7f -> FoxAmber50
        else -> Color(0xFFEF5350)
    }

    LabCard {
        SectionTitle("Risk Utilization")
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { utilization.coerceIn(0f, 1f) },
                modifier = Modifier.size(120.dp),
                color = gaugeColor,
                trackColor = FoxNeutral15,
                strokeWidth = 10.dp,
                strokeCap = StrokeCap.Round,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${(utilization * 100).toInt()}%",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = gaugeColor,
                )
                Text(
                    text = "of budget",
                    fontSize = 11.sp,
                    color = FoxNeutral60,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                utilization < 0.4f -> "Low exposure - capacity available."
                utilization < 0.7f -> "Moderate exposure - monitor closely."
                else -> "High exposure - consider reducing."
            },
            fontSize = 12.sp,
            color = gaugeColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Daily P&L Card
// ---------------------------------------------------------------------------

@Composable
private fun DailyPnlCard(state: TradeProRiskDashboardUiState) {
    LabCard {
        SectionTitle("Daily P&L")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = "Net Points",
                value = String.format(Locale.US, "%+.1f", state.netPoints),
                color = pnlColor(state.netPoints),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Trades",
                value = state.tradesTaken.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = "Wins",
                value = state.wins.toString(),
                color = FoxBullish,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Losses",
                value = state.losses.toString(),
                color = if (state.losses > 0) FoxBearish else FoxNeutral60,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Open Risk Breakdown (Position Heat Bars)
// ---------------------------------------------------------------------------

@Composable
private fun OpenRiskBreakdown(positionHeat: List<PositionHeat>) {
    LabCard {
        SectionTitle("Open Risk")
        Spacer(Modifier.height(8.dp))
        if (positionHeat.isEmpty()) {
            Text(
                text = "No open positions.",
                color = FoxNeutral60,
                fontSize = 12.sp,
            )
        } else {
            positionHeat.forEach { position ->
                PositionHeatRow(position)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun PositionHeatRow(position: PositionHeat) {
    val dirColor = if (position.direction == Direction.BULLISH) FoxBullish else FoxBearish
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = position.symbol,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (position.direction == Direction.BULLISH) "LONG" else "SHORT",
                    fontSize = 10.sp,
                    color = dirColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(dirColor.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Text(
                text = String.format(Locale.US, "%.1f pts (%.0f%%)", position.riskPoints, position.heatPercent * 100),
                fontSize = 11.sp,
                color = FoxNeutral60,
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { position.heatPercent.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = dirColor,
            trackColor = FoxNeutral15,
        )
    }
}

// ---------------------------------------------------------------------------
// Alert Feed
// ---------------------------------------------------------------------------

@Composable
private fun AlertFeed(alerts: List<RiskAlert>) {
    LabCard {
        SectionTitle("Alerts")
        Spacer(Modifier.height(8.dp))
        if (alerts.isEmpty()) {
            Text(
                text = "All clear - no active risk alerts.",
                color = FoxBullish,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            alerts.forEach { alert ->
                AlertRow(alert)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun AlertRow(alert: RiskAlert) {
    val severityColor = when (alert.severity) {
        AlertSeverity.CRITICAL -> Color(0xFFEF5350)
        AlertSeverity.WARNING -> FoxAmber50
        AlertSeverity.INFO -> Color(0xFF42A5F5)
    }
    val severityLabel = when (alert.severity) {
        AlertSeverity.CRITICAL -> "CRITICAL"
        AlertSeverity.WARNING -> "WARNING"
        AlertSeverity.INFO -> "INFO"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(severityColor.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = severityLabel,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = severityColor,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(severityColor.copy(alpha = 0.15f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = alert.message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Correlation Exposure
// ---------------------------------------------------------------------------

@Composable
private fun CorrelationExposureCard(groups: List<CorrelationGroup>) {
    LabCard {
        SectionTitle("Correlation Exposure")
        Spacer(Modifier.height(8.dp))
        if (groups.isEmpty()) {
            Text(
                text = "No correlated positions detected.",
                color = FoxNeutral60,
                fontSize = 12.sp,
            )
        } else {
            groups.forEach { group ->
                CorrelationGroupRow(group)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CorrelationGroupRow(group: CorrelationGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FoxNeutral15)
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.symbols.joinToString(" / "),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = String.format(Locale.US, "r=%.2f", group.correlationCoefficient),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(FoxAmber50.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = String.format(Locale.US, "Combined exposure: %.1f pts", group.combinedExposure),
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
    }
}

// ---------------------------------------------------------------------------
// Position Sizing Calculator
// ---------------------------------------------------------------------------

@Composable
private fun PositionSizerCard(
    symbol: String,
    direction: Direction,
    result: PositionSizeResult?,
    onSymbolSelect: (String) -> Unit,
    onDirectionSelect: (Direction) -> Unit,
) {
    LabCard {
        SectionTitle("Position Sizer")
        Spacer(Modifier.height(10.dp))

        Text("Symbol", fontSize = 12.sp, color = FoxNeutral60)
        Spacer(Modifier.height(4.dp))
        ChipRow(
            items = TradeProRiskDashboardUiState.AVAILABLE_SYMBOLS.toList(),
            selected = symbol,
            label = { it },
            onSelect = onSymbolSelect,
        )

        Spacer(Modifier.height(12.dp))
        Text("Direction", fontSize = 12.sp, color = FoxNeutral60)
        Spacer(Modifier.height(4.dp))
        ChipRow(
            items = listOf(Direction.BULLISH, Direction.BEARISH),
            selected = direction,
            label = { if (it == Direction.BULLISH) "LONG" else "SHORT" },
            onSelect = onDirectionSelect,
        )

        Spacer(Modifier.height(14.dp))

        if (result != null) {
            PositionSizerResult(result)
        } else {
            Text(
                text = "Select symbol and direction to calculate size.",
                color = FoxNeutral60,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun PositionSizerResult(result: PositionSizeResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = "Contracts",
                value = result.recommendedContracts.toString(),
                color = FoxAmber50,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Risk/Contract",
                value = String.format(Locale.US, "%.1f pts", result.riskPerContract),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = "Total Risk",
                value = String.format(Locale.US, "%.1f pts", result.totalRiskPoints),
                color = if (result.totalRiskPoints > 0) FoxBearish else FoxNeutral60,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Kelly Fraction",
                value = String.format(Locale.US, "%.1f%%", result.kellyFraction * 100),
                color = FoxAmber50,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = "DD Budget Left",
                value = String.format(Locale.US, "%.1f pts", result.maxDrawdownBudget),
                color = if (result.maxDrawdownBudget > 10) FoxBullish else FoxBearish,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared private composables (per-screen pattern)
// ---------------------------------------------------------------------------

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

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FoxNeutral15),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, fontSize = 11.sp, color = FoxNeutral60)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun <T> ChipRow(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Text(
                text = label(item),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(if (isSelected) FoxAmber50 else FoxNeutral15)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Stale Price Data Banner
// ---------------------------------------------------------------------------

@Composable
private fun StalePriceDataBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u26A0",
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Price data is stale. Heat and P&L reflect entry prices, not current market.",
                fontSize = 11.sp,
                color = FoxAmber50,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullish
    value < 0.0 -> FoxBearish
    else -> FoxNeutral60
}
