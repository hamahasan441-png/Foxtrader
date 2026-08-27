package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.apex.ApexEngine
import com.foxtrader.app.domain.usecase.compass.CompassEngine
import com.foxtrader.app.domain.usecase.crucible.CrucibleEngine
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import com.foxtrader.app.domain.usecase.rsireversal.RsiReversalEngine
import kotlin.math.max

/** Runtime-readiness metadata for chart studies. */
enum class ChartStudyId(
    val label: String,
    val minimumBars: Int,
    val requiresTimeAxis: Boolean = false,
    val contextHint: String? = null,
) {
    EMA("EMA", 50),
    SUPER_TREND("SuperTrend", 15),
    ICHIMOKU("Ichimoku", 52),
    PARABOLIC_SAR("Parabolic SAR", 2),
    RSI("RSI", 15),
    RSI_ORDER_FLOW("RSI OrderFlow", 25, contextHint = "OHLCV delta/CVD proxy"),
    RSI_REVERSAL(
        "RSI Orderflow Reversal",
        220,
        requiresTimeAxis = true,
        contextHint = "arms on this timeframe, confirms one below",
    ),
    LIQUIDITY_SWEEP(
        "Liquidity Sweep",
        120,
        requiresTimeAxis = true,
        contextHint = "needs two closed timeframes above the chart",
    ),
    VIRGIN_WICK(
        "Virgin Wick",
        200,
        requiresTimeAxis = true,
        contextHint = "needs a closed context timeframe above the chart",
    ),
    PIVOT_SWEEP_DIVERGENCE(
        "Pivot Sweep Divergence",
        40,
        requiresTimeAxis = true,
        contextHint = "needs a completed prior trading day",
    ),
    VALUE_AREA_LIQUIDITY_REJECTION(
        "Value Area Liquidity Rejection",
        64,
        requiresTimeAxis = true,
        contextHint = "needs a completed prior session",
    ),
    AMD(
        "AMD (Accumulation/Manipulation/Distribution)",
        48,
        contextHint = "works on every timeframe",
    ),
    NASCENT(
        "Nascent FX Primary Analysis",
        120,
        requiresTimeAxis = true,
        contextHint = "needs completed external-timeframe structure",
    ),
    APEX(
        "Apex Consensus",
        ApexEngine.MIN_BARS,
        requiresTimeAxis = true,
        // Having enough bars to run is not the same as having enough record to
        // publish. Apex stays silent until enough of its own trades have
        // resolved to measure the hit rate being asked of it, which normally
        // takes far more history than this minimum.
        contextHint = "stays silent until its own record is measured",
    ),
    COMPASS(
        "Compass Accuracy",
        CompassEngine.MIN_BARS,
        requiresTimeAxis = true,
        // Bars enough to run is not bars enough to justify a threshold: the
        // calibration needs calls that have already been proved right or wrong.
        contextHint = "needs resolved calls before it will publish any",
    ),
    CRUCIBLE(
        "Crucible Discovery",
        CrucibleEngine.MIN_BARS,
        requiresTimeAxis = true,
        // A full rule search is the heaviest study here by a wide margin, and
        // it needs a long series before its folds are big enough to mean
        // anything. It is worth switching on deliberately, not by default.
        contextHint = "searches thousands of rules; slow, and needs long history",
    ),
    MACD("MACD", 35),
    STOCHASTIC("Stochastic", 15),
    BOLLINGER("Bollinger", 20),
    KELTNER("Keltner", 20),
    DONCHIAN("Donchian", 20),
    VOLUME("Volume", 1),
    VWAP("VWAP", 1),
    ANCHORED_VWAP("Anchored VWAP", 20),
    OBV("OBV", 2),
    MFI("MFI", 15),
    VOLUME_PROFILE("Volume Profile", 20),
    MARKET_PROFILE("Market Profile", 30),
    STRUCTURE("Structure", 11, requiresTimeAxis = true),
    SUPPORT_RESISTANCE("Support / Resistance", 25),
    FIBONACCI("Auto Fibonacci", 30, requiresTimeAxis = true),
    SESSIONS("Sessions", 1, requiresTimeAxis = true),
    PIVOTS("Daily Pivots", 2, requiresTimeAxis = true, contextHint = "needs two UTC trading days"),
    CONFLUENCE("MTF Confluence", 50, requiresTimeAxis = true, contextHint = "HTF context strengthens the read"),
    ORDER_BLOCKS("Order Blocks", 5, requiresTimeAxis = true),
    FAIR_VALUE_GAPS("Fair Value Gaps", 3, requiresTimeAxis = true),
    LIQUIDITY("Liquidity", 10, requiresTimeAxis = true),
    LITX("LiTX", 50, requiresTimeAxis = true),
    LIT("LiT", 60, requiresTimeAxis = true),
    SMS("SMS", 40, requiresTimeAxis = true),
    SMT("SMT", 40, requiresTimeAxis = true, contextHint = "needs a correlated peer feed"),
    TRADE_PRO("TradePro", 30, requiresTimeAxis = true),
    BINARY_3M("Deriv 3m", 80, requiresTimeAxis = true, contextHint = "Deriv + M1 only"),
}

enum class IndicatorReadinessLevel { READY, WARMING, INCOMPATIBLE }

data class IndicatorReadiness(
    val level: IndicatorReadinessLevel,
    val label: String,
    val missingBars: Int = 0,
)

/**
 * Small read-only runtime snapshot used by the floating indicator command center.
 * Settings are published alongside bar count/mode so legacy readiness calls that
 * do not pass settings explicitly still describe the values actually being used
 * by the calculation pipeline.
 */
object ChartIndicatorRuntime {
    @Volatile
    var candleCount: Int = 0
        private set

    @Volatile
    var barMode: ChartBarMode = ChartBarMode.TIME
        private set

    @Volatile
    var settings: ChartStudySettings = ChartStudySettings()
        private set

    fun publish(candleCount: Int, barMode: ChartBarMode) {
        this.candleCount = candleCount.coerceAtLeast(0)
        this.barMode = barMode
    }

    fun publishSettings(settings: ChartStudySettings) {
        this.settings = settings.sanitized()
    }
}

object IndicatorReadinessCatalog {
    fun status(
        study: ChartStudyId,
        candleCount: Int,
        barMode: ChartBarMode,
        settings: ChartStudySettings = ChartIndicatorRuntime.settings,
    ): IndicatorReadiness {
        if (study.requiresTimeAxis && !barMode.preservesTimeAxis) {
            return IndicatorReadiness(
                level = IndicatorReadinessLevel.INCOMPATIBLE,
                label = "Time bars only",
            )
        }

        val cfg = settings.sanitized()
        val required = requiredBars(study, cfg)
        val missing = (required - candleCount).coerceAtLeast(0)
        if (missing > 0) {
            return IndicatorReadiness(
                level = IndicatorReadinessLevel.WARMING,
                label = "Need $missing more",
                missingBars = missing,
            )
        }
        return IndicatorReadiness(
            level = IndicatorReadinessLevel.READY,
            label = study.contextHint?.let { "Ready · $it" } ?: "Ready",
        )
    }

    fun requiredBars(study: ChartStudyId, settings: ChartStudySettings): Int = when (study) {
        ChartStudyId.EMA -> settings.ema.slowPeriod
        ChartStudyId.SUPER_TREND -> settings.superTrend.atrPeriod + 2
        ChartStudyId.ICHIMOKU -> maxOf(
            settings.ichimoku.tenkanPeriod,
            settings.ichimoku.kijunPeriod,
            settings.ichimoku.senkouBPeriod,
        )
        ChartStudyId.PARABOLIC_SAR -> 2
        ChartStudyId.RSI -> settings.rsi.period + 1
        ChartStudyId.RSI_ORDER_FLOW ->
            max(settings.rsiOrderFlow.rsiPeriod, settings.rsiOrderFlow.flowPeriod) +
                settings.rsiOrderFlow.pivotLeft + settings.rsiOrderFlow.pivotRight + 1
        ChartStudyId.RSI_REVERSAL ->
            // The warmup exclusion dominates: below it the engine is correctly
            // silent rather than publishing decisions a scroll-back could flip.
            RsiReversalEngine().minimumBars(settings.rsiReversal.toEngineConfig())
        ChartStudyId.LIQUIDITY_SWEEP ->
            LiquiditySweepEngine(AnalyzeMarketStructureUseCase())
                .minimumBars(settings.liquiditySweep.toEngineConfig())
        ChartStudyId.VIRGIN_WICK ->
            VirginWickEngine(SmcDetector()).minimumBars(settings.virginWick.toEngineConfig())
        ChartStudyId.PIVOT_SWEEP_DIVERGENCE ->
            maxOf(
                settings.pivotSweepDivergence.rsiPeriod,
                settings.pivotSweepDivergence.flowPeriod,
                settings.pivotSweepDivergence.atrPeriod,
            ) + settings.pivotSweepDivergence.pivotLeft +
                settings.pivotSweepDivergence.pivotRight +
                settings.pivotSweepDivergence.minPivotSeparation + 1
        ChartStudyId.VALUE_AREA_LIQUIDITY_REJECTION ->
            maxOf(
                settings.valueAreaLiquidityRejection.minPreviousSessionBars * 2,
                settings.valueAreaLiquidityRejection.atrPeriod +
                    settings.valueAreaLiquidityRejection.liquidityLookback +
                    settings.valueAreaLiquidityRejection.swingRight + 1,
            )
        ChartStudyId.AMD -> maxOf(settings.amd.atrPeriod, settings.amd.minAccumulationBars) + 1 +
            settings.amd.maxReclaimBars + settings.amd.maxConfirmBars
        ChartStudyId.NASCENT ->
            // Enough internal bars for the external resample to carry confirmed
            // structure of its own; below this the engine is correctly silent.
            settings.nascent.sanitized().liveWindowBars.coerceAtLeast(120)
        ChartStudyId.MACD -> max(settings.macd.fastPeriod, settings.macd.slowPeriod) + settings.macd.signalPeriod
        ChartStudyId.STOCHASTIC -> max(settings.stochastic.kPeriod, settings.stochastic.dPeriod) + 1
        ChartStudyId.BOLLINGER -> settings.bollinger.period
        ChartStudyId.KELTNER -> max(settings.keltner.emaPeriod, settings.keltner.atrPeriod) + 1
        ChartStudyId.DONCHIAN -> settings.donchian.period
        ChartStudyId.MFI -> settings.mfi.period + 1
        else -> study.minimumBars
    }.coerceAtLeast(1)
}
