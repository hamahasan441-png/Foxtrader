package com.foxtrader.app.feature.journal.presentation

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onNavigateBack: () -> Unit,
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            val snapshot = viewModel.csvSnapshot()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                            stream.write(snapshot.toByteArray(Charsets.UTF_8))
                            stream.flush()
                        } ?: error("Could not open export destination")
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "Journal CSV exported" else "Journal export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = { FoxScreenTopBar(title = "Professional Journal", onNavigateBack = onNavigateBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { JournalStatsCard(state.stats) }
            item {
                Button(
                    onClick = { createCsv.launch("foxtrader-journal-${System.currentTimeMillis()}.csv") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export journal CSV") }
            }
            if (state.entries.isEmpty()) {
                item { Text("No journal entries yet.", color = FoxNeutral60) }
            } else {
                items(state.entries, key = { it.id }) { JournalEntryCard(it) }
            }
        }
    }
}

@Composable
private fun JournalStatsCard(stats: JournalStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FoxAmber50)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Closed", stats.totalTrades.toString())
                Metric("Win rate", fmtPct(stats.winRate))
                Metric("Expectancy", fmt(stats.expectancy))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Net P/L", fmt(stats.totalPnl))
                Metric("Profit factor", ratio(stats.profitFactor))
                Metric("Max DD", fmt(stats.maxDrawdown))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Avg R", fmt(stats.averageRMultiple))
                Metric("Best streak", stats.consecutiveWins.toString())
                Metric("Worst streak", stats.consecutiveLosses.toString())
            }
            stats.bestSetupByAverageR?.let { Text("Best setup by average R: $it", style = MaterialTheme.typography.bodySmall, color = FoxNeutral60) }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelSmall, color = FoxNeutral60); Text(value, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun JournalEntryCard(entry: JournalEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${entry.symbol} · ${entry.direction.name}", fontWeight = FontWeight.Bold)
                Text(if (entry.isOpen) "OPEN" else entry.pnl?.let(::fmt) ?: "CLOSED", color = if (entry.isOpen || (entry.pnl ?: 0.0) >= 0.0) FoxSuccess else FoxBearishText)
            }
            Text("${entry.setupType} · ${entry.timeframe.name}", style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
            Text("Entry ${fmt(entry.entryPrice)} · SL ${fmt(entry.stopLoss)} · TP ${fmt(entry.takeProfit)} · vol ${fmt(entry.volume)}", style = MaterialTheme.typography.bodySmall)
            entry.exitPrice?.let { Text("Exit ${fmt(it)} · ${entry.exitTime?.let(::instant).orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
        }
    }
}

private fun fmt(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.4f", v) else "—"
private fun fmtPct(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.1f%%", v) else "—"
private fun ratio(v: Double): String = when { v.isInfinite() -> "∞"; v.isFinite() -> String.format(Locale.US, "%.2f", v); else -> "—" }
private fun instant(epoch: Long): String = runCatching { Instant.ofEpochMilli(epoch).toString() }.getOrDefault("")
