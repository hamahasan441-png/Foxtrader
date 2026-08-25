package com.foxtrader.app.feature.chart.presentation

/**
 * The only analysis systems exposed by the production FOXTRADER chart.
 *
 * Existing engine names remain internal implementation details while the UI and
 * execution boundary use the canonical product names defined here.
 */
enum class ProductionAnalysisSystem(val label: String) {
    LIT_ADVENTURE("LiT Adventure"),
    LIT_MAY_MADNESS("LiT May Madness"),
    SMT("SMT"),
    RSI_ORDERFLOW_CANDLE("RSI Orderflow Candle"),
    PIVOT_SWEEP_DIVERGENCE("Pivot Sweep Divergence"),
}

/**
 * Resolve the currently selected production system.
 *
 * Legacy builds allowed multiple unrelated studies to be enabled at once. If a
 * legacy state contains more than one approved primary engine, report no single
 * selection instead of inventing an ordering.
 */
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
 * Replace the public analysis selection atomically.
 *
 * LiT Adventure is backed by the repository's existing LiTX engine and LiT May
 * Madness by the existing confirmed-bar LiT Pro engine. Their lower-level SMC
 * primitives stay enabled internally where those engines require them, but they
 * are not separate selectable systems. SMT similarly keeps its structure and
 * liquidity prerequisites internal.
 *
 * Starting from a fresh [IndicatorToggles] also clears all legacy public studies,
 * arbitrary strategy selections and binary/TradePro/SMS toggles so stale state
 * cannot silently run a fifth system behind the production selector.
 */
fun IndicatorToggles.withProductionAnalysisSystem(
    system: ProductionAnalysisSystem?,
): IndicatorToggles {
    val clean = IndicatorToggles(
        smcVisualMode = smcVisualMode,
        settings = settings,
    )

    return when (system) {
        ProductionAnalysisSystem.LIT_ADVENTURE -> clean.withLitXSuite(true)
        ProductionAnalysisSystem.LIT_MAY_MADNESS -> clean.withLitSuite(true)
        ProductionAnalysisSystem.SMT -> clean.withSmtSuite(true)
        ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE -> clean.copy(rsiOrderFlow = true)
        ProductionAnalysisSystem.PIVOT_SWEEP_DIVERGENCE -> clean.copy(pivotSweepDivergence = true)
        null -> clean
    }
}
