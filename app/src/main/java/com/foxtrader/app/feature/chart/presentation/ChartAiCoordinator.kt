package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.data.alerts.AlertDispatcher
import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.usecase.ai.MarketExplanationEngine
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the AI multi-agent reasoning pipeline and decision engine.
 *
 * Guards against redundant runs via a lightweight fingerprint of the candle series.
 * This is a plain class instantiated by [ChartViewModel].
 */
internal class ChartAiCoordinator(
    private val orchestrator: AgentOrchestrator,
    private val decisionEngine: MasterDecisionEngine,
    private val mtfContextProvider: MtfContextProvider,
    private val marketExplanationEngine: MarketExplanationEngine,
    private val confluenceEngine: ConfluenceEngine,
    private val aiAlertService: AiAlertService,
    private val alertDispatcher: AlertDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {

    /**
     * Fingerprint of the last candle series passed to the AI pipeline.
     * Used to skip re-running the expensive multi-agent analysis when the
     * data has not changed (e.g. rapid indicator-toggle recomputations).
     */
    var lastAiCandlesHash: Long = 0L

    /** Handle to the currently running AI coroutine, cancelled on each new invocation. */
    private var inFlightJob: Job? = null

    /**
     * Result callback invoked on the main thread after AI completes.
     */
    data class AiResult(
        val decision: DecisionResult?,
        val marketExplanation: MarketExplanation?,
        val confluence: ConfluenceEngine.ConfluenceResult?,
    )

    /**
     * Run the multi-agent reasoning pipeline and return the result via callback.
     *
     * Guards:
     * - Requires at least 50 candles (insufficient data clears the decision).
     * - Skips re-running if the candle series has not changed since the last
     *   analysis (change detected via a lightweight O(1) fingerprint).
     * - The orchestrator and decision engine run on [defaultDispatcher] to avoid
     *   blocking the UI.
     */
    fun runAiDecision(
        candles: List<Candle>,
        dataSource: CandleSource,
        symbol: String,
        timeframe: Timeframe,
        confluenceEnabled: Boolean,
        symbolFlow: () -> String,
        timeframeFlow: () -> Timeframe,
        onResult: (AiResult) -> Unit,
    ) {
        if (candles.size < 50) {
            lastAiCandlesHash = 0L
            onResult(AiResult(decision = null, marketExplanation = null, confluence = null))
            return
        }

        val hash = computeFingerprint(candles)
        if (hash == lastAiCandlesHash) return
        lastAiCandlesHash = hash

        inFlightJob?.cancel()
        inFlightJob = scope.launch {
            val mtfCandles = mtfContextProvider.getHtfContext(
                symbol = symbol,
                executionTimeframe = timeframe,
            )
            val correlatedCandles = mtfContextProvider.getCorrelatedContext(
                symbol = symbol,
                timeframe = timeframe,
            )
            val context = AgentContext(
                symbol = symbol,
                timeframe = timeframe,
                candles = candles.asCandleSeries(),
                mtfCandles = mtfCandles,
                correlatedCandles = correlatedCandles,
            )

            val decision = withContext(defaultDispatcher) {
                val orchestratorResult = orchestrator.analyze(context)
                decisionEngine.evaluate(orchestratorResult, dataSource)
            }
            val confluence = if (confluenceEnabled) {
                withContext(defaultDispatcher) {
                    val dataByTimeframe = linkedMapOf(timeframe to candles).apply { putAll(mtfCandles) }
                    val bullish = confluenceEngine.analyze(dataByTimeframe)
                    val bearish = confluenceEngine.analyze(
                        dataByTimeframe = dataByTimeframe,
                        primaryDirection = com.foxtrader.app.domain.model.Direction.BEARISH,
                    )
                    when {
                        bearish.confluenceScore > bullish.confluenceScore -> bearish
                        bullish.confluenceScore > bearish.confluenceScore -> bullish
                        bullish.overallBias == Bias.BEARISH -> bearish
                        else -> bullish
                    }
                }
            } else null

            // Drop stale AI results if the user changed chart context while this
            // background analysis was running.
            if (symbolFlow() != symbol || timeframeFlow() != timeframe) return@launch

            val htfExplanation = withContext(defaultDispatcher) {
                marketExplanationEngine.explain(
                    symbol = symbol,
                    timeframe = timeframe,
                    candles = candles.asCandleSeries(),
                    htfCandles = mtfCandles,
                )
            }

            onResult(AiResult(decision = decision, marketExplanation = htfExplanation, confluence = confluence))

            // Fire a push alert if the AI approves a signal (cooldown-gated).
            val alert = aiAlertService.evaluate(decision, symbol)
            if (alert != null) {
                alertDispatcher.dispatch(alert)
            }
        }
    }

    fun resetCooldowns() {
        cancelInFlight()
        aiAlertService.resetCooldowns()
    }

    /** Cancel any in-flight AI coroutine to avoid wasted CPU on context change. */
    fun cancelInFlight() {
        inFlightJob?.cancel()
        inFlightJob = null
    }

    companion object {
        /**
         * Lightweight content fingerprint combining spread-out context with the
         * full latest-bar OHLCV payload. This keeps the AI pipeline O(1) while
         * still reacting to live high/low/volume updates where close is unchanged.
         */
        fun computeFingerprint(candles: List<Candle>): Long {
            // Public and independently testable, so it must not rely on the
            // caller's `size < 50` guard to stay in bounds: first()/last() throw
            // on an empty series. 0 is a safe sentinel because the caller also
            // resets `lastAiCandlesHash` to 0 when data is insufficient.
            if (candles.isEmpty()) return 0L
            val midIndex = candles.size / 2
            val last = candles.last()
            var h = candles.size.toLong()
            h = h * 31L + candles.first().timestamp
            h = h * 31L + candles.first().open.toBits()
            h = h * 31L + candles[midIndex].timestamp
            h = h * 31L + candles[midIndex].high.toBits()
            h = h * 31L + last.timestamp
            h = h * 31L + last.open.toBits()
            h = h * 31L + last.high.toBits()
            h = h * 31L + last.low.toBits()
            h = h * 31L + last.close.toBits()
            h = h * 31L + last.volume.toBits()
            return h
        }
    }
}
