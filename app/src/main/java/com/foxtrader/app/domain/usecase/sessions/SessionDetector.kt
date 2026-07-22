package com.foxtrader.app.domain.usecase.sessions

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.TradingSession
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Trading Session Detector — identifies London, New York, Tokyo, Sydney sessions.
 *
 * Maps session open/close hours (UTC) to bar indices in the candle array.
 * Used to render colored session background highlights on the chart.
 *
 * Features:
 * - Handles overnight sessions (Sydney wraps midnight UTC)
 * - Computes session high/low for range analysis
 * - Works with any timeframe (adapts to bar duration)
 *
 * Pure domain logic — no Android dependencies.
 */
class SessionDetector @Inject constructor() {

    /**
     * Detect all trading sessions in the given candle range.
     * Returns session ranges with start/end indices and high/low prices.
     *
     * @param candles Full candle dataset
     * @param sessions Which sessions to detect (default: all 4 major sessions)
     */
    fun detectSessions(
        candles: List<Candle>,
        sessions: List<TradingSession> = TradingSession.entries,
    ): List<SessionRange> {
        if (candles.isEmpty()) return emptyList()
        val result = mutableListOf<SessionRange>()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        for (session in sessions) {
            var sessionStart = -1
            var sessionHigh = Double.NEGATIVE_INFINITY
            var sessionLow = Double.POSITIVE_INFINITY

            for (i in candles.indices) {
                calendar.timeInMillis = candles[i].timestamp
                val hour = calendar.get(Calendar.HOUR_OF_DAY)

                val inSession = if (session.openHourUtc < session.closeHourUtc) {
                    hour in session.openHourUtc until session.closeHourUtc
                } else {
                    // Overnight session (wraps midnight)
                    hour >= session.openHourUtc || hour < session.closeHourUtc
                }

                if (inSession) {
                    if (sessionStart == -1) sessionStart = i
                    sessionHigh = max(sessionHigh, candles[i].high)
                    sessionLow = min(sessionLow, candles[i].low)
                } else if (sessionStart != -1) {
                    // Session just ended
                    result.add(
                        SessionRange(
                            session = session,
                            startIndex = sessionStart,
                            endIndex = i - 1,
                            highPrice = sessionHigh,
                            lowPrice = sessionLow,
                        )
                    )
                    sessionStart = -1
                    sessionHigh = Double.NEGATIVE_INFINITY
                    sessionLow = Double.POSITIVE_INFINITY
                }
            }

            // Handle session still open at end of data
            if (sessionStart != -1) {
                result.add(
                    SessionRange(
                        session = session,
                        startIndex = sessionStart,
                        endIndex = candles.lastIndex,
                        highPrice = sessionHigh,
                        lowPrice = sessionLow,
                    )
                )
            }
        }

        return result.sortedBy { it.startIndex }
    }
}
