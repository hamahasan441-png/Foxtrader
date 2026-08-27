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
    // Label only. The enum constant, the toggle field and every persisted
    // preference keep their existing names: this study is an RSI + volume
    // delta oscillator, and the "Candle" name it shipped under now belongs
    // to the engine that actually draws RSI OHLC candles.
    RSI_ORDERFLOW_CANDLE("RSI Orderflow Divergence"),
    RSI_REVERSAL("RSI Orderflow Reversal"),
    LIQUIDITY_SWEEP("Liquidity Sweep"),
    VIRGIN_WICK("Virgin Wick"),
    PIVOT_SWEEP_DIVERGENCE("Pivot Sweep Divergence"),
    VALUE_AREA_LIQUIDITY_REJECTION("Value Area Liquidity Rejection"),

    // Apex runs the other members itself, so selecting it clears their own
    // chart toggles: what is left on the chart is the consensus, not the
    // consensus drawn on top of the votes it was built from.
    APEX("Apex Consensus"),
}

/** Resolve a single selected canonical engine when the state is unambiguous. */
fun IndicatorToggles.productionAnalysisSystem(): ProductionAnalysisSystem? {
    val selected = listOfNotNull(
        ProductionAnalysisSystem.LIT_ADVENTURE.takeIf { litX },
        ProductionAnalysisSystem.LIT_MAY_MADNESS.takeIf { lit },
        ProductionAnalysisSystem.SMT.takeIf { smt },
        ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE.takeIf { rsiOrderFlow },
        ProductionAnalysisSystem.RSI_REVERSAL.takeIf { rsiReversal },
        ProductionAnalysisSystem.LIQUIDITY_SWEEP.takeIf { liquiditySweep },
        ProductionAnalysisSystem.VIRGIN_WICK.takeIf { virginWick },
        ProductionAnalysisSystem.PIVOT_SWEEP_DIVERGENCE.takeIf { pivotSweepDivergence },
        ProductionAnalysisSystem.VALUE_AREA_LIQUIDITY_REJECTION.takeIf { valueAreaLiquidityRejection },
        ProductionAnalysisSystem.APEX.takeIf { apex },
    )
    return selected.singleOrNull()
}

/**
 * Switch the canonical primary engine without destroying unrelated studies.
 *
 * The legacy implementation rebuilt [IndicatorToggles] from scratch, which
 * silently disabled EMA/MACD/SMC/profile studies whenever the user changed the
 * analysis system. That made indicator availability depend on the active mode.
 * This implementation clears only the mutually-exclusive canonical engine
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
        rsiReversal = false,
        liquiditySweep = false,
        virginWick = false,
        pivotSweepDivergence = false,
        valueAreaLiquidityRejection = false,
        apex = false,
    )

    return when (system) {
        ProductionAnalysisSystem.LIT_ADVENTURE -> preserved.withLitXSuite(true)
        ProductionAnalysisSystem.LIT_MAY_MADNESS -> preserved.withLitSuite(true)
        ProductionAnalysisSystem.SMT -> preserved.withSmtSuite(true)
        ProductionAnalysisSystem.RSI_ORDERFLOW_CANDLE -> preserved.copy(rsiOrderFlow = true)
        ProductionAnalysisSystem.RSI_REVERSAL -> preserved.copy(rsiReversal = true)
        ProductionAnalysisSystem.LIQUIDITY_SWEEP -> preserved.copy(liquiditySweep = true)
        ProductionAnalysisSystem.VIRGIN_WICK -> preserved.copy(virginWick = true)
        ProductionAnalysisSystem.PIVOT_SWEEP_DIVERGENCE -> preserved.copy(pivotSweepDivergence = true)
        ProductionAnalysisSystem.VALUE_AREA_LIQUIDITY_REJECTION -> preserved.copy(valueAreaLiquidityRejection = true)
        ProductionAnalysisSystem.APEX -> preserved.copy(apex = true)
        null -> preserved
    }
}
