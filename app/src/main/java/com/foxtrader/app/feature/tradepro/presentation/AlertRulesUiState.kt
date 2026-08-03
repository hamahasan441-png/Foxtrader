package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.tradepro.AlertRule
import com.foxtrader.app.domain.model.tradepro.TriggeredAlert
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class AlertRulesUiState(
    val rules: ImmutableList<AlertRule> = persistentListOf(),
    /** Non-null while the builder sheet is open (creating or editing). */
    val draft: AlertRule? = null,
    val previewAlerts: ImmutableList<TriggeredAlert> = persistentListOf(),
    val isScanning: Boolean = false,
    val lastScanEpochMs: Long = 0L,
    val error: String? = null,
) {
    val isEditing: Boolean get() = draft != null
    val hasRules: Boolean get() = rules.isNotEmpty()

    companion object {
        val SYMBOL_CHOICES: ImmutableList<String> = persistentListOf(
            "", "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "XAUUSD",
            "BTCUSDT", "ETHUSDT", "NAS100", "US500",
        )
        val TIMEFRAME_CHOICES: ImmutableList<String> =
            listOf("", "15m", "1H", "4H", "1D").toImmutableList()
    }
}
