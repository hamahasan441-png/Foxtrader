package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.tradepro.RiskSimulationResult
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiskSimulatorScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: RiskSimulatorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Risk Simulator", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            InputsCard(state, viewModel)
            RunButton(state, viewModel)

            val result = state.result
            when {
                state.isRunning -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                result != null && result.runsSimulated > 0 -> ResultContent(result)

                else -> LabCard {
                    Text(
                        "Set your edge and risk, then run the simulation to see the distribution of " +
                            "outcomes and your risk of ruin.",
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
private fun InputsCard(state: RiskSimulatorUiState, viewModel: RiskSimulatorViewModel) {
    val input = state.input
    LabCard {
        SectionTitle("Your Edge")
        Spacer(Modifier.height(10.dp))
        Stepper("Win rate", pct(input.winRate), { viewModel.updateInput { i -> i.copy(winRate = (i.winRate - 0.05).coerceIn(0.0, 1.0)) } }) {
            viewModel.updateInput { i -> i.copy(winRate = (i.winRate + 0.05).coerceIn(0.0, 1.0)) }
        }
        Stepper("Avg win (R)", ratio(input.avgWinR), { viewModel.updateInput { i -> i.copy(avgWinR = (i.avgWinR - 0.25).coerceIn(0.0, 20.0)) } }) {
            viewModel.updateInput { i -> i.copy(avgWinR = (i.avgWinR + 0.25).coerceIn(0.0, 20.0)) }
        }
        Stepper("Avg loss (R)", ratio(input.avgLossR), { viewModel.updateInput { i -> i.copy(avgLossR = (i.avgLossR - 0.25).coerceIn(0.0, 20.0)) } }) {
            viewModel.updateInput { i -> i.copy(avgLossR = (i.avgLossR + 0.25).coerceIn(0.0, 20.0)) }
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Risk & Horizon")
        Spacer(Modifier.height(10.dp))
        Stepper("Risk / trade", pct(input.riskPerTradeFraction), { viewModel.updateInput { i -> i.copy(riskPerTradeFraction = (i.riskPerTradeFraction - 0.0025).coerceIn(0.0, 1.0)) } }) {
            viewModel.updateInput { i -> i.copy(riskPerTradeFraction = (i.riskPerTradeFraction + 0.0025).coerceIn(0.0, 1.0)) }
        }
        Stepper("Trades / run", input.tradesPerRun.toString(), { viewModel.updateInput { i -> i.copy(tradesPerRun = (i.tradesPerRun - 25).coerceIn(1, 2000)) } }) {
            viewModel.updateInput { i -> i.copy(tradesPerRun = (i.tradesPerRun + 25).coerceIn(1, 2000)) }
        }
        Stepper("Ruin at", pct(input.ruinThresholdFraction), { viewModel.updateInput { i -> i.copy(ruinThresholdFraction = (i.ruinThresholdFraction - 0.05).coerceIn(0.0, 1.0)) } }) {
            viewModel.updateInput { i -> i.copy(ruinThresholdFraction = (i.ruinThresholdFraction + 0.05).coerceIn(0.0, 1.0)) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Expectancy: ${ratioSigned(input.expectancyR)}R per trade over ${input.runs} simulated runs.",
            fontSize = 11.sp,
            color = if (input.expectancyR > 0) FoxBullishText else FoxBearishText,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RunButton(state: RiskSimulatorUiState, viewModel: RiskSimulatorViewModel) {
    Button(
        onClick = viewModel::run,
        enabled = !state.isRunning && state.input.isValid,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Run ${state.input.runs} Simulations", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultContent(result: RiskSimulationResult) {
    RiskOfRuinCard(result)
    OutcomeBandsCard(result)
    EquityCurvesCard(result)
    DrawdownCard(result)
}

@Composable
private fun RiskOfRuinCard(result: RiskSimulationResult) {
    val color = ruinColor(result.riskOfRuinFraction)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("RISK OF RUIN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FoxNeutral60)
            Spacer(Modifier.height(4.dp))
            Text(pct1(result.riskOfRuinFraction), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(6.dp))
            RuinBar(result.riskOfRuinFraction, color)
            Spacer(Modifier.height(10.dp))
            Text(result.narrative, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun RuinBar(fraction: Double, color: Color) {
    Box(
        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(FoxNeutral15),
    ) {
        val f = fraction.toFloat().coerceIn(0f, 1f)
        if (f > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color),
            )
        }
    }
}

@Composable
private fun OutcomeBandsCard(result: RiskSimulationResult) {
    LabCard {
        SectionTitle("Ending Equity Distribution")
        Spacer(Modifier.height(6.dp))
        Text("Final account value as a multiple of the start, across all runs.", fontSize = 11.sp, color = FoxNeutral60)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Worst 5%", mult(result.p5EndMultiple), pnlColor(result.p5EndMultiple - 1.0), Modifier.weight(1f))
            Tile("Median", mult(result.medianEndMultiple), pnlColor(result.medianEndMultiple - 1.0), Modifier.weight(1f))
            Tile("Best 5%", mult(result.p95EndMultiple), pnlColor(result.p95EndMultiple - 1.0), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("P25", mult(result.p25EndMultiple), FoxNeutral60, Modifier.weight(1f))
            Tile("Mean", mult(result.meanEndMultiple), FoxNeutral60, Modifier.weight(1f))
            Tile("P75", mult(result.p75EndMultiple), FoxNeutral60, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Tile("Profitable runs", pct1(result.profitableRunFraction), FoxAmber50, Modifier.fillMaxWidth())
    }
}

@Composable
private fun EquityCurvesCard(result: RiskSimulationResult) {
    val curves = result.sampleEquityCurves
    if (curves.isEmpty()) return
    LabCard {
        SectionTitle("Sample Equity Paths")
        Spacer(Modifier.height(10.dp))
        val maxVal = curves.maxOf { c -> c.maxOrNull() ?: 1.0 }.coerceAtLeast(0.001)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(FoxNeutral15)
                .padding(8.dp),
        ) {
            // Baseline at equity multiple 1.0.
            val baseY = size.height - (1.0 / maxVal).toFloat() * size.height
            drawLine(
                color = FoxNeutral60.copy(alpha = 0.5f),
                start = Offset(0f, baseY),
                end = Offset(size.width, baseY),
                strokeWidth = 1f,
            )
            curves.forEach { curve ->
                if (curve.size >= 2) {
                    val lastIndex = curve.size - 1
                    val up = (curve.lastOrNull() ?: 1.0) >= 1.0
                    val lineColor = (if (up) FoxBullishText else FoxBearishText).copy(alpha = 0.5f)
                    for (i in 1 until curve.size) {
                        val x0 = (i - 1).toFloat() / lastIndex * size.width
                        val x1 = i.toFloat() / lastIndex * size.width
                        val y0 = size.height - (curve[i - 1] / maxVal).toFloat() * size.height
                        val y1 = size.height - (curve[i] / maxVal).toFloat() * size.height
                        drawLine(lineColor, Offset(x0, y0), Offset(x1, y1), strokeWidth = 1.5f, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawdownCard(result: RiskSimulationResult) {
    LabCard {
        SectionTitle("Drawdown Risk")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Median Max DD", pct1(result.medianMaxDrawdownFraction), FoxAmber50, Modifier.weight(1f))
            Tile("Worst 5% DD", pct1(result.p95MaxDrawdownFraction), FoxBearishText, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Half your runs draw down at least ${pct1(result.medianMaxDrawdownFraction)}; the roughest " +
                "5% hit ${pct1(result.p95MaxDrawdownFraction)}. Can you stomach that and stick to the plan?",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
    }
}

// --- Shared private composables ---

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        StepBtn("\u2212", onMinus)
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        StepBtn("+", onPlus)
    }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .width(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FoxNeutral15)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
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
private fun Tile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
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

private fun ruinColor(fraction: Double): Color = when {
    fraction <= 0.01 -> FoxBullishText
    fraction <= 0.05 -> FoxAmber50
    else -> FoxBearishText
}

private fun pnlColor(delta: Double): Color = when {
    delta > 0.0 -> FoxBullishText
    delta < 0.0 -> FoxBearishText
    else -> FoxNeutral60
}

private fun pct(fraction: Double): String = String.format(Locale.US, "%.1f%%", fraction * 100)
private fun pct1(fraction: Double): String = String.format(Locale.US, "%.1f%%", fraction * 100)
private fun ratio(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun ratioSigned(v: Double): String = String.format(Locale.US, "%+.2f", v)
private fun mult(v: Double): String = String.format(Locale.US, "%.2fx", v)
