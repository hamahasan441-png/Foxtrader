package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.tradepro.TraderProfile

@Immutable
data class TraderProfileUiState(
    val isLoading: Boolean = true,
    val profile: TraderProfile? = null,
    val error: String? = null,
) {
    val hasProfile: Boolean get() = profile != null && profile.totalClosedTrades > 0
}
