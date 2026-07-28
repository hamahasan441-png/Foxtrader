package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
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

    @Test
    fun `confluence participates in equality for StateFlow emissions`() {
        val base = ChartUiState(isLoading = false)
        val withConfluence = base.copy(confluence = confluence())

        assertNotEquals(base, withConfluence)
    }

    @Test
    fun `support resistance zones participate in equality for StateFlow emissions`() {
        val base = ChartUiState(isLoading = false)
        val withZones = base.copy(
            supportResistanceZones = listOf(
                SupportResistanceDetector.SRZone(
                    price = 101.2,
                    upperBound = 101.6,
                    lowerBound = 100.8,
                    touches = 3,
                    strength = 78.0,
                    isSupport = true,
                    lastTouchIndex = 42,
                )
            ).toPersistentList()
        )

        assertNotEquals(base, withZones)
    }

    @Test
    fun `synced crosshair timestamp participates in equality for StateFlow emissions`() {
        val base = ChartUiState(isLoading = false)
        val withSyncedCrosshair = base.copy(syncedCrosshairTimestamp = 1_700_000_000_000L)

        assertNotEquals(base, withSyncedCrosshair)
    }


    @Test
    fun `array-backed overlays compare by content after immutable wrapping`() {
        val first = ChartUiState(
            isLoading = false,
            emaShort = doubleArrayOf(100.0, 101.5, 102.25).asImmutableDoubleSeries(),
            superTrendDir = intArrayOf(1, 1, -1).asImmutableIntSeries(),
        )
        val second = ChartUiState(
            isLoading = false,
            emaShort = doubleArrayOf(100.0, 101.5, 102.25).asImmutableDoubleSeries(),
            superTrendDir = intArrayOf(1, 1, -1).asImmutableIntSeries(),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
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

    private fun confluence(): ConfluenceEngine.ConfluenceResult = ConfluenceEngine.ConfluenceResult(
        analyses = listOf(
            ConfluenceEngine.TimeframeAnalysis(
                timeframe = Timeframe.H1,
                bias = Bias.BULLISH,
                trendStrength = 26.0,
                emaAlignment = true,
                rsiZone = ConfluenceEngine.RsiZone.NEUTRAL,
                structureIntact = true,
            )
        ),
        overallBias = Bias.BULLISH,
        confluenceScore = 100,
        recommendation = "Strong setup",
        alignedTimeframes = 1,
        totalTimeframes = 1,
    )
}
