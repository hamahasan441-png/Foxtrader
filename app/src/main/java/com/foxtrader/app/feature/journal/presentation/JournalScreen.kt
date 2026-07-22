package com.foxtrader.app.feature.journal.presentation

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Trade Journal screen — displays trade history and performance statistics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Journal", fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
            if (!state.hasEntries) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No trades recorded yet",
                            color = FoxNeutral60,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Trades from replay and backtesting will appear here",
                            color = FoxNeutral60.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Stats card at top
                    if (state.showStats) {
                        item { StatsCard(state.stats) }
                    }

                    // Trade entries
                    items(state.entries, key = { it.id }) { entry ->
                        JournalEntryCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(stats: JournalStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Performance",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = FoxAmber50,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatItem("Trades", stats.totalTrades.toString())
                StatItem("Win Rate", "%.1f%%".format(stats.winRate))
                StatItem("Avg R", "%.2f".format(stats.averageRMultiple))
                StatItem("P/F", "%.2f".format(stats.profitFactor))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatItem("Total P&L", "%.2f".format(stats.totalPnl))
                StatItem("Best", "%.2f".format(stats.bestTrade))
                StatItem("Worst", "%.2f".format(stats.worstTrade))
                StatItem("Streak W/L", "${stats.consecutiveWins}/${stats.consecutiveLosses}")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = FoxNeutral60,
        )
    }
}

@Composable
private fun JournalEntryCard(entry: JournalEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Row 1: Symbol + Direction + P&L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.symbol,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    val dirColor = if (entry.direction == Direction.BULLISH) FoxBullishText else FoxBearishText
                    val dirLabel = if (entry.direction == Direction.BULLISH) "LONG" else "SHORT"
                    Text(
                        dirLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = dirColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(dirColor.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }

                // P&L
                if (entry.pnl != null) {
                    val pnlColor = if (entry.pnl > 0) FoxBullishText else FoxBearishText
                    Text(
                        text = "%+.2f".format(entry.pnl),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = pnlColor,
                    )
                } else {
                    Text("OPEN", fontSize = 11.sp, color = FoxAmber50, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Row 2: Setup + R-Multiple + Timeframe
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(entry.setupType, fontSize = 11.sp, color = FoxNeutral60)
                if (entry.rMultiple != null) {
                    Text(
                        "%+.1fR".format(entry.rMultiple),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.rMultiple > 0) FoxBullishText else FoxBearishText,
                    )
                }
                Text(entry.timeframe.label, fontSize = 11.sp, color = FoxNeutral60)
            }

            // Notes (if any)
            if (entry.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.notes,
                    fontSize = 11.sp,
                    color = FoxNeutral60.copy(alpha = 0.8f),
                    maxLines = 2,
                )
            }
        }
    }
}
