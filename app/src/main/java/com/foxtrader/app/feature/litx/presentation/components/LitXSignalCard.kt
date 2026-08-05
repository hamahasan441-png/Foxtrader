package com.foxtrader.app.feature.litx.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.LitXSignal
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

/**
 * A validated LIT X institutional setup card. Styled to match the app's design
 * language (mirrors TradeProSetupCard: FoxNeutral10 surface, directional
 * colouring, amber accents).
 */
@Composable
fun LitXSignalCard(signal: LitXSignal, modifier: Modifier = Modifier) {
    val bullish = signal.direction == Direction.BULLISH
    val dirColor = if (bullish) FoxBullishText else FoxBearishText

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FoxNeutral10)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (bullish) stringResource(R.string.litx_direction_long) else stringResource(R.string.litx_direction_short),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = dirColor,
                )
                Spacer(Modifier.width(8.dp))
                GradeBadge(signal.confidence.grade)
            }
            Text(
                text = "${signal.confidence.score}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )
        }

        Text(
            text = signal.rationale,
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )

        MetricRow(stringResource(R.string.litx_metric_entry), fmt(signal.entry))
        MetricRow(stringResource(R.string.litx_metric_stop), fmt(signal.stopLoss))
        MetricRow(stringResource(R.string.litx_metric_target_1), fmt(signal.takeProfit1))
        MetricRow(stringResource(R.string.litx_metric_target_2), fmt(signal.takeProfit2))
        MetricRow(stringResource(R.string.litx_metric_risk_reward), String.format(Locale.US, "%.2f", signal.riskReward))
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun GradeBadge(grade: LitXGrade) {
    val (label, color: Color) = when (grade) {
        LitXGrade.A_PLUS -> "A+" to FoxAmber50
        LitXGrade.A -> "A" to FoxBullishText
        LitXGrade.B -> "B" to FoxNeutral60
        LitXGrade.REJECT -> "Reject" to FoxBearishText
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun fmt(price: Double): String =
    if (price >= 1000) String.format(Locale.US, "%,.2f", price) else String.format(Locale.US, "%.5f", price)
