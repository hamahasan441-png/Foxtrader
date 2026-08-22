package com.foxtrader.app.feature.chart.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

/**
 * Scrollable panel displaying the unified signal history list.
 * Reuses the card/metric-row pattern from LitXSignalCard.
 */
@Composable
fun ChartSignalHistory(
    signals: List<ChartSignal>,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(FoxNeutral10)
            .padding(8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (signals.isEmpty()) {
            Text(
                text = "No signals",
                style = MaterialTheme.typography.bodySmall,
                color = FoxNeutral60,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            for (signal in signals) {
                SignalRow(signal)
            }
        }
    }
}

@Composable
private fun SignalRow(signal: ChartSignal) {
    val dirColor = if (signal.direction == Direction.BULLISH) FoxBullishText else FoxBearishText
    val dirLabel = if (signal.direction == Direction.BULLISH) "LONG" else "SHORT"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(signal.source)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = dirLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = dirColor,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(signal.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = FoxAmber50,
                )
                Spacer(Modifier.width(6.dp))
                LiveIndicator(isLive = signal.isLive)
            }
        }

        signal.label?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = FoxNeutral60,
            )
        }

        if (signal.entry != 0.0) {
            MetricRow("Entry", formatPrice(signal.entry))
        }
        if (signal.sl != 0.0) {
            MetricRow("SL", formatPrice(signal.sl))
        }
        if (signal.tp != 0.0) {
            MetricRow("TP", formatPrice(signal.tp))
        }
        signal.riskReward?.let { rr ->
            MetricRow("R:R", String.format(Locale.US, "%.2fR", rr))
        }
    }
}

@Composable
private fun SourceBadge(source: SignalSource) {
    val (label, color) = when (source) {
        SignalSource.LITX -> "LiTX" to FoxAmber50
        SignalSource.LIT -> "LiT" to FoxBullishText
        SignalSource.SMS -> "SMS" to FoxAmber50
        SignalSource.TRADEPRO -> "TradePro" to FoxBullishText
        SignalSource.SMT -> "SMT" to FoxBearishText
        SignalSource.BINARY3M -> "Binary 3m" to FoxBullishText
        SignalSource.STRATEGY -> "Strategy" to FoxAmber50
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun LiveIndicator(isLive: Boolean) {
    val color = if (isLive) Color(0xFF00E688) else FoxNeutral60
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatPrice(price: Double): String =
    if (price >= 1000) String.format(Locale.US, "%,.2f", price)
    else String.format(Locale.US, "%.5f", price)
