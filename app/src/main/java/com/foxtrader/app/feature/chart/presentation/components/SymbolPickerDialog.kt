package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.foxtrader.app.R
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
    onAddSymbol: (String) -> Unit = {},
    onRemoveSymbol: (String) -> Unit = {},
) {
    if (!visible) return
    var newSymbol by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FoxNeutral10)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.chart_watchlist_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )

            // Add-symbol row. The field is uppercased on submit so the
            // repository's normalisation and the display agree.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = newSymbol,
                    onValueChange = { newSymbol = it.uppercase() },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.chart_watchlist_add_symbol), fontSize = 11.sp) },
                )
                IconButton(
                    onClick = {
                        if (newSymbol.isNotBlank()) {
                            onAddSymbol(newSymbol.trim())
                            newSymbol = ""
                        }
                    },
                    enabled = newSymbol.isNotBlank(),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.chart_watchlist_add_symbol_cd),
                        tint = if (newSymbol.isNotBlank()) FoxAmber50 else FoxNeutral60,
                    )
                }
            }

            if (symbols.isEmpty()) {
                Text(
                    text = stringResource(R.string.chart_watchlist_empty),
                    color = FoxNeutral60,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(symbols, key = { it }) { symbol ->
                    val isSelected = symbol == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSelect(symbol) }
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) FoxAmber50
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp),
                        )
                        IconButton(onClick = { onRemoveSymbol(symbol) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.chart_watchlist_remove_symbol_cd, symbol),
                                tint = FoxNeutral60,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
