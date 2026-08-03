package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.OpportunityGrade
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeOpportunity
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpportunityBoardScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: OpportunityBoardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Opportunity Board", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::scan, enabled = !state.isScanning) {
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
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            TimeframeRow(state, viewModel)
            SummaryCard(state)
            FilterRow(state, viewModel)

            when {
                state.isScanning -> Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                state.error != null -> LabCard {
                    Text(state.error ?: "Scan failed.", color = FoxBearishText, fontSize = 13.sp)
                }

                !state.hasResults -> LabCard {
                    Text(
                        "No qualifying setups on the watchlist right now. Standing aside is a position.",
                        color = FoxNeutral60,
                        fontSize = 13.sp,
                    )
                }

                else -> {
                    val filtered = applyFilter(state.board.opportunities, state.filter)
                    filtered.forEach { OpportunityCard(it) }
                    if (filtered.isEmpty()) {
                        LabCard {
                            Text("Nothing matches this filter.", color = FoxNeutral60, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimeframeRow(state: OpportunityBoardUiState, viewModel: OpportunityBoardViewModel) {
    LabCard {
        Text("Timeframe", fontSize = 12.sp, color = FoxNeutral60)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { tf ->
                Chip(tf.label, state.timeframe == tf) { viewModel.setTimeframe(tf) }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: OpportunityBoardUiState) {
    val board = state.board
    LabCard {
        SectionTitle("Watchlist Scan")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Scanned", board.scannedSymbols.toString(), FoxNeutral60, Modifier.weight(1f))
            StatTile("Actionable", board.actionableCount.toString(), FoxBullishText, Modifier.weight(1f))
            StatTile("Watch", board.watchCount.toString(), FoxAmber50, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Bullish", board.bullishCount.toString(), FoxBullishText, Modifier.weight(1f))
            StatTile("Bearish", board.bearishCount.toString(), FoxBearishText, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(board.narrative, fontSize = 11.sp, color = FoxNeutral60)
    }
}

@Composable
private fun FilterRow(state: OpportunityBoardUiState, viewModel: OpportunityBoardViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpportunityFilter.entries.forEach { filter ->
            Chip(filter.label, state.filter == filter) { viewModel.setFilter(filter) }
        }
    }
}

@Composable
private fun OpportunityCard(opportunity: TradeOpportunity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradeBadge(opportunity.grade, opportunity.hasData)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        opportunity.symbol,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (opportunity.hasData) {
                        BiasPill(opportunity.bias, opportunity.direction)
                        if (opportunity.htfAligned) {
                            Text(
                                "HTF",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = FoxBullishText,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(FoxBullishText.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(opportunity.headline, fontSize = 11.sp, color = FoxNeutral60)
                if (opportunity.hasData && opportunity.stage != SetupStage.NONE) {
                    Spacer(Modifier.height(6.dp))
                    StageProgress(opportunity.stage)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (opportunity.hasData) opportunity.readinessScore.toString() else "--",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor(opportunity.readinessScore),
                )
                Text("score", fontSize = 9.sp, color = FoxNeutral60)
                if (opportunity.hasData && opportunity.confidence > 0) {
                    Text("${opportunity.confidence}% conf", fontSize = 10.sp, color = FoxNeutral60)
                }
                if (opportunity.riskReward > 0.0) {
                    Text("RR ${fmt(opportunity.riskReward)}", fontSize = 10.sp, color = FoxAmber50)
                }
            }
        }
    }
}

@Composable
private fun GradeBadge(grade: OpportunityGrade, hasData: Boolean) {
    val color = gradeColor(grade)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hasData) color.copy(alpha = 0.18f) else FoxNeutral15),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (hasData) grade.label else "--",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (hasData) color else FoxNeutral60,
        )
    }
}

@Composable
private fun BiasPill(bias: Bias, direction: Direction?) {
    val (label, color) = when (bias) {
        Bias.BULLISH -> "LONG" to FoxBullishText
        Bias.BEARISH -> "SHORT" to FoxBearishText
        Bias.NEUTRAL -> "FLAT" to FoxNeutral60
    }
    val text = when (direction) {
        Direction.BULLISH -> "LONG"
        Direction.BEARISH -> "SHORT"
        null -> label
    }
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StageProgress(stage: SetupStage) {
    val stages = listOf(SetupStage.LEVEL, SetupStage.ZONE, SetupStage.CONFIRMATION, SetupStage.EXECUTE)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        stages.forEach { s ->
            val reached = stage.ordinal >= s.ordinal
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (reached) stageColor(stage) else FoxNeutral15),
            )
        }
    }
}

// --- Shared private composables ---

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (selected) FoxAmber50 else FoxNeutral15)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FoxNeutral15),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 10.sp, color = FoxNeutral60)
        }
    }
}

// --- Helpers ---

private fun applyFilter(
    opportunities: List<TradeOpportunity>,
    filter: OpportunityFilter,
): List<TradeOpportunity> = when (filter) {
    OpportunityFilter.ALL -> opportunities.filter { it.hasData }
    OpportunityFilter.ACTIONABLE -> opportunities.filter { it.hasData && it.isActionable }
    OpportunityFilter.WATCH -> opportunities.filter { it.hasData && it.isWatch }
    OpportunityFilter.BULLISH -> opportunities.filter { it.hasData && it.bias == Bias.BULLISH }
    OpportunityFilter.BEARISH -> opportunities.filter { it.hasData && it.bias == Bias.BEARISH }
}

private fun scoreColor(score: Int): Color = when {
    score >= 70 -> FoxBullishText
    score >= 40 -> FoxAmber50
    else -> FoxNeutral60
}

private fun gradeColor(grade: OpportunityGrade): Color = when (grade) {
    OpportunityGrade.A_PLUS, OpportunityGrade.A -> FoxBullishText
    OpportunityGrade.B, OpportunityGrade.C -> FoxAmber50
    OpportunityGrade.WATCH -> FoxNeutral60
    OpportunityGrade.NONE -> FoxNeutral60
}

private fun stageColor(stage: SetupStage): Color = when (stage) {
    SetupStage.EXECUTE -> FoxBullishText
    SetupStage.CONFIRMATION, SetupStage.ZONE -> FoxAmber50
    else -> FoxNeutral60
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
