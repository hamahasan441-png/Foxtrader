package com.foxtrader.app.domain.model

/**
 * News & Economic Calendar domain models.
 */

/** Impact level of an economic event. */
enum class ImpactLevel { LOW, MEDIUM, HIGH }

/** News sentiment classification. */
enum class NewsSentiment { BULLISH, BEARISH, NEUTRAL }

/**
 * An economic calendar event (e.g. FOMC, NFP, CPI).
 */
data class EconomicEvent(
    val id: String,
    val title: String,
    val currency: String,           // e.g. "USD", "EUR"
    val impact: ImpactLevel,
    val timestamp: Long,            // When the event occurs
    val forecast: String? = null,   // Market expectation
    val previous: String? = null,   // Previous reading
    val actual: String? = null,     // Actual (filled after release)
    val description: String = "",
)

/**
 * A news article/headline item.
 */
data class NewsItem(
    val id: String,
    val title: String,
    val source: String,
    val url: String,
    val publishedAt: Long,
    val sentiment: NewsSentiment = NewsSentiment.NEUTRAL,
    val relevanceScore: Double = 1.0, // 0.0-1.0 how relevant to trading
    val relatedSymbols: List<String> = emptyList(),
    val summary: String = "",
)
