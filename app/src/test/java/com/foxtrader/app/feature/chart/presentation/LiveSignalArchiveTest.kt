package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSignalArchiveTest {
    private val context = LiveSignalContext(
        provider = DataProvider.DERIV,
        symbol = "EURUSD",
        timeframe = Timeframe.M1,
        barMode = ChartBarMode.TIME,
    )

    @Test
    fun `live event survives later empty frames`() {
        val archive = LiveSignalArchive()
        val candles = candles(5)
        val live = signal(id = "live-1", index = 3, timestamp = candles[3].timestamp, isLive = true)

        val first = archive.merge(context, listOf(live), candles)
        assertTrue(first.any { it.id == "live-1" && it.isLive })

        val later = archive.merge(context, emptyList(), candles)
        assertEquals(1, later.size)
        assertEquals("live-1", later.single().id)
        assertFalse(later.single().isLive)
        assertEquals(1, archive.liveSnapshot(context, candles).size)
    }

    @Test
    fun `retrospective strategy marker is rendered but not promoted to live sample`() {
        val archive = LiveSignalArchive()
        val candles = candles(5)
        val historical = signal(
            id = "history-only",
            index = 1,
            timestamp = candles[1].timestamp,
            isLive = false,
            source = SignalSource.STRATEGY,
        )

        assertEquals(1, archive.merge(context, listOf(historical), candles).size)
        assertTrue(archive.liveSnapshot(context, candles).isEmpty())
        assertTrue(archive.merge(context, emptyList(), candles).isEmpty())
    }

    @Test
    fun `prepending older candles remaps retained arrow by timestamp`() {
        val archive = LiveSignalArchive()
        val original = candles(4, startTimestamp = 120_000L)
        val live = signal(id = "stable", index = 2, timestamp = original[2].timestamp, isLive = true)
        archive.merge(context, listOf(live), original)

        val prepended = listOf(
            candle(0L, 99.0),
            candle(60_000L, 99.5),
        ) + original
        val retained = archive.liveSnapshot(context, prepended).single()

        assertEquals(4, retained.barIndex)
        assertEquals(original[2].timestamp, retained.timestamp)
    }

    @Test
    fun `semantic event key deduplicates canonical and strategy mirror`() {
        val archive = LiveSignalArchive()
        val candles = candles(5)
        val canonical = signal(
            id = "lit-direct",
            index = 3,
            timestamp = candles[3].timestamp,
            isLive = true,
            source = SignalSource.LIT,
            eventKey = "lit|EURUSD|1m|${candles[3].timestamp}|BULLISH|3",
        )
        archive.merge(context, listOf(canonical), candles)

        val mirror = signal(
            id = "strategy-mirror",
            index = 3,
            timestamp = candles[3].timestamp,
            isLive = false,
            source = SignalSource.STRATEGY,
            eventKey = canonical.eventKey,
        )
        val merged = archive.merge(context, listOf(mirror), candles)

        assertEquals(1, merged.size)
        assertEquals(SignalSource.LIT, merged.single().source)
    }

    @Test
    fun `contexts never mix`() {
        val archive = LiveSignalArchive()
        val candles = candles(5)
        archive.merge(
            context,
            listOf(signal("eur", 3, candles[3].timestamp, true)),
            candles,
        )
        val other = context.copy(symbol = "BTCUSD")

        assertTrue(archive.liveSnapshot(other, candles).isEmpty())
        assertEquals(1, archive.liveSnapshot(context, candles).size)
    }

    private fun signal(
        id: String,
        index: Int,
        timestamp: Long,
        isLive: Boolean,
        source: SignalSource = SignalSource.TRADEPRO,
        eventKey: String? = null,
    ) = ChartSignal(
        id = id,
        source = source,
        direction = Direction.BULLISH,
        entry = 100.0,
        sl = 95.0,
        tp = 110.0,
        barIndex = index,
        timestamp = timestamp,
        confidence = 0.8,
        isLive = isLive,
        label = id,
        eventKey = eventKey,
    )

    private fun candles(count: Int, startTimestamp: Long = 0L): List<Candle> =
        List(count) { i -> candle(startTimestamp + i * 60_000L, 100.0 + i) }

    private fun candle(timestamp: Long, price: Double) = Candle(
        timestamp = timestamp,
        open = price,
        high = price + 1.0,
        low = price - 1.0,
        close = price + 0.2,
        volume = 100.0,
    )
}
