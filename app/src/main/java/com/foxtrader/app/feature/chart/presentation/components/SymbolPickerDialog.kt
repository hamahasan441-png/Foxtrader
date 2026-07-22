package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Symbol picker dialog — lets the user switch the charted instrument.
 */
@Composable
fun SymbolPickerDialog(
    visible: Boolean,
    symbols: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FoxNeutral10)
                .padding(16.dp),
        ) {
            Text(
                text = "Select Symbol",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(symbols) { symbol ->
                    val isSelected = symbol == selected
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) FoxAmber50 else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSelect(symbol) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
