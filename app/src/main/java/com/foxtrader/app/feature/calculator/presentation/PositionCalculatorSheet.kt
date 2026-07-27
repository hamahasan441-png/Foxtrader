package com.foxtrader.app.feature.calculator.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.calculator.PositionCalculator
import com.foxtrader.app.domain.usecase.calculator.RiskAwarePositionCalculator
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxError
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxWarning
import kotlin.math.abs
import kotlin.math.round

/**
 * Position-size calculator sheet.
 *
 * Surfaces `PositionCalculator` (144 lines, previously zero call sites) and
 * always reports the risk engine's verdict alongside the number, so the app
 * never suggests a size the order path would refuse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionCalculatorSheet(
    symbol: String,
    lastPrice: Double?,
    onDismiss: () -> Unit,
    viewModel: CalculatorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.runtime.LaunchedEffect(symbol, lastPrice) {
        viewModel.prefill(symbol, lastPrice)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Position Size",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                color = FoxAmber50,
            )

            OutlinedTextField(
                value = state.form.symbol,
                onValueChange = { v -> viewModel.updateForm { it.copy(symbol = v.uppercase()) } },
                label = { Text("Symbol") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            DirectionToggle(
                selected = state.form.direction,
                onSelect = viewModel::setDirection,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = state.form.entryPrice,
                    label = "Entry",
                    modifier = Modifier.weight(1f),
                ) { v -> viewModel.updateForm { it.copy(entryPrice = v) } }
                NumberField(
                    value = state.form.stopLoss,
                    label = "Stop loss",
                    modifier = Modifier.weight(1f),
                ) { v -> viewModel.updateForm { it.copy(stopLoss = v) } }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = state.form.takeProfit,
                    label = "Target (optional)",
                    modifier = Modifier.weight(1f),
                ) { v -> viewModel.updateForm { it.copy(takeProfit = v) } }
                NumberField(
                    value = state.form.riskPercent,
                    label = "Risk %",
                    modifier = Modifier.weight(1f),
                ) { v -> viewModel.updateForm { it.copy(riskPercent = v) } }
            }

            NumberField(
                value = state.form.accountBalance,
                label = "Account balance",
                modifier = Modifier.fillMaxWidth(),
            ) { v -> viewModel.updateForm { it.copy(accountBalance = v) } }

            Button(
                onClick = viewModel::calculate,
                enabled = state.form.isComplete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
            ) {
                Text("Calculate", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            if (state.validationErrors.isNotEmpty()) {
                ProblemCard(state.validationErrors)
            }

            state.sized?.let { sized ->
                if (!sized.allowed) {
                    RiskBlockedCard(sized.riskCheck.reasons)
                }
                ResultCard(sized)
                PartialsCard(sized.partials)
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun DirectionToggle(selected: Direction, onSelect: (Direction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Direction.entries.forEach { direction ->
            val isSelected = direction == selected
            val accent = if (direction == Direction.BULLISH) FoxBullishText else FoxBearishText
            Text(
                text = if (direction == Direction.BULLISH) "LONG" else "SHORT",
                color = if (isSelected) Color.Black else accent,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) accent else accent.copy(alpha = 0.12f))
                    .clickable { onSelect(direction) }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProblemCard(reasons: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FoxError.copy(alpha = 0.12f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Check your inputs", color = FoxError, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        reasons.forEach { Text("• $it", color = FoxError, fontSize = 11.sp) }
    }
}

/**
 * Shown when a size WAS computed but the risk engine would refuse it. The
 * numbers still render below — a trader needs to see how far over the limit
 * they are — but the verdict comes first.
 */
@Composable
private fun RiskBlockedCard(reasons: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FoxWarning.copy(alpha = 0.16f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "RISK ENGINE WOULD BLOCK THIS TRADE",
            color = FoxWarning,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        reasons.forEach { Text("• $it", color = FoxWarning, fontSize = 11.sp) }
    }
}

@Composable
private fun ResultCard(sized: RiskAwarePositionCalculator.Outcome.Sized) {
    val r = sized.result
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral10)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            Arrangement.SpaceBetween,
            Alignment.CenterVertically,
        ) {
            Column {
                Text("Position size", color = FoxNeutral60, fontSize = 10.sp)
                Text(
                    text = "${trim(r.positionSize)} lots",
                    color = if (sized.allowed) FoxAmber50 else FoxNeutral60,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
            Text(
                text = sized.instrumentType.name.replace('_', ' '),
                color = FoxNeutral60,
                fontSize = 9.sp,
            )
        }

        MetricRow("Risk amount", money(r.riskAmount))
        MetricRow("Stop distance", "${trim(r.stopDistancePips)} pips")
        r.riskRewardRatio?.let { MetricRow("Risk / reward", "1 : ${trim(it)}") }
        r.rewardAmount?.let { MetricRow("Potential reward", money(it)) }
        MetricRow("Pip value (per lot)", money(r.pipValue))
        MetricRow("Margin required", money(r.marginRequired))
        MetricRow("Break-even", trim(r.breakEvenPrice))
    }
}

@Composable
private fun PartialsCard(levels: List<PositionCalculator.PartialCloseLevel>) {
    if (levels.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral10)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Scale-out plan", color = FoxNeutral60, fontSize = 10.sp)
        levels.forEach { level ->
            MetricRow(
                label = "Close ${round(level.percentage * 100).toInt()}% at ${trim(level.rTarget)}R",
                value = trim(level.price),
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, color = FoxNeutral60, fontSize = 11.sp)
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun money(value: Double): String = "$" + trim(round(value * 100) / 100.0)

private fun trim(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    val rounded = round(value * 100_000) / 100_000.0
    return if (abs(rounded - rounded.toLong()) < 1e-9) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
