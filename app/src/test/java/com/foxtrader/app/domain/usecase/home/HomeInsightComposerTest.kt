package com.foxtrader.app.domain.usecase.home

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.MarketMover
import com.foxtrader.app.domain.model.WorkspaceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInsightComposerTest {

    @Test
    fun `empty snapshot is a fact not a forecast`() {
        val insights = HomeInsightComposer.compose(
            results = emptyList(),
            unreadAlerts = 0,
            openPositions = 0,
            profile = WorkspaceProfile(),
            synthetic = false,
        )
        assertEquals(InsightKind.FACT, insights.first().kind)
        assertTrue(insights.first().text.contains("No market snapshot"))
    }

    @Test
    fun `synthetic snapshot is labelled as fact`() {
        val insights = HomeInsightComposer.compose(
            results = listOf(sample(change = 1.2)),
            unreadAlerts = 2,
            openPositions = 1,
            profile = WorkspaceProfile(),
            synthetic = true,
        )
        assertEquals(InsightKind.FACT, insights.first().kind)
        assertTrue(insights.any { it.kind == InsightKind.CALCULATION })
        assertTrue(insights.any { it.kind == InsightKind.OPINION })
        assertTrue(insights.none { it.text.contains("will") })
    }

    @Test
    fun `positive and negative watchlist counts are reported as facts`() {
        val insights = HomeInsightComposer.compose(
            results = listOf(
                sample(change = 1.0),
                sample(change = -0.8, symbol = "GBPUSD"),
            ),
            unreadAlerts = 0,
            openPositions = 0,
            profile = WorkspaceProfile(),
            synthetic = false,
        )
        val fact = insights.first { it.kind == InsightKind.FACT }
        assertTrue(fact.text.contains("2 symbols"))
        assertTrue(fact.text.contains("1 positive"))
        assertTrue(fact.text.contains("1 negative"))
    }

    private fun sample(
        change: Double,
        symbol: String = "EURUSD",
    ) = MarketMover(
        symbol = symbol,
        assetClass = AssetClass.FOREX,
        lastPrice = 1.08,
        changePercent = change,
    )
}
