package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartBarMode

/**
 * Runtime-readiness metadata for chart studies.
 *
 * A large class of "indicator does nothing" reports are not engine failures at
 * all: the study was enabled before enough bars were loaded, or on a synthetic
 * bar mode (Renko) that intentionally disables time/index-sensitive SMC logic.
 */
enum class ChartStudyId(
    val label: String,
    val minimumBars: Int,
    val requiresTimeAxis: Boolean = false,
    val contextHint: String? = null,
) {
    EMA("EMA 20 / 50", 50),
    SUPER_TREND("SuperTrend", 15),
    ICHIMOKU("Ichimoku", 52),
    PARABOLIC_SAR("Parabolic SAR", 2),
    RSI("RSI", 15),
    RSI_ORDER_FLOW("RSI OrderFlow", 25, contextHint = "OHLCV delta/CVD proxy"),
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
 * Primary-chart runtime snapshot consumed by the indicator command center.
 * Volatile primitives avoid Compose snapshot writes from the computation path;
 * ChartScreen state updates already trigger the recomposition that reads them.
 */
object ChartIndicatorRuntime {
    @Volatile
    var candleCount: Int = 0
        private set

    @Volatile
    var barMode: ChartBarMode = ChartBarMode.TIME
        private set

    fun publish(candleCount: Int, barMode: ChartBarMode) {
        this.candleCount = candleCount.coerceAtLeast(0)
        this.barMode = barMode
    }
}

object IndicatorReadinessCatalog {
    fun status(
        study: ChartStudyId,
        candleCount: Int,
        barMode: ChartBarMode,
    ): IndicatorReadiness {
        if (study.requiresTimeAxis && !barMode.preservesTimeAxis) {
            return IndicatorReadiness(
                level = IndicatorReadinessLevel.INCOMPATIBLE,
                label = "Time bars only",
            )
        }
        val missing = (study.minimumBars - candleCount).coerceAtLeast(0)
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
}
