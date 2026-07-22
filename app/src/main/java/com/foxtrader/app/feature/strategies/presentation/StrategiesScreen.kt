package com.foxtrader.app.feature.strategies.presentation

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Strategies screen — a dedicated section listing actionable setups
 * detected across the watchlist (harmonics, order blocks, R:R setups).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategiesScreen(
    viewModel: StrategiesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Strategies", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                actions = {
                    IconButton(onClick = viewModel::scan) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = FoxAmber50)
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
                .padding(padding),
        ) {
            // Summary bar
            if (state.hasSignals) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("${state.signals.size} setups", color = FoxNeutral60, fontSize = 13.sp)
                    Text("${state.bullishCount} long", color = FoxBullishText, fontSize = 13.sp)
                    Text("${state.bearishCount} short", color = FoxBearishText, fontSize = 13.sp)
                }
            }

            when {
                state.isScanning -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = FoxAmber50)
                }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Error: ${state.error}", color = FoxBearishText)
                }
                !state.hasSignals -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No setups found. Pull to rescan.", color = FoxNeutral60)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                ) {
                    items(state.signals, key = { it.id }) { signal ->
                        SignalCard(signal)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalCard(signal: StrategySignalItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(signal.symbol, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    val dirColor = if (signal.direction == Direction.BULLISH) FoxBullishText else FoxBearishText
                    val dirLabel = if (signal.direction == Direction.BULLISH) "LONG" else "SHORT"
                    Text(
                        dirLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = dirColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(dirColor.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                // Confidence
                Text(
                    "${signal.confidence}%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = confidenceColor(signal.confidence),
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(signal.strategyName, fontSize = 13.sp, color = FoxAmber50, fontWeight = FontWeight.SemiBold)

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricLabel("Entry", "%.5f".format(signal.entry))
                MetricLabel("SL", "%.5f".format(signal.stopLoss))
                MetricLabel("TP", "%.5f".format(signal.takeProfit))
                MetricLabel("R:R", "%.1f".format(signal.riskReward))
            }

            if (signal.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(signal.note, fontSize = 11.sp, color = FoxNeutral60)
            }
        }
    }
}

@Composable
private fun MetricLabel(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = FoxNeutral60)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun confidenceColor(confidence: Int): Color = when {
    confidence >= 70 -> FoxBullishText
    confidence >= 50 -> FoxAmber50
    else -> FoxNeutral60
}
