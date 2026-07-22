package com.foxtrader.app.domain.usecase.news

import com.foxtrader.app.domain.model.EconomicEvent
import com.foxtrader.app.domain.model.ImpactLevel
import com.foxtrader.app.domain.model.NewsItem
import com.foxtrader.app.domain.model.NewsSentiment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * News & Economic Calendar Engine.
 *
 * Manages:
 * - Economic calendar events (FOMC, NFP, CPI, etc.)
 * - Market news items with sentiment classification
 * - Impact-based filtering and alerting
 * - Upcoming event scheduling
 *
 * The actual API data fetching is in the data layer.
 * This engine provides domain logic: filtering, scoring, alerting.
 */
@Singleton
class NewsEngine @Inject constructor() {

    private val events = mutableListOf<EconomicEvent>()
    private val news = mutableListOf<NewsItem>()

    // ========================================================================
    // ECONOMIC CALENDAR
    // ========================================================================

    fun addEvents(newEvents: List<EconomicEvent>) {
        events.addAll(newEvents)
        events.sortBy { it.timestamp }
    }

    /**
     * Get upcoming events within the next N hours.
     */
    fun getUpcomingEvents(withinHours: Int = 24): List<EconomicEvent> {
        val now = System.currentTimeMillis()
        val cutoff = now + withinHours * 3_600_000L
        return events.filter { it.timestamp in now..cutoff }
    }

    /**
     * Get high-impact events only (NFP, FOMC, CPI, etc.)
     */
    fun getHighImpactEvents(withinHours: Int = 48): List<EconomicEvent> =
        getUpcomingEvents(withinHours).filter { it.impact == ImpactLevel.HIGH }

    /**
     * Check if a high-impact event is imminent (within N minutes).
     * Used to warn before entering trades.
     */
    fun isHighImpactImminent(withinMinutes: Int = 30): Boolean {
        val cutoff = System.currentTimeMillis() + withinMinutes * 60_000L
        return events.any { it.impact == ImpactLevel.HIGH && it.timestamp <= cutoff && it.timestamp >= System.currentTimeMillis() }
    }

    /**
     * Get events affecting a specific currency/symbol.
     */
    fun getEventsForSymbol(symbol: String): List<EconomicEvent> {
        val currencies = extractCurrencies(symbol)
        return events.filter { event ->
            currencies.any { it == event.currency }
        }
    }

    // ========================================================================
    // NEWS
    // ========================================================================

    fun addNews(items: List<NewsItem>) {
        news.addAll(items)
        news.sortByDescending { it.publishedAt }
    }

    fun getLatestNews(limit: Int = 20): List<NewsItem> =
        news.take(limit)

    fun getNewsForSymbol(symbol: String, limit: Int = 10): List<NewsItem> {
        val currencies = extractCurrencies(symbol)
        return news.filter { item ->
            currencies.any { currency -> item.relatedSymbols.contains(currency) || item.title.contains(currency, ignoreCase = true) }
        }.take(limit)
    }

    fun getNewsBySentiment(sentiment: NewsSentiment): List<NewsItem> =
        news.filter { it.sentiment == sentiment }

    // ========================================================================
    // SENTIMENT AGGREGATION
    // ========================================================================

    /**
     * Get overall sentiment score for a symbol (-1.0 bearish to +1.0 bullish).
     */
    fun getSentimentScore(symbol: String): Double {
        val relevant = getNewsForSymbol(symbol, limit = 50)
        if (relevant.isEmpty()) return 0.0

        val score = relevant.sumOf { item ->
            when (item.sentiment) {
                NewsSentiment.BULLISH -> 1.0
                NewsSentiment.BEARISH -> -1.0
                NewsSentiment.NEUTRAL -> 0.0
            } * item.relevanceScore
        }
        return (score / relevant.size).coerceIn(-1.0, 1.0)
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun extractCurrencies(symbol: String): List<String> {
        // EURUSD → [EUR, USD], BTCUSDT → [BTC, USDT]
        return if (symbol.length == 6) {
            listOf(symbol.substring(0, 3), symbol.substring(3, 6))
        } else if (symbol.endsWith("USDT")) {
            listOf(symbol.dropLast(4), "USDT")
        } else {
            listOf(symbol)
        }
    }

    fun clearAll() {
        events.clear()
        news.clear()
    }
}
