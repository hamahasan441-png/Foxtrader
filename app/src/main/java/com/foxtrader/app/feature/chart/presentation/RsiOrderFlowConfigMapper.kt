package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.indicators.RsiOrderFlow
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine

/** Keep chart study controls and executable RSI Orderflow signals on one config. */
internal fun RsiOrderFlowStudySettings.toSignalEngineConfig(): RsiOrderFlowSignalEngine.Config {
    val safe = sanitized()
    return RsiOrderFlowSignalEngine.Config(
        study = RsiOrderFlow.Config(
            rsiPeriod = safe.rsiPeriod,
            flowPeriod = safe.flowPeriod,
            flowSmoothing = safe.flowSmoothing,
            pivotLeft = safe.pivotLeft,
            pivotRight = safe.pivotRight,
            minPivotSeparation = safe.minPivotSeparation,
            maxPivotSeparation = safe.maxPivotSeparation,
            minRsiDifference = safe.minRsiDifference,
            minFlowDifference = safe.minFlowDifference,
            includeHidden = safe.includeHidden,
        ),
    )
}
