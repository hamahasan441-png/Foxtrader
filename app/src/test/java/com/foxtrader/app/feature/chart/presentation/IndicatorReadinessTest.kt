package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartBarMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorReadinessTest {

    @Test
    fun `ichimoku explains missing history instead of looking broken`() {
        val state = IndicatorReadinessCatalog.status(
            study = ChartStudyId.ICHIMOKU,
            candleCount = 40,
            barMode = ChartBarMode.TIME,
        )

        assertEquals(IndicatorReadinessLevel.WARMING, state.level)
        assertEquals(12, state.missingBars)
        assertEquals("Need 12 more", state.label)
    }

    @Test
    fun `institutional studies reject non time axis modes in readiness`() {
        val state = IndicatorReadinessCatalog.status(
            study = ChartStudyId.SMT,
            candleCount = 200,
            barMode = ChartBarMode.RENKO,
        )

        assertEquals(IndicatorReadinessLevel.INCOMPATIBLE, state.level)
        assertEquals("Time bars only", state.label)
    }

    @Test
    fun `smt ready state retains peer context hint`() {
        val state = IndicatorReadinessCatalog.status(
            study = ChartStudyId.SMT,
            candleCount = 200,
            barMode = ChartBarMode.TIME,
        )

        assertEquals(IndicatorReadinessLevel.READY, state.level)
        assertTrue(state.label.contains("correlated peer"))
    }

    @Test
    fun `lit and litx use their actual engine warmup floors`() {
        assertEquals(
            IndicatorReadinessLevel.READY,
            IndicatorReadinessCatalog.status(ChartStudyId.LITX, 50, ChartBarMode.TIME).level,
        )
        assertEquals(
            IndicatorReadinessLevel.WARMING,
            IndicatorReadinessCatalog.status(ChartStudyId.LIT, 50, ChartBarMode.TIME).level,
        )
    }

    @Test
    fun `PSD exposes time-axis and previous-day requirements`() {
        val incompatible = IndicatorReadinessCatalog.status(
            ChartStudyId.PIVOT_SWEEP_DIVERGENCE,
            500,
            ChartBarMode.RENKO,
        )
        val ready = IndicatorReadinessCatalog.status(
            ChartStudyId.PIVOT_SWEEP_DIVERGENCE,
            500,
            ChartBarMode.TIME,
        )

        assertEquals(IndicatorReadinessLevel.INCOMPATIBLE, incompatible.level)
        assertTrue(ready.label.contains("prior trading day"))
    }
}
