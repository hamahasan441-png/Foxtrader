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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.DailyPlan
import com.foxtrader.app.domain.model.tradepro.DeviationSeverity
import com.foxtrader.app.domain.model.tradepro.FocusItem
import com.foxtrader.app.domain.model.tradepro.PlanDeviation
import com.foxtrader.app.domain.model.tradepro.RiskPosture
import com.foxtrader.app.domain.model.tradepro.SessionReview
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyPlanScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: DailyPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Plan", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.hasPlan) {
                        IconButton(onClick = viewModel::refreshReview) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh review", tint = FoxAmber50)
                        }
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
            GenerateButton(state, viewModel)

            when {
                state.isGenerating -> Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                state.error != null -> LabCard {
                    Text(state.error ?: "Failed.", color = FoxBearishText, fontSize = 13.sp)
                }

                state.hasPlan -> {
                    val plan = state.plan
                    if (plan != null) {
                        PostureCard(plan)
                        BudgetCard(plan)
                        FocusCard(plan.focus)
                        RulesCard(plan.rules)
                        state.review?.let { ReviewCard(it) }
                    }
                }

                else -> LabCard {
                    SectionTitle("Pre-Market Plan")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Generate a plan to set your risk posture, daily budget, trade cap and focus " +
                            "list before the session \u2014 then trade the plan, not your emotions.",
                        color = FoxNeutral60,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimeframeRow(state: DailyPlanUiState, viewModel: DailyPlanViewModel) {
    LabCard {
        Text("Execution timeframe", fontSize = 12.sp, color = FoxNeutral60)
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
private fun GenerateButton(state: DailyPlanUiState, viewModel: DailyPlanViewModel) {
    Button(
        onClick = viewModel::generatePlan,
        enabled = !state.isGenerating,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
    ) {
        Text(if (state.hasPlan) "Regenerate Plan" else "Generate Today's Plan", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PostureCard(plan: DailyPlan) {
    val color = postureColor(plan.posture)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("TODAY'S POSTURE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FoxNeutral60)
            Spacer(Modifier.height(4.dp))
            Text(plan.posture.label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(4.dp))
            Text(plan.marketRegime.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(plan.headline, fontSize = 11.sp, color = FoxNeutral60)
        }
    }
}

@Composable
private fun BudgetCard(plan: DailyPlan) {
    LabCard {
        SectionTitle("Risk Budget")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Daily Loss Cap", "${fmt(plan.dailyRiskBudgetPoints)} pts", FoxBearishText, Modifier.weight(1f))
            StatTile("Max Trades", plan.maxTrades.toString(), FoxAmber50, Modifier.weight(1f))
            StatTile("Risk / Trade", "${fmt(plan.riskPerTradePoints)} pts", FoxNeutral60, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FocusCard(focus: List<FocusItem>) {
    LabCard {
        SectionTitle("Focus List")
        Spacer(Modifier.height(8.dp))
        if (focus.isEmpty()) {
            Text("No qualifying symbols today. Patience is a position.", color = FoxNeutral60, fontSize = 12.sp)
        } else {
            focus.forEachIndexed { index, item ->
                FocusRow(item)
                if (index < focus.size - 1) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FocusRow(item: FocusItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(biasColor(item.bias)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.symbol, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    biasLabel(item.bias),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = biasColor(item.bias),
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(biasColor(item.bias).copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(item.note, fontSize = 11.sp, color = FoxNeutral60)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("@ ${fmtPrice(item.keyLevel)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = FoxAmber50)
            Text("${item.readinessScore} ready", fontSize = 10.sp, color = FoxNeutral60)
        }
    }
}

@Composable
private fun RulesCard(rules: List<String>) {
    LabCard {
        SectionTitle("Rules of Engagement")
        Spacer(Modifier.height(8.dp))
        rules.forEach { rule ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("\u2022", fontSize = 13.sp, color = FoxAmber50)
                Spacer(Modifier.width(8.dp))
                Text(rule, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReviewCard(review: SessionReview) {
    LabCard {
        SectionTitle("Session Review")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Adherence", "${review.adherenceScore}%", adherenceColor(review.adherenceScore), Modifier.weight(1f))
            StatTile("Trades", "${review.tradesTaken}/${review.plannedMaxTrades}", FoxNeutral60, Modifier.weight(1f))
            StatTile("Net", fmtSigned(review.netPoints), pnlColor(review.netPoints), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            review.summary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (review.followedPlan) FoxBullishText else FoxAmber50,
        )
        if (review.commendations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            review.commendations.forEach {
                Text("\u2713 $it", fontSize = 11.sp, color = FoxBullishText, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
        if (review.deviations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            review.deviations.forEach { DeviationRow(it) }
        }
    }
}

@Composable
private fun DeviationRow(deviation: PlanDeviation) {
    val color = severityColor(deviation.severity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(deviation.rule, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
            Text(deviation.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
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
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 10.sp, color = FoxNeutral60)
        }
    }
}

// --- Helpers ---

private fun postureColor(posture: RiskPosture): Color = when (posture) {
    RiskPosture.DEFENSIVE -> FoxBearishText
    RiskPosture.CAUTIOUS -> FoxAmber50
    RiskPosture.NORMAL -> FoxBullishText
    RiskPosture.AGGRESSIVE -> FoxBullishText
}

private fun biasColor(bias: Bias): Color = when (bias) {
    Bias.BULLISH -> FoxBullishText
    Bias.BEARISH -> FoxBearishText
    Bias.NEUTRAL -> FoxNeutral60
}

private fun biasLabel(bias: Bias): String = when (bias) {
    Bias.BULLISH -> "LONG"
    Bias.BEARISH -> "SHORT"
    Bias.NEUTRAL -> "FLAT"
}

private fun adherenceColor(score: Int): Color = when {
    score >= 80 -> FoxBullishText
    score >= 60 -> FoxAmber50
    else -> FoxBearishText
}

private fun severityColor(severity: DeviationSeverity): Color = when (severity) {
    DeviationSeverity.SEVERE -> FoxBearishText
    DeviationSeverity.MODERATE -> FoxAmber50
    DeviationSeverity.MINOR -> FoxNeutral60
}

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> FoxNeutral60
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmtSigned(v: Double): String = String.format(Locale.US, "%+.1f", v)
private fun fmtPrice(v: Double): String = String.format(Locale.US, "%.4f", v)
