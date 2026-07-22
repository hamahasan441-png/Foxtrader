package com.foxtrader.app.feature.chart.presentation

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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.feature.chart.presentation.components.CandleChart
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * The Chart screen — the heart of FoxTrader. Pure function of [ChartUiState].
 */
@Composable
fun ChartScreen(
    modifier: Modifier = Modifier,
    viewModel: ChartViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 24.dp),
    ) {
        ChartTopBar(state)
        Spacer(Modifier.height(1.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.hasData -> CandleChart(
                    candles = state.candles,
                    modifier = Modifier.fillMaxSize(),
                )
                state.isLoading -> CircularProgressIndicator(color = FoxAmber50)
                state.error != null -> Text(
                    text = state.error ?: "",
                    color = FoxBearishText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Text(
                    text = "No data",
                    color = FoxNeutral60,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ChartTopBar(state: ChartUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Fox", color = FoxAmber50, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text("Trader", color = FoxNeutral60, style = MaterialTheme.typography.titleMedium)
        Badge(state.symbol)
        Badge(state.timeframe.label)
        BiasBadge(state.bias)
        Spacer(Modifier.weight(1f))
        state.lastPrice?.let { price ->
            Text(
                text = formatPrice(price),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun BiasBadge(bias: Bias) {
    val (color, label) = when (bias) {
        Bias.BULLISH -> FoxBullishText to "BULLISH"
        Bias.BEARISH -> FoxBearishText to "BEARISH"
        Bias.NEUTRAL -> FoxNeutral60 to "NEUTRAL"
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun formatPrice(price: Double): String =
    if (price >= 1000) String.format("%,.2f", price) else String.format("%.5f", price)
