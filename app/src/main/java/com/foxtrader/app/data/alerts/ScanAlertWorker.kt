package com.foxtrader.app.data.alerts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import com.foxtrader.app.domain.usecase.scanner.Phase4ConfluenceEngine
import com.foxtrader.app.domain.usecase.alerts.AlertEngine
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Background periodic worker that evaluates watchlist symbols through the AI
 * decision pipeline and dispatches push alerts for approved setups.
 *
 * Scheduled via WorkManager with a PeriodicWorkRequest (minimum 15 min).
 * Runs even when the app is in the background (respects Doze/App Standby
 * since WorkManager handles deferral).
 *
 * Flow per execution:
 *   1. Get enabled watchlist symbols (top 10 for battery).
 *   2. For each symbol, fetch cached candles (H1 timeframe, offline path).
 *   3. Run the AI orchestrator → MasterDecisionEngine.
 *   4. If approved, pass to AiAlertService → AlertDispatcher.
 */
@HiltWorker
class ScanAlertWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: MarketRepository,
    private val scannerUseCase: ScannerUseCase,
    private val orchestrator: AgentOrchestrator,
    private val decisionEngine: MasterDecisionEngine,
    private val mtfContextProvider: MtfContextProvider,
    private val aiAlertService: AiAlertService,
    private val alertDispatcher: AlertDispatcher,
    private val alertEngine: AlertEngine,
    private val phase4ConfluenceEngine: Phase4ConfluenceEngine,
    private val tradeProEngine: TradeProSignalEngine,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val watchlist = scannerUseCase.getWatchlist()
                .filter { it.enabled }
                .take(MAX_SYMBOLS)

            for (item in watchlist) {
                evaluateSymbol(item.symbol)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Non-fatal: retry next period. Don't crash the worker.
            Result.retry()
        }
    }

    private suspend fun evaluateSymbol(symbol: String) {
        // Sourced fetch: a background scan must not push a notification for a
        // signal computed over synthetic seed bars. The decision engine vetoes
        // on provenance, but only if it is actually told what it is analysing.
        val sourced = repository.getSourcedCandles(symbol, SCAN_TIMEFRAME)
        val candles = sourced.candles
        if (candles.size < MIN_BARS) return
        if (!sourced.source.isTrustworthy) return

        // Fetch HTF context once and reuse it for both the AI orchestrator and
        // the standalone TRADEPRO read below (HTF defines bias, LTF the entry).
        val htfCandles = mtfContextProvider.getHtfContext(symbol, SCAN_TIMEFRAME)

        val correlatedCandles = mtfContextProvider.getCorrelatedContext(symbol, SCAN_TIMEFRAME)
        val context = AgentContext(
            symbol = symbol,
            timeframe = SCAN_TIMEFRAME,
            candles = candles,
            mtfCandles = htfCandles,
            correlatedCandles = correlatedCandles,
        )

        val orchestratorResult = orchestrator.analyze(context)
        val decision = decisionEngine.evaluate(orchestratorResult, sourced.source)

        val alert = aiAlertService.evaluate(decision, symbol)
        if (alert != null) {
            alertDispatcher.dispatch(alert)
            return
        }

        // TRADEPRO standalone: if the full AI consensus didn't approve but the TRADEPRO
        // engine independently found an EXECUTE setup, notify the trader. This surfaces
        // confirmed order-flow/auction setups that the conservative 5-confluence gate
        // might miss (TRADEPRO has its own Flip-Zone/Hold-Zone/imbalance qualification).
        // MTF-validated: HTF bias must agree before a background alert fires,
        // and the user's configured TRADEPRO settings are honoured. The AI path
        // already returned above if it dispatched, so this cannot duplicate it.
        val analysis = tradeProEngine.analyze(
            symbol,
            candles,
            appPreferences.tradeProConfig.value,
            htfCandles,
        )
        val setup = analysis.setup
        if (setup != null && setup.isExecutable) {
            val tradeProAlert = FoxAlert(
                id = "tradepro-${symbol}-${setup.entry.toLong()}",
                title = "TRADEPRO ${if (setup.direction == com.foxtrader.app.domain.model.Direction.BULLISH) "BUY" else "SELL"} — $symbol",
                body = setup.note,
                priority = com.foxtrader.app.domain.model.AlertPriority.MEDIUM,
                symbol = symbol,
                timestamp = System.currentTimeMillis(),
            )
            alertDispatcher.dispatch(tradeProAlert)
            return
        }

        // Phase 4 fallback: a high-quality scanner opportunity may be valid even when neither the
        // AI consensus nor TRADEPRO reaches its own stricter execution state. It must still pass
        // real-data provenance, MTF alignment, SMT conflict checks and the adaptive risk gate.
        val baseScan = scannerUseCase(
            dataMap = mapOf(symbol to candles),
            strategy = StrategyType.CONFLUENCE,
        ).results.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
        if (baseScan != null) {
            val phase4 = phase4ConfluenceEngine.enrich(
                base = baseScan,
                baseCandles = candles,
                higherTimeframeCandles = htfCandles,
                correlatedCandles = correlatedCandles,
                dataTrustworthy = sourced.source.isTrustworthy,
            )
            if (phase4.actionable) {
                val side = if (phase4.direction == Direction.BULLISH) "BUY" else "SELL"
                val p4Alert = alertEngine.send(
                    title = "Phase 4 $side — $symbol",
                    body = "Score ${phase4.score}/100 · MTF ${(phase4.mtfAlignment * 100).toInt()}%" +
                        (phase4.smtPeer?.let { " · SMT $it" } ?: "") +
                        " · suggested risk x${"%.2f".format(phase4.riskMultiplier)}",
                    priority = if (phase4.score >= 85) AlertPriority.HIGH else AlertPriority.MEDIUM,
                    symbol = symbol,
                    cooldownKey = "phase4-$symbol-${phase4.direction}",
                    cooldownMsOverride = PHASE4_ALERT_COOLDOWN_MS,
                )
                if (p4Alert != null) alertDispatcher.dispatch(p4Alert)
            }
        }
    }

    companion object {
        const val WORK_NAME = "fox_scan_alert_periodic"
        private const val MAX_SYMBOLS = 10
        private const val MIN_BARS = 50
        private const val PHASE4_ALERT_COOLDOWN_MS = 45 * 60_000L
        private val SCAN_TIMEFRAME = Timeframe.H1
    }
}
