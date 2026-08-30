package com.foxtrader.app.domain.usecase.keystone.model

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.keystone.KeystoneLiquiditySource
import com.foxtrader.app.domain.usecase.keystone.KeystoneSession

/** A peer series and how it is expected to move against the primary. */
data class KeystonePeerSeries(
    val symbol: String,
    val candles: List<com.foxtrader.app.domain.model.Candle>,
    val polarity: KeystonePolarity,
)

/**
 * Whether a peer normally moves with the primary or against it.
 *
 * The divergence test is the same shape either way — the peer failing to
 * confirm what the primary just did — but for an inverse peer "confirming"
 * means making the opposite extreme. Treating XAUUSD/DXY as a positive pair
 * would report a divergence on every ordinary bar and none of the real ones.
 */
enum class KeystonePolarity { POSITIVE, INVERSE }

/** A shelf of resting liquidity, and where it came from. */
data class KeystonePool(
    val source: KeystoneLiquiditySource,
    val price: Double,
    /** True when the pool sits above the market (buy-side liquidity). */
    val aboveMarket: Boolean,
    /** Bar at which the pool became knowable. */
    val formedIndex: Int,
    val label: String,
)

/** A confirmed sweep of a pool: taken, then rejected on the same closed bar. */
data class KeystoneSweep(
    val pool: KeystonePool,
    /** Bar whose wick took the pool and whose close rejected it. */
    val index: Int,
    val timestamp: Long,
    /** The extreme the sweep reached — where the stop will sit behind. */
    val extreme: Double,
    /** Direction the sweep implies: a high swept points down. */
    val direction: Direction,
    /** How far beyond the pool price traded, in ATR multiples. */
    val penetrationAtr: Double,
)

/** A divergence between the primary series and one correlated market. */
data class KeystoneDivergence(
    val peerSymbol: String,
    val polarity: KeystonePolarity,
    val direction: Direction,
    /** Primary bar at which both legs were confirmed. */
    val confirmationIndex: Int,
    val correlation: Double,
    /** Separation between the two legs, in average-range units. */
    val strength: Double,
    val detail: String,
)

/** The closed candle that broke internal structure after the sweep. */
data class KeystoneDisplacement(
    val index: Int,
    val direction: Direction,
    val startPrice: Double,
    val endPrice: Double,
    val bodyToRangeRatio: Double,
    /** The candle's full range in ATR multiples — like measured against like. */
    val atrMultiple: Double,
    /** The fair-value gap the impulse left, when it left one. */
    val fairValueGap: KeystoneGap?,
    /** The internal swing the close took out, when [KeystoneConfig.requireInternalBreak]. */
    val brokenStructureLevel: Double?,
)

/** A three-candle fair value gap. */
data class KeystoneGap(val low: Double, val high: Double) {
    fun contains(price: Double): Boolean = price in low..high
    val midpoint: Double get() = (low + high) / 2.0
}

/** The directional read in force when a sweep confirmed. */
data class KeystoneBiasRead(
    val bias: Bias,
    val higherTimeframe: Timeframe,
    val midTimeframe: Timeframe,
    val sessionDirection: Direction?,
    val reason: String,
)

/** Why a candidate was refused. Kept so the engine can say what it stood down on. */
enum class KeystoneRejection(val label: String) {
    NO_BIAS("No confirmed higher-timeframe bias"),
    AGAINST_BIAS("Sweep opposes the higher-timeframe bias"),
    AGAINST_SESSION("Sweep opposes the session direction"),
    NO_SMT("No divergence against a correlated market"),
    NO_DISPLACEMENT("No closed displacement candle broke structure"),
    NO_ENTRY("Price never returned to the entry zone"),
    GEOMETRY("Stop and target could not reach the reward floor"),
    OUT_OF_SESSION("Outside the permitted sessions"),
    SPREAD("Spread too large against the trade's risk"),
    NEWS("Inside a scheduled release window"),
    VOLATILITY("Volatility below the floor"),
    DUPLICATE_EVENT("Liquidity event already traded"),
    DAILY_LOSS_LIMIT("Daily loss limit reached"),
    DAILY_SIGNAL_LIMIT("Daily signal limit reached"),
}

/** A complete, confirmed Keystone setup. */
data class KeystoneSignal(
    val symbol: String,
    val timeframe: Timeframe,
    val direction: Direction,
    /** Bar the entry filled — the bar this signal may first be drawn on. */
    val index: Int,
    val timestamp: Long,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val rewardMultiple: Double,
    /** Fraction of the account risked, from [KeystoneConfig.riskPercent]. */
    val riskPercent: Double,
    val sweep: KeystoneSweep,
    val biasRead: KeystoneBiasRead,
    val divergence: KeystoneDivergence?,
    val displacement: KeystoneDisplacement,
    val session: KeystoneSession?,
    val entryFromGap: Boolean,
    val reasons: List<String>,
)

/** What happened to a signal once the market resolved it. */
enum class KeystoneOutcome { WIN, LOSS, BREAKEVEN, EXPIRED, OPEN }

/** A signal carried forward to its resolution, net of costs. */
data class KeystoneTrade(
    val signal: KeystoneSignal,
    val outcome: KeystoneOutcome,
    val exitIndex: Int,
    val exitPrice: Double,
    /** Result in R, after spread, commission, slippage and latency. */
    val rMultiple: Double,
    val holdingBars: Int,
)

/** Expectancy, profit factor and drawdown over a set of trades. */
data class KeystonePerformance(
    val trades: Int,
    /**
     * Share of trades that made money.
     *
     * Reported because it is asked for, and deliberately not part of any
     * acceptance test. A model can be right most of the time and still lose,
     * and this one is built to be wrong often and profitable anyway.
     */
    val winRate: Double,
    /** Mean result per trade, in R. This is the number that decides. */
    val expectancyR: Double,
    val profitFactor: Double,
    val maxDrawdownR: Double,
    val totalR: Double,
    /** Standard deviation of per-trade R, used by the Sharpe figures. */
    val standardDeviationR: Double,
) {
    companion object {
        val EMPTY = KeystonePerformance(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/** What the out-of-sample and robustness work found. */
data class KeystoneValidationReport(
    val inSample: KeystonePerformance,
    val outOfSample: KeystonePerformance,
    /** Per-fold walk-forward expectancy, oldest fold first. */
    val walkForwardExpectancyR: List<Double>,
    /** Folds whose out-of-sample expectancy was positive. */
    val positiveFolds: Int,
    /** 95th-percentile drawdown across reordered equity curves, in R. */
    val monteCarloDrawdownR95: Double,
    /** Share of Monte Carlo reorderings that ended below zero. */
    val monteCarloLossProbability: Double,
    /** Probability of backtest overfitting, from combinatorially symmetric CV. */
    val overfittingProbability: Double,
    /** Sharpe ratio deflated for the number of configurations considered. */
    val deflatedSharpe: Double,
    val costsApplied: KeystoneCosts,
    val notes: List<String>,
) {
    companion object {
        val EMPTY = KeystoneValidationReport(
            inSample = KeystonePerformance.EMPTY,
            outOfSample = KeystonePerformance.EMPTY,
            walkForwardExpectancyR = emptyList(),
            positiveFolds = 0,
            monteCarloDrawdownR95 = 0.0,
            monteCarloLossProbability = 0.0,
            overfittingProbability = 1.0,
            deflatedSharpe = 0.0,
            costsApplied = KeystoneCosts(0.0, 0.0, 0.0, 0),
            notes = emptyList(),
        )
    }
}

/** The costs every reported figure was measured net of. */
data class KeystoneCosts(
    val spreadFraction: Double,
    val commissionFraction: Double,
    val slippageFraction: Double,
    val latencyBars: Int,
)

/** The verdict, and every reason it was or was not reached. */
data class KeystoneAcceptance(
    val accepted: Boolean,
    val expectancyPassed: Boolean,
    val profitFactorPassed: Boolean,
    val drawdownPassed: Boolean,
    val samplePassed: Boolean,
    val stabilityPassed: Boolean,
    val summary: String,
) {
    companion object {
        val UNMEASURED = KeystoneAcceptance(
            accepted = false,
            expectancyPassed = false,
            profitFactorPassed = false,
            drawdownPassed = false,
            samplePassed = false,
            stabilityPassed = false,
            summary = "Not enough resolved trades to judge.",
        )
    }
}

/** Everything one Keystone run produced. */
data class KeystoneAnalysis(
    val signals: List<KeystoneSignal>,
    val trades: List<KeystoneTrade>,
    val pools: List<KeystonePool>,
    val sweeps: List<KeystoneSweep>,
    val performance: KeystonePerformance,
    val validation: KeystoneValidationReport,
    val acceptance: KeystoneAcceptance,
    /** Count of each reason a candidate was refused. */
    val rejections: Map<KeystoneRejection, Int>,
    val peersUsed: List<String>,
    val note: String?,
) {
    companion object {
        fun empty(note: String): KeystoneAnalysis = KeystoneAnalysis(
            signals = emptyList(),
            trades = emptyList(),
            pools = emptyList(),
            sweeps = emptyList(),
            performance = KeystonePerformance.EMPTY,
            validation = KeystoneValidationReport.EMPTY,
            acceptance = KeystoneAcceptance.UNMEASURED,
            rejections = emptyMap(),
            peersUsed = emptyList(),
            note = note,
        )
    }
}
