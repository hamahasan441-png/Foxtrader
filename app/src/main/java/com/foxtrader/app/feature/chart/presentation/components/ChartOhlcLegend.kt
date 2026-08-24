package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettingsRegistry
import com.foxtrader.app.feature.chart.presentation.ChartIndicatorRuntime
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
 * Minimal OHLC data-window overlay.
 *
 * The previous version stacked study controls under the OHLC row and covered a
 * large part of the top-left price action on phones. Study/system settings stay
 * available from the chart toolbar and Settings; the canvas overlay is now one
 * compact row only so candles remain readable.
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
    val strategyRuntimeSettings by StrategyRuntimeSettingsRegistry.state.collectAsStateWithLifecycle()

    SideEffect {
        ChartIndicatorRuntime.publishSettings(chartState.indicators.settings)
    }

    LaunchedEffect(
        strategyRuntimeSettings,
        chartState.indicators.activeStrategy,
        chartState.indicators.allStrategies,
    ) {
        if (chartState.indicators.activeStrategy != null || chartState.indicators.allStrategies) {
            viewModel.updateIndicators { it }
        }
    }

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

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FoxNeutral10.copy(alpha = 0.82f))
            .border(1.dp, FoxNeutral20.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Value("O", formatPrice(candle.open))
        Value("H", formatPrice(candle.high), FoxBullishText)
        Value("L", formatPrice(candle.low), FoxBearishText)
        Value("C", formatPrice(candle.close), closeColor)
        Value("Δ", formatPercent(changePct), trendColor)
        if (freshness != MarketDataFreshness.LIVE) {
            Text(
                text = sourceText,
                color = if (freshness == MarketDataFreshness.SIMULATED) FoxBearishText else FoxNeutral60,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun Value(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = FoxNeutral80) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
