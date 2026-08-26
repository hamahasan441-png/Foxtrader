package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.SmcVisualMode
import com.foxtrader.app.domain.model.StrategyType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart workspace is persisted as JSON. These tests pin the two properties
 * that matter: a saved workspace comes back intact, and a payload written by a
 * different build degrades to defaults instead of making the chart unopenable.
 */
class IndicatorTogglesPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a tuned workspace round-trips through json`() {
        val original = IndicatorToggles(
            ema = true,
            rsi = true,
            pivotSweepDivergence = true,
            valueAreaLiquidityRejection = true,
            volumeProfile = true,
            smcVisualMode = SmcVisualMode.PROFESSIONAL,
            activeStrategy = StrategyType.LITX,
            settings = ChartStudySettings(
                ema = EmaStudySettings(fastPeriod = 9, slowPeriod = 21),
                rsi = RsiStudySettings(period = 7),
                pivotSweepDivergence = PivotSweepDivergenceStudySettings(
                    mode = PivotSweepDivergenceMode.POWER,
                    sweepWindowBars = 5,
                    maxReclaimBars = 4,
                ),
                volumeProfile = VolumeProfileStudySettings(buckets = 64),
                supportResistance = SupportResistanceStudySettings(swingLookback = 9, maxZones = 12),
                smc = SmcStudySettings(orderBlockImpulseMultiplier = 2.5),
            ),
        )

        val restored = json.decodeFromString<IndicatorToggles>(json.encodeToString(original))

        assertEquals(original, restored)
        assertEquals(9, restored.settings.ema.fastPeriod)
        assertEquals(PivotSweepDivergenceMode.POWER, restored.settings.pivotSweepDivergence.mode)
        assertEquals(5, restored.settings.pivotSweepDivergence.sweepWindowBars)
        assertEquals(64, restored.settings.volumeProfile.buckets)
        assertEquals(2.5, restored.settings.smc.orderBlockImpulseMultiplier, 1e-9)
    }

    @Test
    fun `defaults round-trip`() {
        val restored = json.decodeFromString<IndicatorToggles>(json.encodeToString(IndicatorToggles()))
        assertEquals(IndicatorToggles(), restored)
    }

    /**
     * A payload from an older build will be missing whichever fields were added
     * since. Decoding must still succeed, using defaults for the gaps.
     */
    @Test
    fun `a partial payload decodes with defaults`() {
        val legacy = """{"ema":true,"rsi":true}"""
        val restored = json.decodeFromString<IndicatorToggles>(legacy)

        assertTrue(restored.ema)
        assertTrue(restored.rsi)
        assertEquals(ChartStudySettings(), restored.settings)
        assertEquals(SmcVisualMode.MINIMAL, restored.smcVisualMode)
    }

    /** A payload with fields this build no longer knows must not throw. */
    @Test
    fun `an unknown field is ignored`() {
        val future = """{"ema":true,"someStudyAddedLater":true}"""
        val restored = json.decodeFromString<IndicatorToggles>(future)
        assertTrue(restored.ema)
    }

    /**
     * Out-of-range values must never reach a detector. Persisted state is
     * attacker-adjacent in the sense that it survives crashes and downgrades, so
     * sanitization is applied on the way back in, not only on the way out.
     */
    @Test
    fun `hostile stored values are clamped by sanitize`() {
        val hostile = ChartStudySettings(
            ema = EmaStudySettings(fastPeriod = -5, slowPeriod = 100_000),
            volumeProfile = VolumeProfileStudySettings(buckets = 0),
            marketProfile = MarketProfileStudySettings(rowSize = -1),
            supportResistance = SupportResistanceStudySettings(swingLookback = 0, maxZones = 9_999),
            fibonacci = FibonacciStudySettings(lookbackBars = 1),
            anchoredVwap = AnchoredVwapStudySettings(lookbackBars = 0, bandMultiplier = Double.NaN),
            smc = SmcStudySettings(
                orderBlockImpulseMultiplier = Double.POSITIVE_INFINITY,
                liquidityTolerancePercent = -1.0,
                liquidityLookback = 0,
            ),
        ).sanitized()

        assertTrue(hostile.ema.fastPeriod >= 1)
        assertTrue(hostile.volumeProfile.buckets >= 4)
        assertTrue(hostile.marketProfile.rowSize >= 4)
        assertTrue(hostile.supportResistance.swingLookback >= 1)
        assertTrue(hostile.supportResistance.maxZones <= 30)
        assertTrue(hostile.fibonacci.lookbackBars >= 20)
        assertTrue(hostile.anchoredVwap.lookbackBars >= 10)
        assertTrue(hostile.anchoredVwap.bandMultiplier.isFinite())
        assertTrue(hostile.smc.orderBlockImpulseMultiplier.isFinite())
        assertTrue(hostile.smc.liquidityTolerancePercent > 0.0)
        assertTrue(hostile.smc.liquidityLookback >= 5)
    }

    /**
     * The incremental chart cache is invalidated by IndicatorToggles equality.
     * If a settings change did not change equality, the chart would keep showing
     * values computed with the old parameters.
     */
    @Test
    fun `changing any study parameter changes toggle equality`() {
        val base = IndicatorToggles(rsi = true)
        val changed = base.copy(
            settings = base.settings.copy(rsi = base.settings.rsi.copy(period = 7)),
        )
        assertNotNull(changed.settings)
        assertTrue(base != changed)

        val profileChanged = base.copy(
            settings = base.settings.copy(volumeProfile = VolumeProfileStudySettings(buckets = 60)),
        )
        assertTrue(base != profileChanged)
    }
}
