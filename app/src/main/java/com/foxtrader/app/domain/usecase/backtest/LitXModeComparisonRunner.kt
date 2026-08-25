package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import javax.inject.Inject
import kotlinx.coroutines.yield

/**
 * Runs the same real candle history through every LiT Adventure mode and reports
 * them side by side.
 *
 * ## Why this exists
 *
 * Four modes were added without any way to tell which one is better. Waiting for
 * live signals to accumulate would take weeks per mode — and SNIPER, by design,
 * would take longest. This replays recorded candles bar by bar instead, so the
 * comparison is available immediately from history you already have.
 *
 * It answers one question and no more: **on this data, which rule set actually
 * performed?** It does not tune anything, and deliberately holds every threshold
 * constant across modes so a difference is attributable to the rule set rather
 * than to a preset.
 *
 * ## Replay fidelity
 *
 * The strategy function hands the engine a trailing candle prefix and accepts the
 * result only when `confirmationIndex == index` — i.e. only a signal the engine
 * would have emitted live, on that bar, with no later bar visible. This is the
 * same right-edge contract `LitPrefixNonRepaintTest` pins, so a backtest here
 * cannot report a trade that live trading could not have taken.
 *
 * Execution, spread, commission and slippage are [BacktestEngine]'s
 * responsibility and are shared identically across modes.
 *
 * ## The trailing window, and what it costs
 *
 * `LitXEngine.analyze` is O(window), so evaluating every bar of a full history
 * from index 0 would be quadratic and unusable on a phone. A trailing window of
 * [DEFAULT_ANALYSIS_WINDOW] bars bounds it to linear.
 *
 * This is not free and should not be presented as such: an engine that can see
 * 240 bars will not always agree with one that can see 5,000. The choice is
 * defensible because it *matches live behaviour* — the chart pipeline also feeds
 * the engine a bounded display window, so a full-history backtest would be
 * measuring a configuration that never actually trades. Keep this window aligned
 * with the chart's if you change either.
 */
class LitXModeComparisonRunner @Inject constructor(
    private val litXEngine: LitXEngine,
    private val backtestEngine: BacktestEngine,
    private val analyticsEngine: BacktestAnalyticsEngine,
) {

    /**
     * One mode's outcome. [analytics] carries the walk-forward and Monte Carlo
     * validation so a mode cannot look good on an in-sample curve alone.
     */
    data class ModeOutcome(
        val mode: LitXMode,
        val result: BacktestResult,
        val analytics: BacktestAnalyticsReport,
    ) {
        val trades: Int get() = result.metrics.totalTrades

        /**
         * Whether this mode produced enough trades for its metrics to carry
         * meaning. Mirrors the live-signal gate in
         * `SignalOutcomeEvaluator.MIN_RESOLVED_FOR_RATE`, deliberately: the same
         * standard should apply whether the evidence came from replay or from
         * live trading.
         */
        val sampleIsAdequate: Boolean get() = trades >= MIN_TRADES_FOR_COMPARISON
    }

    /**
     * @param comparison every mode's outcome, ordered as [LitXMode] declares.
     * @param ranked outcomes with an adequate sample, best profit factor first.
     *   Modes below the sample bar are excluded from ranking rather than ranked
     *   on thin evidence — a mode with three lucky trades must not top the table.
     * @param inadequateSample modes that did not trade enough to be judged.
     */
    data class ComparisonReport(
        val symbol: String,
        val timeframe: Timeframe,
        val barsAnalyzed: Int,
        val comparison: List<ModeOutcome>,
        val ranked: List<ModeOutcome>,
        val inadequateSample: List<LitXMode>,
    ) {
        /** Best mode on this data, or null when nothing cleared the sample bar. */
        val winner: LitXMode? get() = ranked.firstOrNull()?.mode

        /**
         * True when no mode produced a judgeable sample. Callers should say so
         * plainly rather than showing the least-bad row as a recommendation.
         */
        val inconclusive: Boolean get() = ranked.isEmpty()
    }

    /**
     * @param baseConfig thresholds held constant across modes; only
     *   [LitXConfig.mode] varies, so differences are attributable to the rule
     *   set. Pass a config you would actually trade.
     * @param onProgress invoked after each mode completes, for UI feedback on a
     *   run that takes seconds rather than milliseconds.
     */
    suspend operator fun invoke(
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe,
        backtestConfig: BacktestConfig = BacktestConfig(),
        baseConfig: LitXConfig = LitXConfig.preset(SignalProfile.INTRADAY),
        modes: List<LitXMode> = LitXMode.entries,
        analysisWindow: Int = DEFAULT_ANALYSIS_WINDOW,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ComparisonReport {
        require(analysisWindow >= MIN_ANALYSIS_WINDOW) {
            "analysisWindow must be at least $MIN_ANALYSIS_WINDOW bars for the engine to confirm a setup"
        }

        val outcomes = mutableListOf<ModeOutcome>()
        modes.forEachIndexed { position, mode ->
            // Cooperative cancellation: a full sweep is four backtests over the
            // whole history, so the user must be able to leave the screen
            // without stranding them. yield() both checks for cancellation and
            // releases the dispatcher between modes.
            yield()

            backtestEngine.updateConfig(backtestConfig)
            val result = backtestEngine(
                candles = candles,
                strategy = strategyFor(
                    mode = mode,
                    baseConfig = baseConfig,
                    symbol = symbol,
                    timeframe = timeframe,
                    analysisWindow = analysisWindow,
                ),
                symbol = symbol,
                timeframe = timeframe,
            )
            outcomes += ModeOutcome(
                mode = mode,
                result = result,
                analytics = analyticsEngine.analyze(result),
            )
            onProgress(position + 1, modes.size)
        }

        val adequate = outcomes.filter { it.sampleIsAdequate }
        return ComparisonReport(
            symbol = symbol,
            timeframe = timeframe,
            barsAnalyzed = candles.size,
            comparison = outcomes,
            ranked = adequate.sortedWith(
                compareByDescending<ModeOutcome> { it.result.metrics.profitFactor }
                    .thenByDescending { it.result.metrics.expectancy }
                    .thenBy { it.mode.ordinal },
            ),
            inadequateSample = outcomes.filterNot { it.sampleIsAdequate }.map { it.mode },
        )
    }

    /**
     * Bar-by-bar strategy for one mode. Accepts a signal only when the engine
     * confirmed it on the bar being evaluated, which is what makes this a replay
     * of live behaviour rather than a scan of history.
     */
    private fun strategyFor(
        mode: LitXMode,
        baseConfig: LitXConfig,
        symbol: String,
        timeframe: Timeframe,
        analysisWindow: Int,
    ): StrategyFunction {
        val config = baseConfig.copy(mode = mode).sanitized()
        return { candles, index ->
            if (index < MIN_ANALYSIS_WINDOW || index >= candles.size) {
                null
            } else {
                val start = (index - analysisWindow + 1).coerceAtLeast(0)
                val window = candles.subList(start, index + 1)
                val signal = litXEngine.analyze(symbol, timeframe, window, config).signal
                // window.lastIndex is the local index of `index`; anything else
                // would be a signal about an earlier bar and must be ignored.
                if (signal != null && signal.confirmationIndex == window.lastIndex) {
                    StrategySignal(
                        index = index,
                        timestamp = candles[index].timestamp,
                        direction = signal.direction,
                        entry = signal.entry,
                        stopLoss = signal.stopLoss,
                        takeProfit = signal.takeProfit1,
                        confidence = signal.confidence.score,
                        setupType = "LiT Adventure ${mode.label}",
                    )
                } else {
                    null
                }
            }
        }
    }

    companion object {
        /**
         * Trailing bars visible to the engine on each evaluation. Chosen to
         * match the chart's working window; see the class docs for the
         * trade-off this represents.
         */
        const val DEFAULT_ANALYSIS_WINDOW = 240

        /** Below this the engine cannot complete a sweep -> shift -> retest sequence. */
        const val MIN_ANALYSIS_WINDOW = 60

        /**
         * Trades required before a mode is ranked. Matches the live-signal gate
         * so replay evidence and live evidence are held to one standard.
         */
        const val MIN_TRADES_FOR_COMPARISON = 20
    }
}
