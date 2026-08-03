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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.foxtrader.app.domain.model.tradepro.AlertPriority
import com.foxtrader.app.domain.model.tradepro.AlertRule
import com.foxtrader.app.domain.model.tradepro.AlertStage
import com.foxtrader.app.domain.model.tradepro.AlertTriggerType
import com.foxtrader.app.domain.model.tradepro.TriggeredAlert
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertRulesScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: AlertRulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Alerts", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::startNewRule) {
                        Icon(Icons.Default.Add, contentDescription = "New rule", tint = FoxAmber50)
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

            val draft = state.draft
            if (draft != null) {
                RuleBuilder(draft, viewModel)
            } else {
                TestScanCard(state, viewModel)
                if (state.previewAlerts.isNotEmpty()) {
                    PreviewAlertsCard(state.previewAlerts)
                }
                RulesListCard(state, viewModel)
            }

            state.error?.let { LabCard { Text(it, color = FoxBearishText, fontSize = 13.sp) } }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// --- Test scan ---

@Composable
private fun TestScanCard(state: AlertRulesUiState, viewModel: AlertRulesViewModel) {
    LabCard {
        SectionTitle("Test Your Alerts")
        Spacer(Modifier.height(6.dp))
        Text(
            "Run your enabled rules against the live watchlist to preview which alerts would fire now.",
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = viewModel::testScan,
            enabled = !state.isScanning && state.hasRules,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
        ) {
            if (state.isScanning) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp, modifier = Modifier.height(18.dp).width(18.dp))
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Run Test Scan", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PreviewAlertsCard(alerts: List<TriggeredAlert>) {
    LabCard {
        SectionTitle("Would Fire Now (${alerts.size})")
        Spacer(Modifier.height(8.dp))
        alerts.take(12).forEach { alert ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PriorityDot(alert.priority)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(alert.ruleName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(alert.message, fontSize = 11.sp, color = FoxNeutral60)
                }
            }
        }
    }
}

// --- Rules list ---

@Composable
private fun RulesListCard(state: AlertRulesUiState, viewModel: AlertRulesViewModel) {
    LabCard {
        SectionTitle("Alert Rules")
        Spacer(Modifier.height(8.dp))
        if (!state.hasRules) {
            Text(
                "No rules yet. Tap + to create your first smart alert \u2014 e.g. \"alert me when any " +
                    "symbol reaches an executable setup with HTF alignment.\"",
                fontSize = 12.sp,
                color = FoxNeutral60,
            )
        } else {
            state.rules.forEachIndexed { index, rule ->
                RuleRow(rule, viewModel)
                if (index < state.rules.size - 1) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RuleRow(rule: AlertRule, viewModel: AlertRulesViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral15)
            .clickable { viewModel.editRule(rule.id) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rule.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (rule.appliesToAllSymbols) "ALL" else rule.symbol,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = FoxAmber50,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(FoxAmber50.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(rule.conditionText(), fontSize = 11.sp, color = FoxNeutral60)
            Text("Cooldown ${rule.cooldownMinutes}m", fontSize = 10.sp, color = FoxNeutral60)
        }
        Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleRule(rule.id) })
        IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = FoxBearishText)
        }
    }
}

// --- Rule builder ---

@Composable
private fun RuleBuilder(draft: AlertRule, viewModel: AlertRulesViewModel) {
    LabCard {
        SectionTitle(if (draft.name.isBlank()) "New Rule" else "Edit Rule")
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.name,
            onValueChange = { viewModel.updateDraft(draft.copy(name = it)) },
            label = { Text("Rule name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        FieldLabel("Trigger condition")
        ChipFlow(
            items = AlertTriggerType.entries.toList(),
            selected = draft.trigger,
            label = { it.label },
            onSelect = { viewModel.updateDraft(draft.copy(trigger = it)) },
        )

        if (draft.trigger.usesStage) {
            Spacer(Modifier.height(12.dp))
            FieldLabel("Minimum stage")
            ChipFlow(
                items = AlertStage.entries.toList(),
                selected = draft.minStage,
                label = { it.label },
                onSelect = { viewModel.updateDraft(draft.copy(minStage = it)) },
            )
        }

        if (draft.trigger.usesThreshold) {
            Spacer(Modifier.height(12.dp))
            val isPercent = draft.trigger == AlertTriggerType.CONFIDENCE_ABOVE
            FieldLabel(if (isPercent) "Confidence threshold (%)" else "R:R threshold")
            ThresholdStepper(
                value = draft.threshold,
                step = if (isPercent) 5.0 else 0.5,
                min = 0.0,
                max = if (isPercent) 100.0 else 10.0,
                onChange = { viewModel.updateDraft(draft.copy(threshold = it)) },
            )
        }

        Spacer(Modifier.height(12.dp))
        FieldLabel("Symbol")
        ChipFlow(
            items = AlertRulesUiState.SYMBOL_CHOICES,
            selected = draft.symbol,
            label = { if (it.isBlank()) "All" else it },
            onSelect = { viewModel.updateDraft(draft.copy(symbol = it)) },
        )

        Spacer(Modifier.height(12.dp))
        FieldLabel("Timeframe")
        ChipFlow(
            items = AlertRulesUiState.TIMEFRAME_CHOICES,
            selected = draft.timeframeLabel,
            label = { if (it.isBlank()) "Default" else it },
            onSelect = { viewModel.updateDraft(draft.copy(timeframeLabel = it)) },
        )

        Spacer(Modifier.height(12.dp))
        FieldLabel("Cooldown (minutes)")
        ThresholdStepper(
            value = draft.cooldownMinutes.toDouble(),
            step = 5.0,
            min = 0.0,
            max = 240.0,
            onChange = { viewModel.updateDraft(draft.copy(cooldownMinutes = it.toInt())) },
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = draft.enabled, onCheckedChange = { viewModel.updateDraft(draft.copy(enabled = it)) })
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = viewModel::cancelDraft,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = FoxNeutral15, contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text("Cancel") }
            Button(
                onClick = viewModel::saveDraft,
                enabled = draft.name.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
            ) { Text("Save Rule", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ThresholdStepper(value: Double, step: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepperButton("\u2212") { onChange((value - step).coerceIn(min, max)) }
        Text(
            trimNumber(value),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
            modifier = Modifier.weight(1f),
        )
        StepperButton("+") { onChange((value + step).coerceIn(min, max)) }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .width(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FoxNeutral15)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

// --- Shared private composables ---

@Composable
private fun <T> ChipFlow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PriorityDot(priority: AlertPriority) {
    Box(
        modifier = Modifier
            .height(10.dp)
            .width(10.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(priorityColor(priority)),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, color = FoxNeutral60)
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

private fun priorityColor(priority: AlertPriority): Color = when (priority) {
    AlertPriority.CRITICAL -> FoxBearishText
    AlertPriority.HIGH -> FoxAmber50
    AlertPriority.MEDIUM -> FoxBullishText
    AlertPriority.LOW -> FoxNeutral60
}

private fun trimNumber(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
