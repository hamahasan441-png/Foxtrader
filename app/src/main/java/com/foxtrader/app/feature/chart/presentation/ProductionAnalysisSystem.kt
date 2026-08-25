package com.foxtrader.app.feature.chart.presentation

/**
 * Canonical FoxTrader signal engines.
 *
 * These names identify primary signal methodologies; they do not own or erase
 * chart studies. Technical indicators, SMC overlays and volume/profile studies
 * remain independent so every LiT Adventure mode can be combined with the same
 * indicator stack.
 */
enum class ProductionAnalysisSystem(val label: String) {
    LIT_ADVENTURE("LiT Adventure"),
    LIT_MAY_MADNESS("LiT May Madness"),
    SMT("SMT"),
    RSI_ORDERFLOW_CANDLE("RSI Orderflow Candle"),
    PIVOT_SWEEP_DIVERGENCE("Pivot Sweep Divergence"),
}

/** Resolve a single selected canonical engine when the state is unambiguous. */
fun IndicatorToggles.productionAnalysisSystem(): ProductionAnalysisSystem? {
    val selected = listOfNotNull(
        ProductionAnalysisSystem.LIT_ADVENTURE.takeIf { litX },
        ProductionAnalysisSystem.LIT_MAY_MADNESS.takeIf { lit },
        ProductionAnalysisSystem.SMT.takeIf { smt },
        ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE.takeIf { rsiOrderFlow },
        ProductionAnalysisSystem.PIVOT_SWEEP_DIVERGENCE.takeIf { pivotSweepDivergence },
    )
    return selected.singleOrNull()
}

/**
 * Switch the canonical primary engine without destroying unrelated studies.
 *
 * The legacy implementation rebuilt [IndicatorToggles] from scratch, which
 * silently disabled EMA/MACD/SMC/profile studies whenever the user changed the
 * analysis system. That made indicator availability depend on the active mode.
 * This implementation clears only the five mutually-exclusive canonical engine
 * flags and preserves every other chart choice and its [ChartStudySettings].
 */
fun IndicatorToggles.withProductionAnalysisSystem(
    system: ProductionAnalysisSystem?,
): IndicatorToggles {
    val preserved = copy(
        litX = false,
        lit = false,
        smt = false,
        rsiOrderFlow = false,
        pivotSweepDivergence = false,
    )

    return when (system) {
        ProductionAnalysisSystem.LIT_ADVENTURE -> preserved.withLitXSuite(true)
        ProductionAnalysisSystem.LIT_MAY_MADNESS -> preserved.withLitSuite(true)
        ProductionAnalysisSystem.SMT -> preserved.withSmtSuite(true)
        ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE -> preserved.copy(rsiOrderFlow = true)
        ProductionAnalysisSystem.PIVOT_SWEEP_DIVERGENCE -> preserved.copy(pivotSweepDivergence = true)
        null -> preserved
    }
}
