package com.foxtrader.app.feature.portfolio.presentation

import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.usecase.correlation.CorrelationMatrix
import com.foxtrader.app.domain.usecase.portfolio.PortfolioRiskSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Immutable UI state for the Portfolio screen.
 *
 * `dataSource` is carried through from the market cache so correlation
 * clusters computed over synthetic bars are labelled rather than presented as
 * real relationships (Sprint 6 contract).
 */
data class PortfolioUiState(
    val snapshot: PortfolioRiskSnapshot? = null,
    val correlationClusters: ImmutableList<CorrelationCluster> = persistentListOf(),
    val accountEquity: Double = 0.0,
    val dataSource: CandleSource = CandleSource.CACHED,
    val isLoading: Boolean = true,
) {
    val hasPositions: Boolean get() = (snapshot?.positions?.isNotEmpty()) == true
    val isSyntheticData: Boolean get() = dataSource == CandleSource.SYNTHETIC
    val warnings: List<String> get() = snapshot?.warnings.orEmpty()
}

/**
 * A group of held symbols whose returns move together strongly enough to be
 * treated as one risk unit.
 */
data class CorrelationCluster(
    val symbols: List<String>,
    /** Strongest absolute correlation observed inside the cluster. */
    val peakCorrelation: Double,
    /** Combined exposure of the cluster, as a percentage of equity. */
    val combinedExposurePercent: Double,
    val strength: CorrelationMatrix.CorrelationStrength,
) {
    /** Mirrors CorrelationClusterBuilder.Cluster.isHedge (domain semantics). */
    val isHedge: Boolean get() = peakCorrelation < 0.0
}
