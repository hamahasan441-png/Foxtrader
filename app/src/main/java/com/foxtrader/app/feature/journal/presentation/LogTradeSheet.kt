package com.foxtrader.app.feature.journal.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * "Log Trade" modal bottom sheet — a form to record a trade into the journal.
 *
 * Leaving the Exit Price blank logs an OPEN trade; entering it logs a CLOSED
 * trade (PnL + R computed by the engine). Fully stateless — driven by
 * [form] and callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTradeSheet(
    form: LogTradeForm,
    onFormChange: ((LogTradeForm) -> LogTradeForm) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Log Trade",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                color = FoxAmber50,
            )

            // Symbol
            OutlinedTextField(
                value = form.symbol,
                onValueChange = { v -> onFormChange { it.copy(symbol = v) } },
                label = { Text("Symbol (e.g. EURUSD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            // Direction (Long / Short)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.direction == Direction.BULLISH,
                    onClick = { onFormChange { it.copy(direction = Direction.BULLISH) } },
                    label = { Text("LONG") },
                )
                FilterChip(
                    selected = form.direction == Direction.BEARISH,
                    onClick = { onFormChange { it.copy(direction = Direction.BEARISH) } },
                    label = { Text("SHORT") },
                )
            }

            // Timeframe chips
            Text("Timeframe", style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Timeframe.entries.forEach { tf ->
                    FilterChip(
                        selected = form.timeframe == tf,
                        onClick = { onFormChange { it.copy(timeframe = tf) } },
                        label = { Text(tf.label) },
                    )
                }
            }

            // Prices: Entry + Stop
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = form.entryPrice,
                    label = "Entry",
                    onChange = { v -> onFormChange { it.copy(entryPrice = v) } },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = form.stopLoss,
                    label = "Stop Loss",
                    onChange = { v -> onFormChange { it.copy(stopLoss = v) } },
                    modifier = Modifier.weight(1f),
                )
            }

            // Prices: Target + Exit
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = form.takeProfit,
                    label = "Take Profit",
                    onChange = { v -> onFormChange { it.copy(takeProfit = v) } },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = form.exitPrice,
                    label = "Exit (optional)",
                    onChange = { v -> onFormChange { it.copy(exitPrice = v) } },
                    modifier = Modifier.weight(1f),
                )
            }

            // Volume + Setup
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = form.volume,
                    label = "Volume (lots)",
                    onChange = { v -> onFormChange { it.copy(volume = v) } },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.setupType,
                    onValueChange = { v -> onFormChange { it.copy(setupType = v) } },
                    label = { Text("Setup") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            // Emotion chips
            Text("Emotion", style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EmotionTag.entries.forEach { tag ->
                    FilterChip(
                        selected = form.emotionTag == tag,
                        onClick = { onFormChange { it.copy(emotionTag = tag) } },
                        label = { Text(tag.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            // Execution rating (1..5). Tapping the current top star clears it.
            Text("Execution rating", style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
            RatingSelector(
                rating = form.rating,
                onRatingChange = { newRating -> onFormChange { it.copy(rating = newRating) } },
            )

            // Notes
            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> onFormChange { it.copy(notes = v) } },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onSubmit,
                enabled = form.isValid,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
            ) {
                Text(
                    text = if (form.exitPrice.toDoubleOrNull() != null) "Log Closed Trade" else "Log Open Trade",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * A 1..5 star rating row. Tapping a star sets the rating to that value; tapping
 * the currently-highest filled star again clears it back to unrated (0), so the
 * "Rating" journal stat isn't forced to a value the trader never intended.
 */
@Composable
private fun RatingSelector(
    rating: Int,
    onRatingChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (1..5).forEach { star ->
            val filled = star <= rating
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (filled) FoxAmber50 else FoxNeutral60,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onRatingChange(if (rating == star) star - 1 else star) }
                    .semantics { contentDescription = "Rate $star of 5" },
            )
        }
        Text(
            text = if (rating > 0) "$rating/5" else "Unrated",
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
    )
}
