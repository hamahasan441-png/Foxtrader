package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Bottom-anchored, collapsible "Analysis" sheet.
 *
 * TradingView keeps the price surface clean and pulls decision/context detail
 * up on demand. This consolidates the four cards that previously floated over
 * the chart (AI decision, market context, MTF confluence, TRADEPRO setup) into
 * a single sheet: collapsed it is a slim summary handle (bias + AI confidence);
 * expanded it reveals the existing panels in a scrollable column.
 *
 * `RULE` Renders nothing when there is no analysis to show, so the chart is
 * fully unobstructed until the engines produce something worth surfacing.
 */
@Composable
fun ChartAnalysisSheet(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    bias: Bias,
    decision: DecisionResult?,
    explanation: MarketExplanation?,
    confluence: ConfluenceEngine.ConfluenceResult?,
    tradeProAnalysis: TradeProAnalysis?,
    modifier: Modifier = Modifier,
) {
    val hasContent = decision != null || explanation != null ||
        confluence != null || tradeProAnalysis != null
    if (!hasContent) return

    val toggleLabel = stringResource(
        if (expanded) R.string.chart_analysis_collapse else R.string.chart_analysis_expand,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(FoxNeutral10.copy(alpha = 0.96f)),
    ) {
        // --- Summary handle (always visible; tap to expand/collapse) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = toggleLabel,
                    role = Role.Button,
                    onClick = onToggleExpanded,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Insights,
                contentDescription = null,
                tint = FoxAmber50,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.chart_analysis_sheet_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SummaryBiasChip(bias)
            decision?.let { d ->
                Text(
                    text = stringResource(R.string.chart_analysis_ai_short, d.confidence.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxNeutral60,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = FoxNeutral60,
                modifier = Modifier.size(20.dp),
            )
        }

        // --- Expanded body: the existing panels stacked in a scroll column ---
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiDecisionPanel(decision = decision, modifier = Modifier.fillMaxWidth())
                ConfluenceRibbon(result = confluence, modifier = Modifier.fillMaxWidth())
                MarketContextPanel(explanation = explanation, modifier = Modifier.fillMaxWidth())
                TradeProSetupCard(analysis = tradeProAnalysis, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SummaryBiasChip(bias: Bias) {
    val (label, color): Pair<String, Color> = when (bias) {
        Bias.BULLISH -> stringResource(R.string.chart_bias_bullish) to FoxBullishText
        Bias.BEARISH -> stringResource(R.string.chart_bias_bearish) to FoxBearishText
        Bias.NEUTRAL -> stringResource(R.string.chart_bias_neutral) to FoxNeutral60
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(FoxNeutral20.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
