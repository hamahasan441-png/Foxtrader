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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.OptimizationObjective
import com.foxtrader.app.domain.model.tradepro.TradeProOptimizationCandidate
import com.foxtrader.app.domain.model.tradepro.TradeProOptimizationReport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeProOptimizerScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TradeProOptimizerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TRADEPRO Optimizer", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            ConfigCard(state, viewModel)
            RunButton(state, viewModel)

            if (state.isSynthetic && state.hasReport) {
                LabCard {
                    Text(
                        "SIMULATED DATA \u2014 parameters tuned on synthetic bars may not " +
                            "generalise to live. Treat as illustrative.",
                        color = FoxAmber50,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            val report = state.report
            when {
                state.isRunning -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                state.error != null -> LabCard {
                    Text(state.error ?: "Optimization failed.", color = FoxBearishText)
                }

                report != null && report.best != null -> ReportContent(
                    report = report,
                    applied = state.applied,
                    canApply = state.canApplyBest,
                    viewModel = viewModel,
                )

                report != null -> LabCard {
                    Text(report.narrative, color = FoxNeutral60, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfigCard(state: TradeProOptimizerUiState, viewModel: TradeProOptimizerViewModel) {
    LabCard {
        SectionTitle("Configuration")
        Spacer(Modifier.height(10.dp))
        Text("Symbol", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(state.availableSymbols, state.symbol, { it }, viewModel::setSymbol)
        Spacer(Modifier.height(12.dp))
        Text("Timeframe", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1), state.timeframe, { it.label }, viewModel::setTimeframe)
        Spacer(Modifier.height(12.dp))
        Text("Optimise for", fontSize = 12.sp, color = FoxNeutral60)
        ChipRow(OptimizationObjective.entries.toList(), state.objective, { it.label }, viewModel::setObjective)
    }
}

@Composable
private fun RunButton(state: TradeProOptimizerUiState, viewModel: TradeProOptimizerViewModel) {
    Button(
        onClick = viewModel::runOptimization,
        enabled = !state.isRunning,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.padding(4.dp))
        Text("Run Parameter Sweep", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReportContent(
    report: TradeProOptimizationReport,
    applied: Boolean,
    canApply: Boolean,
    viewModel: TradeProOptimizerViewModel,
) {
    NarrativeCard(report)
    RobustnessCard(report)
    BestCandidateCard(report, applied, canApply, viewModel)
    OutOfSampleCard(report)
    CandidatesTable(report)
}

@Composable
private fun NarrativeCard(report: TradeProOptimizationReport) {
    LabCard {
        SectionTitle("Summary")
        Spacer(Modifier.height(8.dp))
        Text(report.narrative, color = FoxNeutral60, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Evaluated", report.evaluated.toString(), FoxAmber50, Modifier.weight(1f))
            MetricTile("In-Sample", "${report.inSampleBars} bars", FoxNeutral60, Modifier.weight(1f))
            MetricTile("Out-of-Sample", "${report.outOfSampleBars} bars", FoxNeutral60, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RobustnessCard(report: TradeProOptimizationReport) {
    val robustness = report.robustness ?: run {
        LabCard {
            SectionTitle("Phase 4 Robustness")
            Spacer(Modifier.height(6.dp))
            Text(
                "Not enough chronological history for anchored walk-forward validation. " +
                    "The optimizer will not auto-apply a tuned config without this check.",
                fontSize = 11.sp,
                color = FoxNeutral60,
            )
        }
        return
    }
    val gradeColor = when (robustness.grade) {
        "A", "B" -> FoxBullishText
        "C" -> FoxAmber50
        else -> FoxBearishText
    }
    LabCard {
        SectionTitle("Phase 4 Walk-Forward Robustness")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Grade", robustness.grade, gradeColor, Modifier.weight(1f))
            MetricTile("Score", "${robustness.robustnessScore.toInt()}/100", gradeColor, Modifier.weight(1f))
            MetricTile(
                "Folds",
                "${robustness.passedFolds}/${robustness.folds.size}",
                if (robustness.passRate >= 0.75) FoxBullishText else FoxAmber50,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Pass Rate", pct(robustness.passRate), FoxAmber50, Modifier.weight(1f))
            MetricTile("Stability", pct(robustness.winnerStability), FoxAmber50, Modifier.weight(1f))
            MetricTile(
                "Avg OOS Exp.",
                fmtPts(robustness.averageValidationExpectancy),
                pnlColor(robustness.averageValidationExpectancy),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (robustness.recommended) {
                "PASS — repeated unseen folds support guarded application of the tuned configuration."
            } else {
                "BLOCKED — parameter sweep did not survive enough unseen chronological folds."
            },
            color = if (robustness.recommended) FoxBullishText else FoxBearishText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BestCandidateCard(
    report: TradeProOptimizationReport,
    applied: Boolean,
    canApply: Boolean,
    viewModel: TradeProOptimizerViewModel,
) {
    val best = report.best ?: return
    LabCard {
        SectionTitle("Best Config (In-Sample)")
        Spacer(Modifier.height(8.dp))
        Text(best.label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Score", fmt(best.score), FoxBullishText, Modifier.weight(1f))
            MetricTile("Trades", best.inSample.totalTrades.toString(), FoxAmber50, Modifier.weight(1f))
            MetricTile("Win Rate", pct(best.inSample.winRate), FoxAmber50, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Net (pts)", fmtPts(best.inSample.netPoints), pnlColor(best.inSample.netPoints), Modifier.weight(1f))
            MetricTile("SQN", fmt(best.inSample.systemQualityNumber), sqnColor(best.inSample.systemQualityNumber), Modifier.weight(1f))
            MetricTile("PF", profitFactor(best.inSample.profitFactor), FoxAmber50, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = viewModel::applyBestConfig,
            enabled = !applied && canApply,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (applied) FoxBullishText else FoxAmber50,
            ),
        ) {
            if (applied) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Applied", fontWeight = FontWeight.Bold)
            } else {
                Text(
                    if (canApply) "Apply Robust Best Config" else "Robustness Gate Required",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun OutOfSampleCard(report: TradeProOptimizationReport) {
    val oos = report.bestOutOfSample ?: return
    LabCard {
        SectionTitle("Out-of-Sample Validation")
        Spacer(Modifier.height(6.dp))
        Text(
            "Re-ran best config on ${report.outOfSampleBars} bars it never saw during optimisation.",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Trades", oos.totalTrades.toString(), FoxAmber50, Modifier.weight(1f))
            MetricTile("Net (pts)", fmtPts(oos.netPoints), pnlColor(oos.netPoints), Modifier.weight(1f))
            MetricTile("SQN", fmt(oos.systemQualityNumber), sqnColor(oos.systemQualityNumber), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Win Rate", pct(oos.winRate), FoxAmber50, Modifier.weight(1f))
            MetricTile("PF", profitFactor(oos.profitFactor), FoxAmber50, Modifier.weight(1f))
            MetricTile("Expectancy", fmtPts(oos.expectancy), pnlColor(oos.expectancy), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val verdict = if (oos.expectancy > 0.0 && oos.systemQualityNumber > 0.0) {
            "Edge held out-of-sample."
        } else {
            "Edge weakened out-of-sample \u2014 possible curve-fit."
        }
        Text(
            verdict,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = if (oos.expectancy > 0.0) FoxBullishText else FoxBearishText,
        )
    }
}

@Composable
private fun CandidatesTable(report: TradeProOptimizationReport) {
    LabCard {
        SectionTitle("All Candidates (ranked)")
        Spacer(Modifier.height(8.dp))
        report.candidates.take(12).forEachIndexed { index, c ->
            CandidateRow(index + 1, c, isBest = index == 0)
        }
        if (report.candidates.size > 12) {
            Text(
                "+${report.candidates.size - 12} more",
                fontSize = 11.sp,
                color = FoxNeutral60,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CandidateRow(rank: Int, candidate: TradeProOptimizationCandidate, isBest: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "#$rank",
                fontSize = 12.sp,
                fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                color = if (isBest) FoxAmber50 else FoxNeutral60,
            )
            Text(
                candidate.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt(candidate.score), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pnlColor(candidate.score))
            Text(
                "${candidate.inSample.totalTrades}t",
                fontSize = 10.sp,
                color = if (candidate.qualified) FoxNeutral60 else FoxBearishText,
            )
        }
    }
}

// --- Shared private composables (same pattern as the report screen) ---

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
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FoxNeutral15),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, fontSize = 11.sp, color = FoxNeutral60)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Text(
                text = label(item),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(if (isSelected) FoxAmber50 else FoxNeutral15)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> FoxNeutral60
}

private fun sqnColor(sqn: Double): Color = when {
    sqn >= 2.0 -> FoxBullishText
    sqn >= 1.0 -> FoxAmber50
    else -> FoxBearishText
}

private fun fmt(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.2f", v) else "\u2014"
private fun fmtPts(v: Double): String = String.format(Locale.US, "%+.1f", v)
private fun pct(v: Double): String = String.format(Locale.US, "%.0f%%", v * 100)
private fun profitFactor(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.2f", v) else "\u221E"
