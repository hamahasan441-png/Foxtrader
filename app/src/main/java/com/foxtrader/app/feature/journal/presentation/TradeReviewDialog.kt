package com.foxtrader.app.feature.journal.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Post-trade review dialog: lets the trader assign or change a 1..5 execution
 * rating and the emotion state for an existing journal entry. Changes persist
 * immediately through [onSetRating] / [onSetEmotion] (which route to the
 * repository), so the "Rating" stat and per-entry label stop being read-only.
 */
@Composable
fun TradeReviewDialog(
    entry: JournalEntry,
    onSetRating: (Int) -> Unit,
    onSetEmotion: (EmotionTag) -> Unit,
    onDismiss: () -> Unit,
) {
    var rating by remember(entry.id) { mutableIntStateOf(entry.rating) }
    var emotion by remember(entry.id) { mutableStateOf(entry.emotionTag) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Text(
                    text = "Review ${entry.symbol}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.setupType,
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )

                Spacer(Modifier.height(16.dp))
                Text("Execution rating", style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
                Spacer(Modifier.height(6.dp))
                Row(
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
                                .size(34.dp)
                                .clickable {
                                    val next = if (rating == star) star - 1 else star
                                    rating = next
                                    onSetRating(next)
                                }
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

                Spacer(Modifier.height(16.dp))
                Text("Emotion", style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EmotionTag.entries.forEach { tag ->
                        FilterChip(
                            selected = emotion == tag,
                            onClick = {
                                emotion = tag
                                onSetEmotion(tag)
                            },
                            label = {
                                Text(tag.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Done", fontWeight = FontWeight.SemiBold, color = FoxAmber50)
                    }
                }
            }
        }
    }
}
