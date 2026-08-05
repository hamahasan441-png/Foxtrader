package com.foxtrader.app.feature.litx.presentation

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXSignalRecord
import com.foxtrader.app.domain.model.LitXStage
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.litx.presentation.components.GradeBadge
import com.foxtrader.app.feature.litx.presentation.components.LitXConfidenceBars
import com.foxtrader.app.feature.litx.presentation.components.LitXSignalCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxWarning

/**
 * LIT X Institutional Framework analysis screen. Reached from the Chart top bar
 * for the currently-charted symbol/timeframe (additive; not a bottom tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LitXScreen(
    symbol: String,
    timeframe: Timeframe,
    onNavigateBack: () -> Unit = {},
    viewModel: LitXViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recentSignals by viewModel.recentSignals.collectAsStateWithLifecycle()

    LaunchedEffect(symbol, timeframe) {
        viewModel.analyze(symbol, timeframe)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "LIT X · ${state.symbol} ${state.timeframe.label}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            when {
                state.isLoading -> LoadingBlock()
                state.error != null -> InfoCard(state.error ?: "")
                state.analysis != null -> AnalysisContent(state)
                else -> InfoCard("Open a symbol to run LIT X analysis.")
            }

            if (recentSignals.isNotEmpty()) {
                Text(
                    "Recent signals",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = FoxAmber50,
                )
                recentSignals.forEach { record -> RecentSignalRow(record) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AnalysisContent(state: LitXUiState) {
    val analysis = state.analysis ?: return

    if (state.isSynthetic) {
        InfoCard("Simulated data — LIT X output is illustrative only.", tint = FoxWarning)
    }

    // Pipeline progress
    StageStrip(analysis.stage)

    // Bias summary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BiasChip("Bias", analysis.bias)
        BiasChip("HTF", analysis.htfBias)
    }

    val signal = analysis.signal
    if (signal != null) {
        LitXSignalCard(signal)
        Text(
            "Confidence breakdown",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
        )
        LitXConfidenceBars(signal.confidence)
    } else {
        InfoCard(analysis.narrative)
    }
}

@Composable
private fun StageStrip(current: LitXStage) {
    val stages = LitXStage.entries
    val currentIndex = stages.indexOf(current)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Pipeline",
            style = MaterialTheme.typography.labelMedium,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            stages.forEachIndexed { i, _ ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= currentIndex) FoxAmber50 else FoxNeutral20),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            current.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BiasChip(label: String, bias: Bias) {
    val color = when (bias) {
        Bias.BULLISH -> FoxBullishText
        Bias.BEARISH -> FoxBearishText
        Bias.NEUTRAL -> FoxNeutral60
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FoxNeutral10)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
        Text(
            bias.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = FoxAmber50)
    }
}

@Composable
private fun RecentSignalRow(record: LitXSignalRecord) {
    val bullish = record.direction == Direction.BULLISH
    val dirColor = if (bullish) FoxBullishText else FoxBearishText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral10)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${record.symbol} ${record.timeframe.label}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                GradeBadge(record.grade)
            }
            Text(
                text = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(record.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = FoxNeutral60,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (bullish) "LONG" else "SHORT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = dirColor,
            )
            Text(
                "${record.score}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )
            Text(
                "RR ${String.format(Locale.US, "%.1f", record.riskReward)}",
                style = MaterialTheme.typography.labelSmall,
                color = FoxNeutral60,
            )
        }
    }
}

@Composable
private fun InfoCard(text: String, tint: androidx.compose.ui.graphics.Color = FoxNeutral60) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FoxNeutral10)
            .padding(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}
