package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueAreaLiquidityRejectionEngineTest {
    private val engine = ValueAreaLiquidityRejectionEngine()

    @Test
    fun `active profile is built only from the completed previous session`() {
        val day = 86_400_000L
        val prior = List(24) { index ->
            val price = 100.0 + index * 0.10
            Candle(index * 3_600_000L, price, price + 0.60, price - 0.40, price + 0.20, 100.0 + index)
        }
        val current = listOf(Candle(day, 103.0, 103.5, 102.8, 103.2, 120.0))
        val config = ValueAreaLiquidityRejectionEngine.Config(minPreviousSessionBars = 20)

        val first = engine.analyze("EURUSD", Timeframe.H1, prior + current, config).activeProfile
        val futureCurrentBar = Candle(day + 3_600_000L, 103.2, 500.0, 1.0, 103.3, 999_999.0)
        val second = engine.analyze("EURUSD", Timeframe.H1, prior + current + futureCurrentBar, config).activeProfile

        assertNotNull(first)
        assertEquals(first?.poc, second?.poc)
        assertEquals(first?.vah, second?.vah)
        assertEquals(first?.valueAreaLow, second?.valueAreaLow)
        assertEquals(prior.last().timestamp, first?.sourceEndTimestamp)
    }

    /**
     * The chart draws one value area per session, so the engine must hand back
     * every session profile — not only the one for the session in progress —
     * and each must be bounded by the bars it actually governed.
     */
    @Test
    fun `every session profile is returned and bounded to its own session`() {
        val hour = 3_600_000L
        val day = 86_400_000L
        val candles = (0 until 4).flatMap { dayIndex ->
            List(24) { hourIndex ->
                val price = 100.0 + dayIndex * 2.0 + hourIndex * 0.10
                Candle(
                    dayIndex * day + hourIndex * hour,
                    price,
                    price + 0.60,
                    price - 0.40,
                    price + 0.20,
                    100.0 + hourIndex,
                )
            }
        }
        val analysis = engine.analyze(
            "EURUSD",
            Timeframe.H1,
            candles,
            ValueAreaLiquidityRejectionEngine.Config(minPreviousSessionBars = 20),
        )

        // Four sessions, three of which have a completed predecessor to profile.
        assertEquals(3, analysis.profiles.size)
        assertEquals(analysis.activeProfile, analysis.profiles.last())
        analysis.profiles.forEach { profile ->
            assertTrue(
                "a profile must span at least one bar",
                profile.appliesToIndex >= profile.appliesFromIndex,
            )
        }
        // Sessions are disjoint and ordered oldest first.
        analysis.profiles.zipWithNext().forEach { (older, newer) ->
            assertTrue(older.appliesToIndex < newer.appliesFromIndex)
        }
    }

    @Test
    fun `daily and synthetic non-time contexts do not produce signals`() {
        val candles = List(80) { index -> Candle(index * 86_400_000L, 100.0, 101.0, 99.0, 100.5, 10.0) }
        val analysis = engine.analyze("EURUSD", Timeframe.D1, candles)
        assertTrue(analysis.signals.isEmpty())
        assertEquals(null, analysis.activeProfile)
    }

    @Test
    fun `balanced mode emits a closed reclaim arrow after a value area sweep`() {
        val day = 86_400_000L
        val step = 15L * 60_000L
        val prior = List(40) { index ->
            val center = 100.0 + ((index % 8) - 4) * 0.08
            Candle(1L + index * step, center, center + 0.28, center - 0.28, center + 0.04, 100.0)
        }
        val probe = Candle(day, 100.0, 100.2, 99.8, 100.1, 100.0)
        val config = ValueAreaLiquidityRejectionEngine.Config(
            mode = ValueAreaLiquidityRejectionEngine.Mode.PRECISION,
            minPreviousSessionBars = 20,
            atrPeriod = 2,
            minSweepAtr = 0.0,
            minWickFraction = 0.15,
            minPocRewardRisk = 0.25,
            minScore = 0,
            displacementAtrMultiple = 0.20,
        )
        val profile = engine.analyze("EURUSD", Timeframe.M15, prior + probe, config).activeProfile!!
        val prefix = List(5) { index ->
            val price = profile.valueAreaLow + 0.12
            Candle(day + index * step, price, price + 0.08, price - 0.08, price + 0.01, 80.0)
        }
        val sweepTime = day + prefix.size * step
        val sweep = Candle(
            sweepTime,
            profile.valueAreaLow + 0.10,
            profile.valueAreaLow + 0.15,
            profile.valueAreaLow - 0.60,
            profile.valueAreaLow - 0.05,
            240.0,
        )
        val reclaim = Candle(
            sweepTime + step,
            profile.valueAreaLow - 0.04,
            profile.valueAreaLow + 0.40,
            profile.valueAreaLow - 0.08,
            profile.valueAreaLow + 0.32,
            260.0,
        )

        val signals = engine.analyze("EURUSD", Timeframe.M15, prior + prefix + sweep + reclaim, config).signals
        assertTrue("a causal VAL sweep and closed reclaim must create a visible arrow", signals.isNotEmpty())
        assertTrue(signals.all { it.confirmationIndex >= it.sweepIndex })
        assertEquals(reclaim.timestamp, signals.last().timestamp)
    }
}
