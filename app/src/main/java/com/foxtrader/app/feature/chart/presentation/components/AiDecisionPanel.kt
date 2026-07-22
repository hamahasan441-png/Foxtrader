package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * A compact, semi-transparent AI decision badge that floats on the top-left
 * of the chart. Shows: grade, direction, confidence, and a confluence dot row.
 * Designed to be information-dense yet unobtrusive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiDecisionPanel(
    decision: DecisionResult?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = decision != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        decision?.let { d ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(FoxNeutral10.copy(alpha = 0.88f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                // Row 1: grade badge + direction + confidence
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradeBadge(d.grade)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (d.direction) {
                            Direction.BULLISH -> "BULL"
                            Direction.BEARISH -> "BEAR"
                            null -> "—"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (d.direction) {
                            Direction.BULLISH -> FoxBullish
                            Direction.BEARISH -> FoxBearish
                            null -> FoxNeutral60
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${d.confidence.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Row 2: confluence dot indicators (green = present, grey = missing)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    RequiredConfluence.all().forEach { c ->
                        val present = c in d.confluencePresent
                        ConfluenceDot(present = present, label = c.shortLabel())
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeBadge(grade: SignalGrade) {
    val (text, color) = when (grade) {
        SignalGrade.INSTITUTIONAL -> "INST" to FoxAmber50
        SignalGrade.VERY_STRONG -> "A+" to FoxBullish
        SignalGrade.STRONG -> "A" to FoxBullish
        SignalGrade.MODERATE -> "B" to FoxAmber50
        SignalGrade.WEAK -> "C" to FoxNeutral60
        SignalGrade.NO_SIGNAL -> "—" to FoxNeutral60
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@Composable
private fun ConfluenceDot(present: Boolean, label: String) {
    val color = if (present) FoxBullish else FoxNeutral60.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun RequiredConfluence.shortLabel(): String = when (this) {
    RequiredConfluence.LIQUIDITY_SWEEP -> "SW"
    RequiredConfluence.BOS_OR_CHOCH -> "BR"
    RequiredConfluence.FVG -> "FV"
    RequiredConfluence.ORDER_BLOCK -> "OB"
    RequiredConfluence.SMT -> "SM"
    RequiredConfluence.SESSION -> "KZ"
    RequiredConfluence.HTF_BIAS -> "HT"
    RequiredConfluence.TREND -> "TR"
    RequiredConfluence.VOLUME -> "VL"
}
