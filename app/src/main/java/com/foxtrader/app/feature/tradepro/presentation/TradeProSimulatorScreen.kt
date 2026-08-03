package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.ComplianceViolation
import com.foxtrader.app.domain.model.tradepro.SimulatedTrade
import com.foxtrader.app.domain.model.tradepro.SimulatedTradeState
import com.foxtrader.app.domain.model.tradepro.SimulationPerformance
import com.foxtrader.app.domain.model.tradepro.SimulationSpeed
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.ViolationSeverity
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeProSimulatorScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TradeProSimulatorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Trade Simulator",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    PlaybackControls(state, viewModel)
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

            if (!state.hasSession) {
                SessionSetupCard(state, viewModel)
            } else {
                SessionInfoHeader(state)
                SimulationProgressBar(state)
                SessionCompleteCard(state, viewModel)
                LivePriceCard(state)
                AnalysisSummaryCard(state)
                TradeActionButtons(state, viewModel)
                OpenTradeCard(state)
                ViolationBanner(state.lastViolation)
                DrawdownCard(state)
                PerformanceSummaryCard(state.performance)
                EquityCurveCard(state.performance.equityCurve)
                ClosedTradesCard(state)
                SpeedSelectorCard(state, viewModel)
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }
            }

            state.error?.let { error ->
                LabCard {
                    Text(error, color = FoxBearishText, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// --- Playback controls in the top bar ---

@Composable
private fun PlaybackControls(state: TradeProSimulatorUiState, viewModel: TradeProSimulatorViewModel) {
    if (!state.hasSession) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = viewModel::stepForward,
            enabled = state.session?.isComplete == false && !state.isPlaying,
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Step",
                tint = if (state.session?.isComplete == false && !state.isPlaying) FoxAmber50 else FoxNeutral60,
            )
        }
        IconButton(onClick = viewModel::togglePlayback, enabled = state.session?.isComplete == false) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = if (state.session?.isComplete == false) FoxAmber50 else FoxNeutral60,
            )
        }
        Text(
            text = state.speed.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

// --- Session setup when no session exists ---

@Composable
private fun SessionSetupCard(state: TradeProSimulatorUiState, viewModel: TradeProSimulatorViewModel) {
    LabCard {
        SectionTitle("Start a Practice Session")
        Spacer(Modifier.height(12.dp))
        Text("Symbol", fontSize = 12.sp, color = FoxNeutral60)
        Spacer(Modifier.height(4.dp))
        ChipRow(state.availableSymbols.toList(), state.symbol, { it }, viewModel::setSymbol)
        Spacer(Modifier.height(14.dp))
        Text("Timeframe", fontSize = 12.sp, color = FoxNeutral60)
        Spacer(Modifier.height(4.dp))
        ChipRow(
            listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1),
            state.timeframe,
            { it.label },
            viewModel::setTimeframe,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = viewModel::startSession,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Start Simulation", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Walk through historical bars one-by-one. Practice zone entries, " +
                "T1/T2/runner management, and stop discipline without real capital.",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
    }
}

// --- Simulation progress bar ---

@Composable
private fun SimulationProgressBar(state: TradeProSimulatorUiState) {
    val session = state.session ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Bar ${session.currentBarIndex} / ${session.totalBars}",
                fontSize = 11.sp,
                color = FoxNeutral60,
            )
            Text(
                if (session.isComplete) "COMPLETE" else "${(session.progress * 100).toInt()}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (session.isComplete) FoxBullishText else FoxAmber50,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { session.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = FoxAmber50,
            trackColor = FoxNeutral15,
        )
        if (state.isSynthetic) {
            Spacer(Modifier.height(4.dp))
            Text(
                "SIMULATED DATA - results are illustrative only.",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = FoxAmber50,
            )
        }
    }
}

// --- Live price and unrealized P&L ---

@Composable
private fun LivePriceCard(state: TradeProSimulatorUiState) {
    val session = state.session ?: return
    LabCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Live Price", fontSize = 11.sp, color = FoxNeutral60)
                Spacer(Modifier.height(2.dp))
                Text(
                    fmtPrice(session.currentPrice),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            session.openTrade?.let { trade ->
                Column(horizontalAlignment = Alignment.End) {
                    Text("Unrealized P&L", fontSize = 11.sp, color = FoxNeutral60)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        fmtPts(trade.unrealizedPoints),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor(trade.unrealizedPoints),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Equity", fmtPrice(session.equity), FoxAmber50, Modifier.weight(1f))
            MetricTile(
                "Drawdown",
                pct(session.drawdown),
                if (session.drawdown > 0.05) FoxBearishText else FoxNeutral60,
                Modifier.weight(1f),
            )
            MetricTile(
                "Bars Since Entry",
                session.barsSinceEntry.toString(),
                FoxNeutral60,
                Modifier.weight(1f),
            )
        }
    }
}

// --- TRADEPRO Analysis Summary ---

@Composable
private fun AnalysisSummaryCard(state: TradeProSimulatorUiState) {
    val analysis = state.session?.analysis ?: return
    LabCard {
        SectionTitle("TRADEPRO Analysis")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                "Stage",
                stageLabel(analysis.stage),
                stageColor(analysis.stage),
                Modifier.weight(1f),
            )
            MetricTile(
                "Flip Zone Bias",
                analysis.flipZone?.bias?.name ?: "N/A",
                when (analysis.flipZone?.bias?.name) {
                    "BULLISH" -> FoxBullishText
                    "BEARISH" -> FoxBearishText
                    else -> FoxNeutral60
                },
                Modifier.weight(1f),
            )
            MetricTile(
                "Confidence",
                analysis.setup?.confidence?.let { "$it%" } ?: "--",
                confidenceColor(analysis.setup?.confidence),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            analysis.narrative,
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
        analysis.setup?.let { setup ->
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Entry", fmtPrice(setup.entry), FoxAmber50, Modifier.weight(1f))
                MetricTile("Stop", fmtPrice(setup.stopLoss), FoxBearishText, Modifier.weight(1f))
                MetricTile("R:R", fmt(setup.riskReward), FoxBullishText, Modifier.weight(1f))
            }
        }
    }
}

// --- Trade Action Buttons ---

@Composable
private fun TradeActionButtons(state: TradeProSimulatorUiState, viewModel: TradeProSimulatorViewModel) {
    LabCard {
        SectionTitle("Trade Actions")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.placeTrade(Direction.BULLISH) },
                enabled = state.canTrade,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FoxBullishText,
                    disabledContainerColor = FoxBullishText.copy(alpha = 0.3f),
                ),
            ) {
                Text("BUY", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Button(
                onClick = { viewModel.placeTrade(Direction.BEARISH) },
                enabled = state.canTrade,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FoxBearishText,
                    disabledContainerColor = FoxBearishText.copy(alpha = 0.3f),
                ),
            ) {
                Text("SELL", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::closeManually,
                enabled = state.canManage,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FoxAmber50,
                    disabledContainerColor = FoxAmber50.copy(alpha = 0.3f),
                ),
            ) {
                Text("CLOSE", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Button(
                onClick = viewModel::moveStopToBreakeven,
                enabled = state.canManage,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FoxNeutral15,
                    disabledContainerColor = FoxNeutral15.copy(alpha = 0.3f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContentColor = FoxNeutral60,
                ),
            ) {
                Text("Move to BE", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        if (state.canTrade) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No open position. Place a trade when the setup is ready.",
                fontSize = 11.sp,
                color = FoxNeutral60,
            )
        }
    }
}

// --- Open trade status card ---

@Composable
private fun OpenTradeCard(state: TradeProSimulatorUiState) {
    val trade = state.session?.openTrade ?: return
    LabCard {
        SectionTitle("Open Position")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    trade.direction.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (trade.direction == Direction.BULLISH) FoxBullishText else FoxBearishText,
                )
                Text(
                    tradeStateLabel(trade.state),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FoxAmber50,
                )
            }
            Text(
                fmtPts(trade.unrealizedPoints),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = pnlColor(trade.unrealizedPoints),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Entry", fmtPrice(trade.entryPrice), FoxAmber50, Modifier.weight(1f))
            MetricTile("Stop", fmtPrice(trade.stopLoss), FoxBearishText, Modifier.weight(1f))
            MetricTile("Current", fmtPrice(trade.currentPrice), FoxNeutral60, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("T1", fmtPrice(trade.target1), FoxBullishText, Modifier.weight(1f))
            MetricTile("T2", fmtPrice(trade.target2), FoxBullishText, Modifier.weight(1f))
            MetricTile("Runner", fmtPrice(trade.runnerTarget), FoxBullishText, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TargetChip("T1", trade.t1Hit)
            TargetChip("T2", trade.t2Hit)
            TargetChip("Runner", trade.runnerHit)
        }
        if (trade.realizedPoints != 0.0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Realized: ${fmtPts(trade.realizedPoints)} pts",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = pnlColor(trade.realizedPoints),
            )
        }
    }
}

@Composable
private fun TargetChip(label: String, hit: Boolean) {
    Text(
        text = if (hit) "$label \u2713" else label,
        fontSize = 11.sp,
        fontWeight = if (hit) FontWeight.Bold else FontWeight.Normal,
        color = if (hit) FoxBullishText else FoxNeutral60,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (hit) FoxBullishText.copy(alpha = 0.15f) else FoxNeutral15)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// --- Compliance violation banner ---

@Composable
private fun ViolationBanner(violation: ComplianceViolation?) {
    if (violation == null) return
    val bannerColor = when (violation.severity) {
        ViolationSeverity.CRITICAL -> FoxBearishText
        ViolationSeverity.WARNING -> FoxAmber50
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bannerColor.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (violation.severity == ViolationSeverity.CRITICAL) "\u26A0 VIOLATION" else "\u26A0 Warning",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = bannerColor,
                )
                Text(
                    "Bar #${violation.barIndex}",
                    fontSize = 10.sp,
                    color = FoxNeutral60,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                violation.rule,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = bannerColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                violation.description,
                fontSize = 11.sp,
                color = FoxNeutral60,
            )
        }
    }
}

// --- Performance summary card ---

@Composable
private fun PerformanceSummaryCard(performance: SimulationPerformance) {
    if (performance.totalTrades == 0) return
    LabCard {
        SectionTitle("Performance Summary")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                "Win Rate",
                pct(performance.winRate),
                if (performance.winRate >= 0.5) FoxBullishText else FoxBearishText,
                Modifier.weight(1f),
            )
            MetricTile(
                "Net Pts",
                fmtPts(performance.netPoints),
                pnlColor(performance.netPoints),
                Modifier.weight(1f),
            )
            MetricTile(
                "Avg R",
                fmt(performance.avgR),
                pnlColor(performance.avgR),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                "Compliance",
                "${performance.complianceScore}%",
                complianceColor(performance.complianceScore),
                Modifier.weight(1f),
            )
            MetricTile(
                "Trades",
                "${performance.wins}W / ${performance.losses}L",
                FoxAmber50,
                Modifier.weight(1f),
            )
            MetricTile(
                "Expectancy",
                fmtPts(performance.expectancy),
                pnlColor(performance.expectancy),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("T1 Hit", pct(performance.t1HitRate), FoxAmber50, Modifier.weight(1f))
            MetricTile("T2 Hit", pct(performance.t2HitRate), FoxAmber50, Modifier.weight(1f))
            MetricTile("Runner", pct(performance.runnerHitRate), FoxAmber50, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            performance.narrative,
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
    }
}

// --- Mini equity curve (Canvas sparkline) ---

@Composable
private fun EquityCurveCard(equityCurve: List<Double>) {
    if (equityCurve.size < 2) return
    LabCard {
        SectionTitle("Equity Curve")
        Spacer(Modifier.height(8.dp))
        val curveColor = if (equityCurve.last() >= equityCurve.first()) FoxBullishText else FoxBearishText
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FoxNeutral15),
        ) {
            val points = equityCurve
            val minVal = points.min()
            val maxVal = points.max()
            val range = (maxVal - minVal).coerceAtLeast(0.001)
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            val paddingY = 8f

            val path = Path()
            points.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - paddingY - ((value - minVal) / range) * (size.height - paddingY * 2)
                if (index == 0) {
                    path.moveTo(x, y.toFloat())
                } else {
                    path.lineTo(x, y.toFloat())
                }
            }

            drawPath(
                path = path,
                color = curveColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )

            // Draw zero line if applicable
            if (minVal < 0 && maxVal > 0) {
                val zeroY = size.height - paddingY - ((0.0 - minVal) / range) * (size.height - paddingY * 2)
                drawLine(
                    color = FoxNeutral60.copy(alpha = 0.4f),
                    start = Offset(0f, zeroY.toFloat()),
                    end = Offset(size.width, zeroY.toFloat()),
                    strokeWidth = 1f,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Start", fontSize = 10.sp, color = FoxNeutral60)
            Text(
                "Final: ${fmtPts(equityCurve.last())}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = curveColor,
            )
        }
    }
}

// --- Speed selector chips ---

@Composable
private fun SpeedSelectorCard(state: TradeProSimulatorUiState, viewModel: TradeProSimulatorViewModel) {
    if (!state.hasSession) return
    LabCard {
        SectionTitle("Simulation Speed")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SimulationSpeed.entries.forEach { speed ->
                val isSelected = state.speed == speed
                Text(
                    text = speed.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (isSelected) FoxAmber50 else FoxNeutral15)
                        .clickable { viewModel.setSpeed(speed) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Delay: ${state.speed.delayMs}ms per bar",
            fontSize = 10.sp,
            color = FoxNeutral60,
        )
    }
}

// --- Shared private composables ---

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

// --- Utility functions ---

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> FoxNeutral60
}

private fun stageLabel(stage: SetupStage): String = when (stage) {
    SetupStage.NONE -> "None"
    SetupStage.LEVEL -> "Level"
    SetupStage.ZONE -> "Zone"
    SetupStage.CONFIRMATION -> "Confirm"
    SetupStage.EXECUTE -> "Execute"
}

private fun stageColor(stage: SetupStage): Color = when (stage) {
    SetupStage.NONE -> FoxNeutral60
    SetupStage.LEVEL -> FoxNeutral60
    SetupStage.ZONE -> FoxAmber50
    SetupStage.CONFIRMATION -> FoxAmber50
    SetupStage.EXECUTE -> FoxBullishText
}

private fun confidenceColor(confidence: Int?): Color = when {
    confidence == null -> FoxNeutral60
    confidence >= 75 -> FoxBullishText
    confidence >= 50 -> FoxAmber50
    else -> FoxBearishText
}

private fun complianceColor(score: Int): Color = when {
    score >= 90 -> FoxBullishText
    score >= 70 -> FoxAmber50
    else -> FoxBearishText
}

private fun tradeStateLabel(state: SimulatedTradeState): String = when (state) {
    SimulatedTradeState.ACTIVE -> "Active"
    SimulatedTradeState.T1_HIT -> "T1 Hit"
    SimulatedTradeState.T2_HIT -> "T2 Hit"
    SimulatedTradeState.RUNNER -> "Runner"
    SimulatedTradeState.CLOSED -> "Closed"
}

private fun fmt(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.2f", v) else "--"
private fun fmtPts(v: Double): String = String.format(Locale.US, "%+.1f", v)
private fun fmtPrice(v: Double): String = String.format(Locale.US, "%.5f", v)
private fun pct(v: Double): String = String.format(Locale.US, "%.0f%%", v * 100)

// --- Session info header (symbol + timeframe badge) ---

@Composable
private fun SessionInfoHeader(state: TradeProSimulatorUiState) {
    val session = state.session ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.symbol,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                state.timeframe.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(FoxAmber50)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (state.isSynthetic) {
                Text(
                    "SYN",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = FoxAmber50,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(FoxAmber50.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (session.isComplete) "Session Complete" else if (state.isPlaying) "Playing..." else "Paused",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    session.isComplete -> FoxBullishText
                    state.isPlaying -> FoxAmber50
                    else -> FoxNeutral60
                },
            )
            Text(
                "${session.closedTrades.size} closed trades",
                fontSize = 10.sp,
                color = FoxNeutral60,
            )
        }
    }
}

// --- Drawdown and risk metrics card ---

@Composable
private fun DrawdownCard(state: TradeProSimulatorUiState) {
    val session = state.session ?: return
    LabCard {
        SectionTitle("Risk Metrics")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                "Peak Equity",
                fmtPrice(session.peakEquity),
                FoxBullishText,
                Modifier.weight(1f),
            )
            MetricTile(
                "Drawdown",
                pct(session.drawdown),
                when {
                    session.drawdown > 0.10 -> FoxBearishText
                    session.drawdown > 0.05 -> FoxAmber50
                    else -> FoxNeutral60
                },
                Modifier.weight(1f),
            )
            MetricTile(
                "Max DD",
                pct(state.performance.maxDrawdown),
                when {
                    state.performance.maxDrawdown > 0.10 -> FoxBearishText
                    state.performance.maxDrawdown > 0.05 -> FoxAmber50
                    else -> FoxNeutral60
                },
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Drawdown bar visualization
        val ddPct = (session.drawdown * 100).coerceIn(0.0, 100.0)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Current Drawdown", fontSize = 10.sp, color = FoxNeutral60)
                Text(
                    String.format(Locale.US, "%.1f%%", ddPct),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        ddPct > 10.0 -> FoxBearishText
                        ddPct > 5.0 -> FoxAmber50
                        else -> FoxNeutral60
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(FoxNeutral15),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (ddPct / 20.0).toFloat().coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                ddPct > 10.0 -> FoxBearishText
                                ddPct > 5.0 -> FoxAmber50
                                else -> FoxBullishText
                            },
                        ),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("0%", fontSize = 9.sp, color = FoxNeutral60)
                Text("5%", fontSize = 9.sp, color = FoxAmber50)
                Text("10%", fontSize = 9.sp, color = FoxBearishText)
                Text("20%", fontSize = 9.sp, color = FoxBearishText)
            }
        }
    }
}

// --- Closed trades history summary ---

@Composable
private fun ClosedTradesCard(state: TradeProSimulatorUiState) {
    val session = state.session ?: return
    if (session.closedTrades.isEmpty()) return
    LabCard {
        SectionTitle("Recent Closed Trades")
        Spacer(Modifier.height(8.dp))
        session.closedTrades.takeLast(5).reversed().forEachIndexed { index, trade ->
            ClosedTradeRow(trade, index == 0)
            if (index < session.closedTrades.takeLast(5).size - 1) {
                Spacer(Modifier.height(6.dp))
            }
        }
        if (session.closedTrades.size > 5) {
            Spacer(Modifier.height(8.dp))
            Text(
                "+${session.closedTrades.size - 5} more trades",
                fontSize = 11.sp,
                color = FoxNeutral60,
            )
        }
    }
}

@Composable
private fun ClosedTradeRow(trade: SimulatedTrade, isLatest: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isLatest) FoxNeutral15 else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (trade.direction == Direction.BULLISH) "\u2191" else "\u2193",
                fontSize = 14.sp,
                color = if (trade.direction == Direction.BULLISH) FoxBullishText else FoxBearishText,
            )
            Column {
                Text(
                    "${trade.direction.name} @ ${fmtPrice(trade.entryPrice)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (trade.t1Hit) Text("T1\u2713", fontSize = 9.sp, color = FoxBullishText)
                    if (trade.t2Hit) Text("T2\u2713", fontSize = 9.sp, color = FoxBullishText)
                    if (trade.runnerHit) Text("R\u2713", fontSize = 9.sp, color = FoxBullishText)
                    trade.exitReason?.let {
                        Text(it, fontSize = 9.sp, color = FoxNeutral60)
                    }
                }
            }
        }
        Text(
            fmtPts(trade.totalPoints),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = pnlColor(trade.totalPoints),
        )
    }
}

// --- Session complete summary card ---

@Composable
private fun SessionCompleteCard(state: TradeProSimulatorUiState, viewModel: TradeProSimulatorViewModel) {
    val session = state.session ?: return
    if (!session.isComplete) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxBullishText.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Session Complete!",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = FoxBullishText,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "You have walked through all ${session.totalBars} bars. " +
                    "Review your performance metrics below, then start a new session " +
                    "to practice on different data.",
                fontSize = 12.sp,
                color = FoxNeutral60,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    "Final Equity",
                    fmtPrice(session.equity),
                    pnlColor(session.equity - 10000.0),
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Total Trades",
                    state.performance.totalTrades.toString(),
                    FoxAmber50,
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Score",
                    "${state.performance.complianceScore}%",
                    complianceColor(state.performance.complianceScore),
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::startSession,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Start New Session", fontWeight = FontWeight.Bold)
            }
        }
    }
}