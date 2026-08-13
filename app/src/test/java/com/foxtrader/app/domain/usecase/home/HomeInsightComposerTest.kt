package com.foxtrader.app.domain.usecase.home

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.JournalStats
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.WorkspaceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInsightComposerTest {

    @Test
    fun `empty scan is a fact not a forecast`() {
        val insights = HomeInsightComposer.compose(
            results = emptyList(),
            stats = JournalStats(),
            unreadAlerts = 0,
            openTrades = 0,
            profile = WorkspaceProfile(),
            synthetic = false,
        )
        assertEquals(InsightKind.FACT, insights.first().kind)
        assertTrue(insights.first().text.contains("No scan results"))
    }

    @Test
    fun `synthetic scan is labelled as fact`() {
        val insights = HomeInsightComposer.compose(
            results = listOf(sample(score = 80, change = 1.2)),
            stats = JournalStats(totalTrades = 4, winRate = 50.0, expectancy = 0.2),
            unreadAlerts = 2,
            openTrades = 1,
            profile = WorkspaceProfile(),
            synthetic = true,
        )
        assertEquals(InsightKind.FACT, insights.first().kind)
        assertTrue(insights.any { it.kind == InsightKind.CALCULATION })
        assertTrue(insights.any { it.kind == InsightKind.PROBABILITY })
        assertTrue(insights.any { it.kind == InsightKind.OPINION })
        assertTrue(insights.none { it.kind == InsightKind.PROBABILITY && it.text.contains("will") })
    }

    @Test
    fun `counts are reported as facts`() {
        val insights = HomeInsightComposer.compose(
            results = listOf(
                sample(score = 70, change = 1.0, direction = Direction.BULLISH),
                sample(score = 40, change = -0.8, direction = Direction.BEARISH, symbol = "GBPUSD"),
            ),
            stats = JournalStats(),
            unreadAlerts = 0,
            openTrades = 0,
            profile = WorkspaceProfile(),
            synthetic = false,
        )
        val fact = insights.first { it.kind == InsightKind.FACT }
        assertTrue(fact.text.contains("2 symbols"))
        assertTrue(fact.text.contains("1 scored as buys"))
    }

    private fun sample(
        score: Int,
        change: Double,
        direction: Direction = Direction.BULLISH,
        symbol: String = "EURUSD",
    ) = ScreenerResult(
        symbol = symbol,
        assetClass = AssetClass.FOREX,
        strategy = StrategyType.CONFLUENCE,
        direction = direction,
        score = score,
        bias = if (direction == Direction.BULLISH) Bias.BULLISH else Bias.BEARISH,
        trendStrength = 40.0,
        momentum = 40.0,
        volatility = 40.0,
        setupQuality = 40.0,
        categories = emptyList(),
        tags = emptyList(),
        lastPrice = 1.08,
        changePercent = change,
    )
}
