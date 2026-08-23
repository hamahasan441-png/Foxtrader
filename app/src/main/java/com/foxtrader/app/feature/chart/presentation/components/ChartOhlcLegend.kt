package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.feature.chart.presentation.ChartViewModel
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxNeutral80
import java.util.Locale
import kotlin.math.abs

/**
 * Compact data-window legend for the latest visible bar plus the active-study
 * command row immediately beneath it.
 *
 * This component already owns the chart's TopStart overlay position, so keeping
 * study controls here gives TradingView-style in-chart settings without adding a
 * second layout layer or shrinking the price canvas. hiltViewModel resolves the
 * same ChartViewModel instance owned by ChartScreen's navigation entry.
 */
@Composable
internal fun ChartOhlcLegend(
    candle: Candle?,
    previousCandle: Candle?,
    freshness: MarketDataFreshness,
    modifier: Modifier = Modifier,
    viewModel: ChartViewModel = hiltViewModel(),
) {
    if (candle == null) return
    val chartState by viewModel.uiState.collectAsStateWithLifecycle()

    val changePct = if (candle.open != 0.0) {
        ((candle.close - candle.open) / candle.open) * 100.0
    } else {
        0.0
    }
    val trendColor = if (candle.isBullish) FoxBullishText else FoxBearishText
    val sourceText = when (freshness) {
        MarketDataFreshness.LIVE -> stringResource(R.string.chart_legend_source_live)
        MarketDataFreshness.DELAYED -> stringResource(R.string.chart_data_delayed)
        MarketDataFreshness.CACHED -> stringResource(R.string.chart_legend_source_cached)
        MarketDataFreshness.SIMULATED -> stringResource(R.string.chart_legend_source_simulated)
    }
    val description = stringResource(
        R.string.chart_legend_description,
        formatPrice(candle.open),
        formatPrice(candle.high),
        formatPrice(candle.low),
        formatPrice(candle.close),
        formatPercent(changePct),
        sourceText,
    )
    val closeColor = if (previousCandle != null && candle.close >= previousCandle.close) {
        FoxBullishText
    } else {
        trendColor
    }

    Column(
        modifier = modifier.fillMaxWidth(0.88f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FoxNeutral10.copy(alpha = 0.92f))
                .border(1.dp, FoxNeutral20, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .semantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Value("O", formatPrice(candle.open))
            Value("H", formatPrice(candle.high), FoxBullishText)
            Value("L", formatPrice(candle.low), FoxBearishText)
            Value("C", formatPrice(candle.close), closeColor)
            Value("Δ", formatPercent(changePct), trendColor)
            Value("V", formatVolume(candle.volume), FoxNeutral80)
            Text(
                text = sourceText,
                color = if (freshness == MarketDataFreshness.SIMULATED) FoxBearishText else FoxNeutral60,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }

        ChartStudyCornerControls(
            toggles = chartState.indicators,
            onChange = viewModel::updateIndicators,
        )
    }
}

@Composable
private fun Value(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = FoxNeutral80) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            color = FoxNeutral60,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
        Text(
            text = value,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatPrice(value: Double): String = when {
    abs(value) >= 10_000.0 -> String.format(Locale.US, "%,.0f", value)
    abs(value) >= 100.0 -> String.format(Locale.US, "%,.2f", value)
    abs(value) >= 1.0 -> String.format(Locale.US, "%.4f", value)
    else -> String.format(Locale.US, "%.5f", value)
}

private fun formatPercent(value: Double): String = String.format(Locale.US, "%+.2f%%", value)

private fun formatVolume(value: Double): String {
    if (!value.isFinite() || value < 0.0) return "—"
    return when {
        abs(value) >= 1_000_000_000.0 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
        abs(value) >= 1_000_000.0 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        abs(value) >= 1_000.0 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
