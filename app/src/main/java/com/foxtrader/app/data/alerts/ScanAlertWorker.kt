package com.foxtrader.app.data.alerts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.AgentOrchestrator
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
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
    private val aiAlertService: AiAlertService,
    private val alertDispatcher: AlertDispatcher,
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
        } catch (_: Exception) {
            // Non-fatal: retry next period. Don't crash the worker.
            Result.retry()
        }
    }

    private suspend fun evaluateSymbol(symbol: String) {
        val candles = repository.getCandles(symbol, SCAN_TIMEFRAME)
        if (candles.size < MIN_BARS) return

        val context = AgentContext(
            symbol = symbol,
            timeframe = SCAN_TIMEFRAME,
            candles = candles,
        )

        val orchestratorResult = orchestrator.analyze(context)
        val decision = decisionEngine.evaluate(orchestratorResult)

        val alert = aiAlertService.evaluate(decision, symbol)
        if (alert != null) {
            alertDispatcher.dispatch(alert)
        }
    }

    companion object {
        const val WORK_NAME = "fox_scan_alert_periodic"
        private const val MAX_SYMBOLS = 10
        private const val MIN_BARS = 50
        private val SCAN_TIMEFRAME = Timeframe.H1
    }
}
