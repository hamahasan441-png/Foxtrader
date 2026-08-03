package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.SymbolCorrelationMatrix

@Immutable
data class CorrelationUiState(
    val timeframe: Timeframe = Timeframe.H1,
    val isComputing: Boolean = false,
    val matrix: SymbolCorrelationMatrix = SymbolCorrelationMatrix.empty("No scan yet."),
    val error: String? = null,
) {
    val hasMatrix: Boolean get() = matrix.symbols.size >= 2
}
