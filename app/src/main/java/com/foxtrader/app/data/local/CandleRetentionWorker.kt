package com.foxtrader.app.data.local

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Periodic safety net for the Room candle cache.
 *
 * Refreshes already prune their own series, but live WebSocket bars can arrive
 * for hours without a refresh. This worker enumerates the existing series and
 * applies the same user-configured ceiling, so storage and Flow emissions stay
 * bounded even during a long live session.
 */
@HiltWorker
class CandleRetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val candleDao: CandleDao,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val keepCount = appPreferences.maxCachedBars.value
        candleDao.seriesKeys().forEach { series ->
            candleDao.prune(series.symbol, series.timeframe, keepCount)
        }
        Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        const val WORK_NAME = "fox_candle_retention_periodic"
        const val WORK_TAG = "fox_candle_retention"
    }
}
