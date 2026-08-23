package com.foxtrader.app.feature.backtest.presentation

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.BinaryBacktestResult
import com.foxtrader.app.domain.model.BinaryOutcome
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EquityPoint
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsReport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

/**
 * Backtesting Lab — visual UI for running non-repainting strategy simulations
 * and comparing raw strategy results against AI-approved trades.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacktestLabScreen(
    onNavigateToTradeProReport: () -> Unit = {},
    onNavigateToRiskSimulator: () -> Unit = {},
    viewModel: BacktestLabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backtesting Lab", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                actions = {
                    IconButton(onClick = onNavigateToTradeProReport) {
                        Icon(Icons.Default.ShowChart, contentDescription = "TRADEPRO backtest report", tint = FoxAmber50)
                    }
                    IconButton(onClick = onNavigateToRiskSimulator) {
                        Icon(Icons.Default.Casino, contentDescription = "Monte Carlo risk simulator", tint = FoxAmber50)
                    }
                    IconButton(onClick = viewModel::runBacktest, enabled = !state.isRunning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Run backtest", tint = FoxAmber50)
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            ConfigurationCard(state = state, viewModel = viewModel)

            Button(
                onClick = viewModel::runBacktest,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
            ) {
                Text(
                    text = if (state.isRunning) "Running…" else "Run Non-Repainting Backtest",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (state.hasReplayData && !state.isRunning) {
                BacktestVisualTester(state = state, viewModel = viewModel)
            }

            val result = state.result
            val binaryResult = state.binaryResult
            when {
                state.isRunning -> Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                state.error != null -> LabCard {
                    Text(
                        text = state.error ?: "Backtest failed.",
                        color = FoxBearishText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                binaryResult != null -> BinaryResultContent(binaryResult, state.analyticsReport)

                result != null -> ResultContent(result = result, analytics = state.analyticsReport)

                else -> LabCard {
                    Text(
                        text = stringResource(R.string.backtest_empty_prompt),
                        color = FoxNeutral60,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfigurationCard(
    state: BacktestLabUiState,
    viewModel: BacktestLabViewModel,
) {
    LabCard {
        SectionTitle("Configuration")
        Spacer(Modifier.height(10.dp))

        Text("Data Provider", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(
            items = if (state.isBinary3m) listOf(DataProvider.DERIV) else DataProvider.implemented(),
            selected = state.dataProvider,
            label = { it.displayName },
            onSelect = viewModel::setDataProvider,
        )

        Spacer(Modifier.height(12.dp))
        Text("Symbol", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(
            items = if (state.isBinary3m) BacktestLabUiState.DERIV_BINARY_SYMBOLS else state.availableSymbols,
            selected = state.symbol,
            label = { it },
            onSelect = viewModel::setSymbol,
        )

        Spacer(Modifier.height(12.dp))
        Text("Timeframe", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(
            items = if (state.isBinary3m) listOf(Timeframe.M1) else listOf(Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1),
            selected = state.timeframe,
            label = { it.label },
            onSelect = viewModel::setTimeframe,
        )

        Spacer(Modifier.height(12.dp))
        Text("Strategy Template", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(
            items = BacktestStrategyTemplate.entries,
            selected = state.strategy.takeIf { state.selectedBlueprintId == null },
            label = { it.displayName },
            onSelect = viewModel::setStrategy,
        )
        if (state.strategyBlueprints.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("My Builder Strategies", fontSize = 12.sp, color = FoxNeutral60)
            ChipRow(
                items = state.strategyBlueprints,
                selected = state.selectedBlueprint,
                label = { it.name },
                onSelect = { viewModel.setBlueprint(it.id) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.selectedStrategyDescription,
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )

        Spacer(Modifier.height(14.dp))
        RiskSlider(state.riskPercent, viewModel::setRiskPercent)

        if (state.isBinary3m) {
            Spacer(Modifier.height(12.dp))
            BinaryPayoutSlider(state.binaryPayoutRatio, viewModel::setBinaryPayoutRatio)
            Spacer(Modifier.height(12.dp))
            BinaryConfidenceSlider(state.binaryMinConfidence, viewModel::setBinaryMinConfidence)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Fixed model: signal after a closed M1 candle → entry at next M1 open → expiry after 3 minutes. No overlapping contracts and no Martingale.",
                style = MaterialTheme.typography.bodySmall,
                color = FoxNeutral60,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI Master Decision scoring", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = stringResource(R.string.backtest_ai_scoring_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
                Switch(
                    checked = state.aiScoringEnabled,
                    onCheckedChange = viewModel::setAiScoringEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = FoxAmber50),
                )
            }
        }
    }
}

@Composable
private fun BinaryResultContent(result: BinaryBacktestResult, analytics: BacktestAnalyticsReport?) {
    val metrics = result.metrics
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Net P&L", money(metrics.netProfit), pnlColor(metrics.netProfit), Modifier.weight(1f))
            MetricTile("Return", percent(metrics.returnPercent), pnlColor(metrics.returnPercent), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Win Rate", percent(metrics.winRate), FoxAmber50, Modifier.weight(1f))
            MetricTile("Break-even", percent(metrics.breakEvenWinRate), FoxNeutral60, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Edge vs BE", signedPercent(metrics.edgeVsBreakEven), pnlColor(metrics.edgeVsBreakEven), Modifier.weight(1f))
            MetricTile("Profit Factor", ratio(metrics.profitFactor), FoxAmber50, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Contracts", metrics.totalTrades.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            MetricTile("Max DD", percent(metrics.maxDrawdownPercent), FoxBearishText, Modifier.weight(1f))
        }
    }

    EquityCurveCard(result.equityCurve)
    AnalyticsCard(analytics)

    LabCard {
        SectionTitle("3-Minute Contract Model")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Payout ${percent(result.config.payoutRatio * 100.0)} • stake risk ${percent(result.config.riskPercent)} • confidence ≥ ${result.config.minConfidence}%",
            color = FoxNeutral60,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Wins ${metrics.wins} • Losses ${metrics.losses} • Ties ${metrics.ties} • expectancy ${money2(metrics.expectancyPerTrade)} / contract",
            color = FoxNeutral60,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Max streak: ${metrics.maxConsecutiveWins} wins / ${metrics.maxConsecutiveLosses} losses. A positive historical edge does not guarantee future returns.",
            color = FoxNeutral60,
            fontSize = 11.sp,
        )
    }

    LabCard {
        SectionTitle("Recent 3m Contracts")
        Spacer(Modifier.height(8.dp))
        if (result.trades.isEmpty()) {
            Text("No setup passed the selected confidence threshold.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            result.trades.takeLast(8).asReversed().forEach { trade ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dirColor = if (trade.direction == Direction.BULLISH) FoxBullishText else FoxBearishText
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${trade.id} ${if (trade.direction == Direction.BULLISH) "CALL" else "PUT"} • ${trade.outcome}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = when (trade.outcome) {
                                BinaryOutcome.WIN -> FoxBullishText
                                BinaryOutcome.LOSS -> FoxBearishText
                                BinaryOutcome.TIE -> FoxNeutral60
                            },
                        )
                        Text(
                            text = "${trade.confidence}% • entry ${price(trade.entryPrice)} → expiry ${price(trade.expiryPrice)}",
                            fontSize = 11.sp,
                            color = dirColor,
                        )
                    }
                    Text(money2(trade.pnl), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pnlColor(trade.pnl))
                }
            }
        }
    }
}

@Composable
private fun ResultContent(result: BacktestResult, analytics: BacktestAnalyticsReport?) {
    MetricsGrid(result)
    EquityCurveCard(result.equityCurve)
    AnalyticsCard(analytics)
    AiComparisonCard(result)
    RecentTradesCard(result)
}

@Composable
private fun MetricsGrid(result: BacktestResult) {
    val metrics = result.metrics
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Net P&L", money(metrics.netProfit), pnlColor(metrics.netProfit), Modifier.weight(1f))
            MetricTile("Return", percent(metrics.returnPercent), pnlColor(metrics.returnPercent), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Win Rate", percent(metrics.winRate), FoxAmber50, Modifier.weight(1f))
            MetricTile("Profit Factor", ratio(metrics.profitFactor), FoxAmber50, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Trades", metrics.totalTrades.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            MetricTile("Max DD", percent(metrics.maxDrawdownPercent), FoxBearishText, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EquityCurveCard(points: List<EquityPoint>) {
    LabCard {
        SectionTitle("Equity Curve")
        Spacer(Modifier.height(10.dp))
        if (points.size < 2) {
            Text("Not enough equity points.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            MiniEquityCurve(points)
        }
    }
}

@Composable
private fun AnalyticsCard(analytics: BacktestAnalyticsReport?) {
    LabCard {
        SectionTitle("Validation Analytics")
        Spacer(Modifier.height(10.dp))
        if (analytics == null) {
            Text("Run a backtest to compute walk-forward and Monte Carlo analytics.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            val walkForward = analytics.walkForward
            if (walkForward == null) {
                Text("Walk-forward validation needs at least 4 trades.", color = FoxNeutral60, fontSize = 12.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricTile("WF Stability", "${walkForward.stabilityScore}", FoxAmber50, Modifier.weight(1f))
                    MetricTile("OOS PF", ratio(walkForward.outOfSample.profitFactor), FoxAmber50, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(walkForward.verdict, color = FoxNeutral60, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            val monteCarlo = analytics.monteCarlo
            if (monteCarlo == null) {
                Text("Monte Carlo needs at least 6 trades.", color = FoxNeutral60, fontSize = 12.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricTile("95% DD", money(-monteCarlo.confidence95MaxDrawdown), FoxBearishText, Modifier.weight(1f))
                    MetricTile("Ruin Risk", percent(monteCarlo.riskOfRuinPercent), FoxBearishText, Modifier.weight(1f))
                }
            }

            if (analytics.recommendations.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                analytics.recommendations.take(3).forEach { recommendation ->
                    Text("• $recommendation", color = FoxNeutral60, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AiComparisonCard(result: BacktestResult) {
    if (!result.aiScoringEnabled) return
    LabCard {
        SectionTitle("AI Gate Comparison")
        Spacer(Modifier.height(10.dp))
        val approval = result.aiApprovalRate
        if (approval == null) {
            Text("No trades had enough entry context for AI scoring.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("AI Approval", percent(approval), FoxAmber50, Modifier.weight(1f))
                MetricTile(
                    "AI Trades",
                    (result.aiFilteredMetrics?.totalTrades ?: 0).toString(),
                    MaterialTheme.colorScheme.onSurface,
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("All Win Rate", percent(result.metrics.winRate), FoxNeutral60, Modifier.weight(1f))
                MetricTile("AI Win Rate", percent(result.aiFilteredMetrics?.winRate), FoxAmber50, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("All PF", ratio(result.metrics.profitFactor), FoxNeutral60, Modifier.weight(1f))
                MetricTile("AI PF", ratio(result.aiFilteredMetrics?.profitFactor), FoxAmber50, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecentTradesCard(result: BacktestResult) {
    LabCard {
        SectionTitle("Recent Trades")
        Spacer(Modifier.height(8.dp))
        if (result.trades.isEmpty()) {
            Text("No trades generated by this strategy.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            result.trades.takeLast(6).asReversed().forEach { trade ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dirColor = if (trade.direction == Direction.BULLISH) FoxBullishText else FoxBearishText
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${trade.id} ${if (trade.direction == Direction.BULLISH) "LONG" else "SHORT"} • ${trade.exitReason}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = dirColor,
                        )
                        Text(
                            text = trade.setupType.orEmpty().ifBlank { "Strategy entry" },
                            fontSize = 11.sp,
                            color = FoxNeutral60,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(trade.netPnL), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pnlColor(trade.netPnL))
                        Text(aiTradeLabel(trade), fontSize = 11.sp, color = FoxNeutral60)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniEquityCurve(points: List<EquityPoint>) {
    val minBalance = points.minOf { it.balance }
    val maxBalance = points.maxOf { it.balance }
    val span = (maxBalance - minBalance).takeIf { it > 0.0 } ?: 1.0
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral15)
            .padding(8.dp),
    ) {
        for (i in 1 until points.size) {
            val p0 = points[i - 1]
            val p1 = points[i]
            val x0 = ((i - 1).toFloat() / (points.lastIndex).coerceAtLeast(1)) * size.width
            val x1 = (i.toFloat() / (points.lastIndex).coerceAtLeast(1)) * size.width
            val y0 = size.height - (((p0.balance - minBalance) / span).toFloat() * size.height)
            val y1 = size.height - (((p1.balance - minBalance) / span).toFloat() * size.height)
            drawLine(
                color = if (p1.balance >= p0.balance) FoxBullishText else FoxBearishText,
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
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
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun <T> ChipRow(
    items: List<T>,
    selected: T?,
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

@Composable
private fun RiskSlider(value: Double, onChange: (Double) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Risk per Trade", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("${format(value)}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FoxAmber50)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = 0.1f..5f,
            colors = SliderDefaults.colors(
                thumbColor = FoxAmber50,
                activeTrackColor = FoxAmber50,
            ),
        )
    }
}

@Composable
private fun BinaryPayoutSlider(value: Double, onChange: (Double) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Binary payout", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(percent(value * 100.0), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FoxAmber50)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = 0.50f..1.20f,
            colors = SliderDefaults.colors(thumbColor = FoxAmber50, activeTrackColor = FoxAmber50),
        )
    }
}

@Composable
private fun BinaryConfidenceSlider(value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Minimum confidence", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("$value%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FoxAmber50)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 60f..90f,
            steps = 29,
            colors = SliderDefaults.colors(thumbColor = FoxAmber50, activeTrackColor = FoxAmber50),
        )
    }
}

private fun aiTradeLabel(trade: BacktestTrade): String {
    val status = trade.aiApproved?.let { if (it) "AI ✓" else "AI ✕" } ?: return "AI —"
    val grade = trade.aiGrade?.let { " $it" }.orEmpty()
    val confidence = trade.aiConfidence?.let { " ${it.toInt()}%" }.orEmpty()
    val confluences = trade.aiConfluenceCount?.let { " $it/9" }.orEmpty()
    return status + grade + confidence + confluences
}

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> FoxNeutral60
}

private fun money(value: Double): String = String.format(Locale.US, "\$%,.0f", value)
private fun percent(value: Double?): String = value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
private fun ratio(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
private fun signedPercent(value: Double): String = String.format(Locale.US, "%+.1f%%", value)
private fun money2(value: Double): String = String.format(Locale.US, "$%,.2f", value)
private fun price(value: Double): String = String.format(Locale.US, "%.5f", value)
private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
