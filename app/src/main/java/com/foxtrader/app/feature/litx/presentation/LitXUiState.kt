package com.foxtrader.app.feature.litx.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.Timeframe

/**
 * Immutable UI state for the LIT X analysis screen.
 * All fields are Compose-stable (domain.model.* is in compose-stability.conf).
 */
@Immutable
data class LitXUiState(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.H1,
    val isLoading: Boolean = false,
    val analysis: LitXAnalysis? = null,
    val config: LitXConfig = LitXConfig(),
    val isSynthetic: Boolean = false,
    val error: String? = null,
) {
    val signal get() = analysis?.signal
    val hasSignal: Boolean get() = analysis?.hasSignal == true
}
