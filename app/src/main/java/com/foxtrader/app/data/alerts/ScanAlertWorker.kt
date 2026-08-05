package com.foxtrader.app.data.alerts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
        } catch (e: Exception) {
            // Only retry on transient failures (IO, timeout).
            // Programming errors (NPE, cast, etc.) should fail fast so
            // crash reporting can capture the root cause.
            val cause = e.cause ?: e
            if (cause is java.io.IOException ||
                cause is java.util.concurrent.TimeoutException ||
                cause is kotlinx.coroutines.TimeoutCancellationException
            ) {
                Result.retry()
            } else {
                Result.failure()
            }
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

        val context = AgentContext(
            symbol = symbol,
            timeframe = SCAN_TIMEFRAME,
            candles = candles,
            mtfCandles = htfCandles,
            correlatedCandles = mtfContextProvider.getCorrelatedContext(symbol, SCAN_TIMEFRAME),
        )

        val orchestratorResult = orchestrator.analyze(context)
        val decision = decisionEngine.evaluate(orchestratorResult, sourced.source)

        val alert = aiAlertService.evaluate(decision, symbol)
        if (alert != null) {
            alertDispatcher.dispatch(alert)
        }

        // TRADEPRO standalone: if the full AI consensus didn't approve but the TRADEPRO
        // engine independently found an EXECUTE setup, notify the trader. This surfaces
        // confirmed order-flow/auction setups that the conservative 5-confluence gate
        // might miss (TRADEPRO has its own Flip-Zone/Hold-Zone/imbalance qualification).
        if (alert == null) {
            // MTF-validated: HTF bias must agree before a background alert fires,
            // and the user's configured TRADEPRO settings are honoured.
            val analysis = tradeProEngine.analyze(
                symbol,
                candles,
                appPreferences.tradeProConfig.value,
                htfCandles,
            )
            val setup = analysis.setup
            if (setup != null && setup.isExecutable) {
                val id = "tradepro-${symbol}-${setup.entry}-${setup.stopLoss}-${setup.target1}"
                val tradeProAlert = FoxAlert(
                    id = id,
                    title = "TRADEPRO ${if (setup.direction == com.foxtrader.app.domain.model.Direction.BULLISH) "BUY" else "SELL"} — $symbol",
                    body = setup.note,
                    priority = com.foxtrader.app.domain.model.AlertPriority.MEDIUM,
                    symbol = symbol,
                    timestamp = System.currentTimeMillis(),
                )
                alertDispatcher.dispatch(tradeProAlert)
            }
        }
    }

    companion object {
        const val WORK_NAME = "fox_scan_alert_periodic"
        private const val MAX_SYMBOLS = 10
        private const val MIN_BARS = 50
        private val SCAN_TIMEFRAME = Timeframe.H1
    }
}
