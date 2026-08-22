package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitEventType
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.domain.model.LitPoiKind
import com.foxtrader.app.domain.model.LitPoiZone
import com.foxtrader.app.domain.model.LitProContext
import com.foxtrader.app.domain.model.LitScob
import com.foxtrader.app.domain.model.LitStage
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LitProChartProjectionTest {

    private val candles = (0 until 20).map { index ->
        val base = 100.0 + index
        Candle(
            timestamp = 1_700_000_000_000L + index * 60_000L,
            open = base,
            high = base + 2.0,
            low = base - 2.0,
            close = base + 1.0,
            volume = 1_000.0,
        )
    }

    @Test
    fun `projects confirmed framework events onto confirmation bars`() {
        val analysis = LitAnalysis(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            stage = LitStage.SCOB_READY,
            signal = null,
            narrative = "test",
            context = LitProContext(
                trend = Direction.BULLISH,
                pullback = LitLevel(LitEventType.PULLBACK, Direction.BULLISH, 103.0, 3, 5),
                inducement = LitLevel(LitEventType.IDM, Direction.BULLISH, 104.0, 4, 6, swept = true),
                bos = LitLevel(LitEventType.BOS, Direction.BULLISH, 108.0, 7, 9),
                choch = LitLevel(LitEventType.CHOCH, Direction.BEARISH, 111.0, 10, 12),
                poi = LitPoiZone(
                    kind = LitPoiKind.DECISIONAL,
                    direction = Direction.BEARISH,
                    low = 109.0,
                    high = 112.0,
                    originIndex = 10,
                    confirmationIndex = 12,
                    quality = 84,
                ),
                scob = LitScob(
                    direction = Direction.BEARISH,
                    low = 108.5,
                    high = 110.5,
                    originIndex = 13,
                    confirmationIndex = 14,
                    quality = 88,
                ),
            ),
        )

        val projection = analysis.toLitProChartProjection(candles)
        val labels = projection.structureBreaks.associateBy { it.labelOverride }

        assertEquals(5, labels.getValue("Pullback").breakIndex)
        assertEquals(6, labels.getValue("IDM").breakIndex)
        assertEquals(9, labels.getValue("BOS").breakIndex)
        assertEquals(12, labels.getValue("CHoCH").breakIndex)
        assertEquals(12, labels.getValue("POI DECISIONAL").breakIndex)
        assertEquals(14, labels.getValue("SCOB").breakIndex)
        assertTrue(projection.structureBreaks.all { it.confirmed })
        assertEquals(2, projection.zones.size)
        assertTrue(projection.zones.all { it.endIndex == candles.lastIndex })
    }

    @Test
    fun `invalid out of range context fails closed`() {
        val analysis = LitAnalysis(
            symbol = "BTCUSDT",
            timeframe = Timeframe.M15,
            stage = LitStage.POI_READY,
            signal = null,
            narrative = "test",
            context = LitProContext(
                trend = Direction.BULLISH,
                bos = LitLevel(LitEventType.BOS, Direction.BULLISH, Double.NaN, 4, 6),
                poi = LitPoiZone(
                    kind = LitPoiKind.EXTREME,
                    direction = Direction.BULLISH,
                    low = 0.0,
                    high = 1.0,
                    originIndex = 999,
                    confirmationIndex = 999,
                    quality = 90,
                ),
            ),
        )

        val projection = analysis.toLitProChartProjection(candles)
        assertTrue(projection.structureBreaks.isEmpty())
        assertTrue(projection.zones.isEmpty())
    }
}
