package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.TradeProBacktestResult
import com.foxtrader.app.domain.model.tradepro.TradeProBacktestTrade
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

/**
 * TRADEPRO Backtest Report — replays the full framework lifecycle (signal ->
 * 3-contract T1/T2/runner management -> exit) over sourced history and renders a
 * point-based performance report: net/expectancy, win rate vs the plan's
 * break-even bar, staged-exit hit rates, an equity curve and the recent trades.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeProBacktestReportScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TradeProBacktestReportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TRADEPRO Backtest", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::runReport, enabled = !state.isRunning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-run report", tint = FoxAmber50)
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

            SelectorCard(state = state, viewModel = viewModel)

            if (state.isSynthetic) {
                LabCard {
                    Text(
                        text = "SIMULATED DATA — no real feed for this symbol. This report is " +
                            "illustrative of the framework's mechanics, not a live edge.",
                        color = FoxAmber50,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            val result = state.result
            when {
                state.isRunning -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                state.error != null -> LabCard {
                    Text(
                        text = state.error ?: "Backtest failed.",
                        color = FoxBearishText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                result != null && result.totalTrades > 0 -> ResultContent(result)

                result != null -> LabCard {
                    Text(
                        text = result.narrative,
                        color = FoxNeutral60,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> LabCard {
                    Text(
                        text = "Select a symbol to replay the TRADEPRO framework over its history.",
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
private fun SelectorCard(
    state: TradeProBacktestReportUiState,
    viewModel: TradeProBacktestReportViewModel,
) {
    LabCard {
        SectionTitle("Instrument")
        Spacer(Modifier.height(10.dp))
        Text("Symbol", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(
            items = state.availableSymbols,
            selected = state.symbol,
            label = { it },
            onSelect = viewModel::setSymbol,
        )
        Spacer(Modifier.height(12.dp))
        Text("Timeframe", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(
            items = listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1),
            selected = state.timeframe,
            label = { it.label },
            onSelect = viewModel::setTimeframe,
        )
    }
}

@Composable
private fun ResultContent(result: TradeProBacktestResult) {
    HeadlineCard(result)
    MetricsGrid(result)
    EquityCurveCard(result.equityCurve)
    DrawdownCard(result.drawdownCurve, result.maxDrawdownPoints)
    RDistributionCard(result.rMultiples)
    StagedExitCard(result)
    RecentTradesCard(result)
}

@Composable
private fun HeadlineCard(result: TradeProBacktestResult) {
    LabCard {
        SectionTitle("Summary")
        Spacer(Modifier.height(8.dp))
        Text(result.narrative, color = FoxNeutral60, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        Text("Win rate vs break-even bar", fontSize = 12.sp, color = FoxNeutral60)
        Spacer(Modifier.height(6.dp))
        BreakevenBar(winRate = result.winRate, required = result.requiredBreakevenWinRate)
        Spacer(Modifier.height(6.dp))
        val beats = result.beatsBreakeven
        Text(
            text = if (beats) {
                "Win rate ${pct(result.winRate)} clears the ${pct(result.requiredBreakevenWinRate)} " +
                    "break-even bar — positive expectancy."
            } else {
                "Win rate ${pct(result.winRate)} is below the ${pct(result.requiredBreakevenWinRate)} " +
                    "break-even bar for this plan."
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (beats) FoxBullishText else FoxBearishText,
        )
    }
}

@Composable
private fun MetricsGrid(result: TradeProBacktestResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Net (pts)", points(result.netPoints), pnlColor(result.netPoints), Modifier.weight(1f))
            MetricTile("Expectancy", points(result.expectancy), pnlColor(result.expectancy), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Win Rate", pct(result.winRate), FoxAmber50, Modifier.weight(1f))
            MetricTile("Profit Factor", profitFactor(result.profitFactor), FoxAmber50, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Trades", result.totalTrades.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            MetricTile("Avg R", ratio(result.avgR), pnlColor(result.avgR), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Max DD (pts)", points(result.maxDrawdownPoints), FoxBearishText, Modifier.weight(1f))
            MetricTile("Win / Loss", "${result.wins} / ${result.losses}", FoxNeutral60, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("System Quality", ratio(result.systemQualityNumber), sqnColor(result.systemQualityNumber), Modifier.weight(1f))
            MetricTile("Payoff", profitFactor(result.payoffRatio), FoxAmber50, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StagedExitCard(result: TradeProBacktestResult) {
    LabCard {
        SectionTitle("Staged-Exit Hit Rates")
        Spacer(Modifier.height(6.dp))
        Text(
            "Share of trades that reached each of the 3-contract targets.",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("T1", pct(result.t1HitRate), FoxBullishText, Modifier.weight(1f))
            MetricTile("T2", pct(result.t2HitRate), FoxBullishText, Modifier.weight(1f))
            MetricTile("Runner", pct(result.runnerHitRate), FoxBullishText, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Avg Win", points(result.avgWin), FoxBullishText, Modifier.weight(1f))
            MetricTile("Avg Loss", points(result.avgLoss), FoxBearishText, Modifier.weight(1f))
            MetricTile("Max Streak", "${result.maxWinStreak}W/${result.maxLossStreak}L", FoxNeutral60, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DrawdownCard(drawdown: List<Double>, maxDrawdown: Double) {
    LabCard {
        SectionTitle("Drawdown (pts)")
        Spacer(Modifier.height(6.dp))
        Text(
            "Peak-to-trough underwater equity. Max ${String.format(Locale.US, "-%.1f", maxDrawdown)} pts.",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(10.dp))
        if (drawdown.size < 2) {
            Text("Not enough closed trades to plot drawdown.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            DrawdownGraph(drawdown)
        }
    }
}

@Composable
private fun DrawdownGraph(drawdown: List<Double>) {
    val maxDd = drawdown.maxOf { it }.takeIf { it > 0.0 } ?: 1.0
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral15)
            .padding(8.dp),
    ) {
        val lastIndex = drawdown.lastIndex.coerceAtLeast(1)
        for (i in 1 until drawdown.size) {
            val d0 = drawdown[i - 1]
            val d1 = drawdown[i]
            val x0 = ((i - 1).toFloat() / lastIndex) * size.width
            val x1 = (i.toFloat() / lastIndex) * size.width
            // 0 drawdown at the top, deepest drawdown at the bottom.
            val y0 = (d0 / maxDd).toFloat() * size.height
            val y1 = (d1 / maxDd).toFloat() * size.height
            drawLine(
                color = FoxBearishText,
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = 2.5.dp.toPx(),
            )
        }
    }
}

@Composable
private fun RDistributionCard(rMultiples: List<Double>) {
    LabCard {
        SectionTitle("R-Multiple Distribution")
        Spacer(Modifier.height(6.dp))
        Text(
            "How trade outcomes cluster in R (multiples of risk).",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(12.dp))
        if (rMultiples.isEmpty()) {
            Text("No trades.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            val buckets = bucketizeR(rMultiples)
            val maxCount = (buckets.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                buckets.forEach { bucket ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                            if (bucket.count > 0) {
                                Text(
                                    text = bucket.count.toString(),
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    fontSize = 10.sp,
                                    color = FoxNeutral60,
                                )
                            }
                            val barHeight = (BAR_MAX_HEIGHT * bucket.count / maxCount).dp
                            if (barHeight > 0.dp) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth(0.7f)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (bucket.positive) FoxBullishText else FoxBearishText),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = bucket.label,
                            fontSize = 9.sp,
                            color = FoxNeutral60,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EquityCurveCard(points: List<Double>) {
    LabCard {
        SectionTitle("Equity Curve (cumulative pts)")
        Spacer(Modifier.height(10.dp))
        if (points.size < 2) {
            Text("Not enough closed trades to plot a curve.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            MiniEquityCurve(points)
        }
    }
}

@Composable
private fun RecentTradesCard(result: TradeProBacktestResult) {
    LabCard {
        SectionTitle("Recent Trades")
        Spacer(Modifier.height(8.dp))
        if (result.trades.isEmpty()) {
            Text("No trades triggered.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            result.trades.takeLast(8).asReversed().forEach { trade ->
                TradeRow(trade)
            }
        }
    }
}

@Composable
private fun TradeRow(trade: TradeProBacktestTrade) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dirColor = if (trade.direction == Direction.BULLISH) FoxBullishText else FoxBearishText
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${if (trade.direction == Direction.BULLISH) "LONG" else "SHORT"} • ${trade.exitReason}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = dirColor,
            )
            Text(
                text = stageLabel(trade),
                fontSize = 11.sp,
                color = FoxNeutral60,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(points(trade.netPoints), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pnlColor(trade.netPoints))
            Text("${ratio(trade.rMultiple)}R", fontSize = 11.sp, color = FoxNeutral60)
        }
    }
}

@Composable
private fun BreakevenBar(winRate: Double, required: Double) {
    val beats = winRate >= required && required > 0.0
    val fraction = winRate.toFloat().coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FoxNeutral15),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (beats) FoxBullishText else FoxBearishText),
            )
        }
    }
}

@Composable
private fun MiniEquityCurve(points: List<Double>) {
    val minValue = points.minOf { it }
    val maxValue = points.maxOf { it }
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral15)
            .padding(8.dp),
    ) {
        val lastIndex = points.lastIndex.coerceAtLeast(1)
        for (i in 1 until points.size) {
            val v0 = points[i - 1]
            val v1 = points[i]
            val x0 = ((i - 1).toFloat() / lastIndex) * size.width
            val x1 = (i.toFloat() / lastIndex) * size.width
            val y0 = size.height - (((v0 - minValue) / span).toFloat() * size.height)
            val y1 = size.height - (((v1 - minValue) / span).toFloat() * size.height)
            drawLine(
                color = if (v1 >= v0) FoxBullishText else FoxBearishText,
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

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> FoxNeutral60
}

private fun sqnColor(sqn: Double): Color = when {
    sqn >= 2.0 -> FoxBullishText
    sqn >= 1.0 -> FoxAmber50
    else -> FoxBearishText
}

private data class RBucket(val label: String, val count: Int, val positive: Boolean)

/** Buckets realised R into fixed ranges for the distribution histogram. */
private fun bucketizeR(rMultiples: List<Double>): List<RBucket> {
    var fullLoss = 0   // <= -1R
    var partialLoss = 0 // -1R .. 0
    var smallWin = 0   // 0 .. 1R
    var oneToTwo = 0   // 1 .. 2R
    var twoToThree = 0 // 2 .. 3R
    var runner = 0     // >= 3R
    for (r in rMultiples) {
        when {
            r <= -1.0 -> fullLoss++
            r < 0.0 -> partialLoss++
            r < 1.0 -> smallWin++
            r < 2.0 -> oneToTwo++
            r < 3.0 -> twoToThree++
            else -> runner++
        }
    }
    return listOf(
        RBucket("<=-1", fullLoss, false),
        RBucket("-1..0", partialLoss, false),
        RBucket("0..1", smallWin, true),
        RBucket("1..2", oneToTwo, true),
        RBucket("2..3", twoToThree, true),
        RBucket(">=3", runner, true),
    )
}

private const val BAR_MAX_HEIGHT = 80f

private fun stageLabel(trade: TradeProBacktestTrade): String = when {
    trade.reachedRunner -> "Runner • ${trade.confidence}% conf"
    trade.reachedT2 -> "T2 • ${trade.confidence}% conf"
    trade.reachedT1 -> "T1 • ${trade.confidence}% conf"
    else -> "Stopped • ${trade.confidence}% conf"
}

private fun points(value: Double): String = String.format(Locale.US, "%+.1f", value)
private fun pct(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100)
private fun ratio(value: Double): String = if (value.isFinite()) String.format(Locale.US, "%.2f", value) else "—"
private fun profitFactor(value: Double): String =
    if (value.isFinite()) String.format(Locale.US, "%.2f", value) else "\u221E"
