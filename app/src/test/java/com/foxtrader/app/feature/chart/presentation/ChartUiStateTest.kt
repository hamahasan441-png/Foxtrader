package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChartUiStateTest {

    @Test
    fun `ai decision participates in equality for StateFlow emissions`() {
        val base = ChartUiState(isLoading = false)
        val withDecision = base.copy(aiDecision = decision())

        assertNotEquals(base, withDecision)
    }

    @Test
    fun `market explanation participates in equality for StateFlow emissions`() {
        val base = ChartUiState(isLoading = false)
        val withExplanation = base.copy(
            marketExplanation = MarketExplanation.insufficient("EURUSD", Timeframe.H1, 12)
        )

        assertNotEquals(base, withExplanation)
    }

    private fun decision(): DecisionResult = DecisionResult(
        approved = true,
        direction = Direction.BULLISH,
        confidence = 70.0,
        grade = SignalGrade.STRONG,
        confluencePresent = listOf(RequiredConfluence.TREND, RequiredConfluence.VOLUME),
        confluenceMissing = RequiredConfluence.all().filterNot {
            it == RequiredConfluence.TREND || it == RequiredConfluence.VOLUME
        },
        blockReasons = emptyList(),
        vetoedBy = null,
        explanation = "Approved",
        timestamp = 1_000L,
    )
}
