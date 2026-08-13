package com.foxtrader.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.ui.theme.FoxTheme
import java.util.Locale
import kotlin.math.abs

@Composable
fun FoxPriceText(
    price: Double,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val formatted = formatPrice(price)
    Text(
        text = formatted,
        style = FoxTheme.type.price,
        color = FoxTheme.colors.textPrimary,
        modifier = modifier.semantics {
            this.contentDescription = contentDescription ?: "Price $formatted"
        },
    )
}

@Composable
fun FoxPercentText(
    value: Double,
    modifier: Modifier = Modifier,
    signed: Boolean = true,
) {
    val colors = FoxTheme.colors
    val formatted = formatSignedPercent(value, signed)
    val color = when {
        value > 0.0 -> colors.bullishText
        value < 0.0 -> colors.bearishText
        else -> colors.textSecondary
    }
    val arrow = when {
        value > 0.0 -> "▲ "
        value < 0.0 -> "▼ "
        else -> ""
    }
    Text(
        text = arrow + formatted,
        style = FoxTheme.type.percentage,
        color = color,
        modifier = modifier.semantics { contentDescription = formatted },
    )
}

@Composable
fun FoxDirectionBadge(
    direction: Direction,
    modifier: Modifier = Modifier,
    longLabel: String = "LONG",
    shortLabel: String = "SHORT",
) {
    val colors = FoxTheme.colors
    val bullish = direction == Direction.BULLISH
    val color = if (bullish) colors.bullishText else colors.bearishText
    val label = if (bullish) longLabel else shortLabel
    val prefix = if (bullish) "▲ " else "▼ "
    FoxBadge(text = prefix + label, color = color, modifier = modifier)
}

@Composable
fun FoxBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FoxTheme.colors.accent,
) {
    Text(
        text = text,
        style = FoxTheme.type.caption,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(FoxTheme.shapes.xs))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun FoxChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = FoxTheme.colors.accent,
) {
    val colors = FoxTheme.colors
    Text(
        text = label,
        style = FoxTheme.type.label,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = if (selected) colors.onAccent else colors.textSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(FoxTheme.shapes.sm))
            .background(if (selected) accent else colors.surfaceStrong)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { role = Role.Button },
    )
}

@Composable
fun FoxProBadge(modifier: Modifier = Modifier) {
    FoxBadge(text = "PRO", color = FoxTheme.colors.ai, modifier = modifier)
}

fun formatPrice(price: Double): String =
    if (!price.isFinite()) "—"
    else if (abs(price) >= 1000) String.format(Locale.US, "%,.2f", price)
    else String.format(Locale.US, "%.5f", price)

fun formatSignedPercent(value: Double, signed: Boolean = true): String {
    if (!value.isFinite()) return "—"
    val rounded = kotlin.math.round(value * 100.0) / 100.0
    val sign = if (signed && rounded > 0) "+" else ""
    return String.format(Locale.US, "%s%.2f%%", sign, rounded)
}

fun formatMoney(value: Double): String {
    if (!value.isFinite()) return "—"
    val sign = if (value > 0) "+" else if (value < 0) "-" else ""
    return sign + "$" + String.format(Locale.US, "%,.2f", abs(value))
}
