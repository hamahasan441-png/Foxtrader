package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxtrader.app.domain.model.BacktestMetrics
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlin.math.abs


/**
 * Compact overlay card summarising the active strategy: the live actionable
 * signal (direction, entry/SL/TP, R:R, confidence) plus a backtest performance
 * strip (win rate, trades, profit factor, return). Fully null-safe: renders a
 * helpful note when there's no signal yet or not enough data.
 */
@Composable
fun StrategySignalCard(
    strategy: StrategyType,
    signal: StrategySignal?,
    metrics: BacktestMetrics?,
    note: String?,
    computing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 180.dp, max = 240.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Header: strategy name + computing hint
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = strategy.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )
            if (computing) {
                Text(
                    text = "scanning…",
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxNeutral60,
                )
            }
        }

        // Live signal block
        if (signal != null) {
            val bullish = signal.direction == Direction.BULLISH
            val dirColor = if (bullish) FoxBullishText else FoxBearishText
            val risk = abs(signal.entry - signal.stopLoss)
            val reward = abs(signal.takeProfit - signal.entry)
            val rr = if (risk > 0.0) reward / risk else 0.0

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (bullish) "BUY" else "SELL",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(dirColor)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                signal.confidence?.let {
                    Text(
                        text = "$it% conf",
                        style = MaterialTheme.typography.labelSmall,
                        color = FoxNeutral60,
                    )
                }
                Text(
                    text = "R:R ${String.format("%.1f", rr)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            SignalLevelRow("Entry", signal.entry, MaterialTheme.colorScheme.onSurface)
            SignalLevelRow("Stop", signal.stopLoss, FoxBearishText)
            SignalLevelRow("Target", signal.takeProfit, FoxBullishText)
            signal.setupType?.let {
                Text(
                    text = it.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxNeutral60,
                    fontSize = 9.sp,
                )
            }
        } else {
            Text(
                text = note ?: "No active setup on the latest bar.",
                style = MaterialTheme.typography.labelSmall,
                color = FoxNeutral60,
            )
        }

        // Backtest performance strip
        if (metrics != null && metrics.totalTrades > 0) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Stat("Win", "${metrics.winRate.toInt()}%")
                Stat("Trades", metrics.totalTrades.toString())
                Stat("PF", formatPf(metrics.profitFactor))
                Stat("Return", "${sign(metrics.returnPercent)}${String.format("%.1f", metrics.returnPercent)}%")
            }
        }
    }
}

@Composable
private fun SignalLevelRow(label: String, price: Double, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FoxNeutral60,
            modifier = Modifier.widthIn(min = 44.dp),
        )
        Text(
            text = formatPrice(price),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FoxNeutral60,
            fontSize = 9.sp,
        )
    }
}

private fun formatPrice(price: Double): String =
    if (price >= 1000) String.format("%,.2f", price) else String.format("%.5f", price)

private fun formatPf(pf: Double): String = when {
    pf >= 1_000_000 -> "∞"
    else -> String.format("%.2f", pf)
}

private fun sign(v: Double): String = if (v >= 0) "+" else ""
