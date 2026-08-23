package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.signalintel.LitEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical registry for all built-in strategies.
 *
 * Strategy logic no longer lives as a collection of unrelated indicator
 * functions in this registry. Every built-in methodology is routed through one
 * [StrategyPackageEngine], which builds a causal package containing technicals,
 * market structure, the complete SMC detector bundle, session context and the
 * strategy-specific institutional/classical decision pipeline.
 *
 * This is the same [StrategyDefinition.function] consumed by live chart arrows,
 * Strategy Tester and backtests, so package analysis cannot silently diverge by
 * surface.
 */
@Singleton
class StrategyLibrary @Inject constructor(
    smcDetector: SmcDetector,
    analyzeStructure: AnalyzeMarketStructureUseCase,
    ichimokuCloud: IchimokuCloud,
    litXEngine: LitXEngine,
    // Default preserves direct-construction unit tests/source compatibility;
    // Hilt still injects the production singleton in the app.
    litEngine: LitEngine = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = com.foxtrader.app.domain.usecase.litx.DisplacementDetector(),
        premiumDiscount = com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator(),
    ),
) {
    private val packageEngine = StrategyPackageEngine(
        smcDetector = smcDetector,
        analyzeStructure = analyzeStructure,
        ichimokuCloud = ichimokuCloud,
        litXEngine = litXEngine,
        litEngine = litEngine,
    )

    /** Registry of all available strategies by type. */
    fun all(
        symbol: String = "",
        timeframe: Timeframe = Timeframe.H1,
    ): Map<StrategyType, StrategyDefinition> = StrategyType.entries.associateWith { type ->
        definition(type, symbol, timeframe)
    }

    /** Resolve one complete strategy package. */
    fun get(
        type: StrategyType,
        symbol: String = "",
        timeframe: Timeframe = Timeframe.H1,
    ): StrategyDefinition = definition(type, symbol, timeframe)

    /**
     * Full diagnostic package for callers that need more than the executable
     * signal (evidence, SMC detections, structure, sessions and technical state).
     */
    fun analyze(
        type: StrategyType,
        symbol: String,
        timeframe: Timeframe,
        candles: List<com.foxtrader.app.domain.model.Candle>,
        index: Int,
    ): StrategyPackageEngine.Analysis = packageEngine.analyze(
        type = type,
        symbol = symbol,
        timeframe = timeframe,
        candles = candles,
        index = index,
    )

    private fun definition(
        type: StrategyType,
        symbol: String,
        timeframe: Timeframe,
    ): StrategyDefinition {
        val meta = metadata(type)
        return StrategyDefinition(
            name = meta.name,
            type = type,
            description = meta.description,
            minimumBars = meta.minimumBars,
            function = packageEngine.function(
                type = type,
                symbol = symbol,
                timeframe = timeframe,
                minimumBars = meta.minimumBars,
            ),
        )
    }

    private data class Metadata(
        val name: String,
        val description: String,
        val minimumBars: Int,
    )

    private fun metadata(type: StrategyType): Metadata = when (type) {
        StrategyType.SMART_MONEY -> Metadata(
            name = "SMC Institutional Package",
            description = "Complete SMC package: structure, OB/FVG/liquidity/breaker/IFVG/BPR context with confirmed order-block execution.",
            minimumBars = 80,
        )
        StrategyType.LIT -> Metadata(
            name = "LIT Institutional Package",
            description = "LiT package: liquidity sweep → confirmed CHOCH/MSS → displacement → first POI retest, enriched by shared market context.",
            minimumBars = 60,
        )
        StrategyType.LITX -> Metadata(
            name = "LIT X Institutional Package",
            description = "LiTX package: institutional pipeline plus full shared structure/SMC/technical/session diagnostics.",
            minimumBars = 60,
        )
        StrategyType.TREND_FOLLOWING -> Metadata(
            name = "Trend Analysis Package",
            description = "Trend package: EMA event, ADX/DI strength, structure, momentum context, SMC zones and sessions in one analysis.",
            minimumBars = 60,
        )
        StrategyType.MEAN_REVERSION -> Metadata(
            name = "Mean Reversion Package",
            description = "Mean-reversion package: RSI reclaim, EMA mean, volatility, structure and institutional zones evaluated together.",
            minimumBars = 50,
        )
        StrategyType.BREAKOUT -> Metadata(
            name = "Structure Breakout Package",
            description = "Breakout package: confirmed BOS, swing risk, liquidity/SMC context, trend strength and session state.",
            minimumBars = 60,
        )
        StrategyType.ICHIMOKU -> Metadata(
            name = "Ichimoku Market Package",
            description = "Ichimoku package: Kumo/TK execution enriched with structure, SMC, volatility, volume and session context.",
            minimumBars = 60,
        )
        StrategyType.CONFLUENCE -> Metadata(
            name = "Full Confluence Package",
            description = "Full-market package combining technical state, confirmed structure and institutional SMC detections without double-counting separate UI indicators.",
            minimumBars = 60,
        )
        StrategyType.PATTERN -> Metadata(
            name = "Institutional Pattern Package",
            description = "Pattern package: FVG execution with structure, liquidity, OB/breaker/IFVG/BPR and technical market context.",
            minimumBars = 60,
        )
    }
}
