package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LitBreakMode
import com.foxtrader.app.domain.model.LitConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LitProStructureDetectorTest {
    private val detector = LitProStructureDetector()

    @Test
    fun `flat market does not fabricate structural execution context`() {
        val candles = (0 until 90).map { index ->
            candle(index, 100.0, 100.2, 99.8, 100.0)
        }
        val context = detector.detect(candles, LitConfig(swingLeftBars = 2, swingRightBars = 2))

        assertNull(context.choch)
        assertNull(context.poi)
        assertNull(context.scob)
    }

    @Test
    fun `confirmed levels never predate their structural origin`() {
        val context = detector.detect(
            trendingWave(90),
            LitConfig(
                setupLookback = 80,
                swingLeftBars = 2,
                swingRightBars = 2,
                breakMode = LitBreakMode.BODY,
            ),
        )

        assertNotNull("fixture should create at least one continuation BOS", context.bos)
        listOfNotNull(context.pullback, context.inducement, context.bos, context.choch).forEach { level ->
            assertTrue(level.originIndex >= 0)
            assertTrue(level.confirmationIndex >= level.originIndex)
            assertTrue(level.price.isFinite() && level.price > 0.0)
        }
        context.poi?.let { poi ->
            assertTrue(poi.originIndex >= 0)
            assertTrue(poi.confirmationIndex >= poi.originIndex)
            assertTrue(poi.high > poi.low)
        }
        context.scob?.let { scob ->
            assertTrue(scob.confirmationIndex >= scob.originIndex)
            assertTrue(scob.high > scob.low)
        }
    }

    @Test
    fun `appending future bars cannot move a confirmed bos backwards`() {
        val config = LitConfig(
            setupLookback = 100,
            swingLeftBars = 2,
            swingRightBars = 2,
            breakMode = LitBreakMode.BODY,
        )
        val prefix = detector.detect(trendingWave(66), config)
        val extended = detector.detect(trendingWave(90), config)

        val prefixBos = prefix.bos
        val extendedBos = extended.bos
        assertNotNull(prefixBos)
        assertNotNull(extendedBos)
        assertTrue(extendedBos!!.confirmationIndex >= prefixBos!!.confirmationIndex)
    }

    @Test
    fun `lit pro configuration clamps unsafe structural values`() {
        val cfg = LitConfig(
            swingLeftBars = 0,
            swingRightBars = 99,
            maxIdmToBosBars = 1,
            maxBosToChochBars = 100,
            maxPoiAgeBars = 1,
            hiddenShadowMaxAtrFraction = 9.0,
            stopAtrBuffer = 0.0,
        ).sanitized()

        assertTrue(cfg.swingLeftBars == 2)
        assertTrue(cfg.swingRightBars == 8)
        assertTrue(cfg.maxIdmToBosBars == 3)
        assertTrue(cfg.maxBosToChochBars == 36)
        assertTrue(cfg.maxPoiAgeBars == 4)
        assertTrue(cfg.hiddenShadowMaxAtrFraction == 1.0)
        assertTrue(cfg.stopAtrBuffer == 0.02)
    }

    private fun trendingWave(count: Int): List<Candle> {
        val pattern = doubleArrayOf(0.0, 1.8, 3.8, 2.6, 0.8, -1.4, -3.2, -2.0, 0.2, 2.4, 4.8, 3.0)
        return (0 until count).map { index ->
            val cycle = index / pattern.size
            val close = 100.0 + pattern[index % pattern.size] + cycle * 0.9
            val open = close - if (index % 2 == 0) 0.25 else -0.18
            candle(index, open, maxOf(open, close) + 0.32, minOf(open, close) - 0.32, close)
        }
    }

    private fun candle(index: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        timestamp = 1_700_000_000_000L + index * 60_000L,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000.0,
    )
}
