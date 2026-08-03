package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.InsightSeverity
import com.foxtrader.app.domain.model.tradepro.TraderArchetype
import com.foxtrader.app.domain.model.tradepro.TradingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraderCoachingEngineTest {

    private val engine = TraderCoachingEngine()

    private var idCounter = 0

    private fun trade(
        setup: String = "BOS Long",
        win: Boolean = true,
        pnl: Double = if (win) 100.0 else -50.0,
        r: Double = if (win) 2.0 else -1.0,
        emotion: EmotionTag = EmotionTag.NEUTRAL,
        rating: Int = 3,
        entryTime: Long = 1_000_000L,
        exitTime: Long = 1_000_000L + 3_600_000L,
        stopLoss: Double = 99.0,
        entryPrice: Double = 100.0,
    ): JournalEntry = JournalEntry(
        id = "t${idCounter++}",
        symbol = "EURUSD",
        direction = Direction.BULLISH,
        timeframe = Timeframe.H1,
        entryPrice = entryPrice,
        exitPrice = if (win) 102.0 else 98.0,
        stopLoss = stopLoss,
        takeProfit = 104.0,
        volume = 1.0,
        entryTime = entryTime,
        exitTime = exitTime,
        pnl = pnl,
        rMultiple = r,
        setupType = setup,
        rating = rating,
        emotionTag = emotion,
    )

    @Test
    fun `too few closed trades returns an empty profile`() {
        val profile = engine.buildProfile(listOf(trade(), trade()))
        assertEquals(TraderArchetype.UNKNOWN, profile.archetype)
        assertEquals(0, profile.totalClosedTrades)
        assertTrue(profile.edges.isEmpty())
        assertTrue(profile.leaks.isEmpty())
    }

    @Test
    fun `open trades are excluded from the profile`() {
        val open = trade().copy(exitPrice = null, pnl = null, rMultiple = null, exitTime = null)
        val entries = List(6) { trade() } + open
        val profile = engine.buildProfile(entries)
        assertEquals(6, profile.totalClosedTrades)
    }

    @Test
    fun `scores and rates are within valid bounds`() {
        val entries = (0 until 20).map { trade(win = it % 2 == 0) }
        val profile = engine.buildProfile(entries)
        assertTrue(profile.disciplineScore in 0..100)
        assertTrue(profile.consistencyScore in 0..100)
        profile.setupPerformance.forEach { assertTrue(it.winRate in 0.0..1.0) }
        profile.emotionPerformance.forEach { assertTrue(it.winRate in 0.0..1.0) }
        profile.sessionPerformance.forEach { assertTrue(it.winRate in 0.0..1.0) }
    }

    @Test
    fun `a profitable high win-rate setup is surfaced as an edge`() {
        val winners = List(8) { trade(setup = "Flip Zone Long", win = true) }
        val fillers = List(4) { trade(setup = "Random", win = false) }
        val profile = engine.buildProfile(winners + fillers)
        assertTrue(
            "expected a positive edge for the winning setup",
            profile.edges.any { it.severity == InsightSeverity.POSITIVE && it.title.contains("Flip Zone Long") },
        )
    }

    @Test
    fun `revenge trades are flagged as a critical leak`() {
        val revenge = List(5) { trade(setup = "Chase", win = false, pnl = -80.0, emotion = EmotionTag.REVENGE) }
        val good = List(8) { trade(setup = "BOS Long", win = true, emotion = EmotionTag.PATIENT) }
        val profile = engine.buildProfile(revenge + good)
        assertTrue(
            "expected a critical revenge leak",
            profile.leaks.any { it.severity == InsightSeverity.CRITICAL && it.title.contains("Revenge", ignoreCase = true) },
        )
    }

    @Test
    fun `high revenge share infers the revenge trader archetype`() {
        val revenge = List(6) { trade(win = false, emotion = EmotionTag.REVENGE) }
        val other = List(10) { trade(win = true, emotion = EmotionTag.NEUTRAL) }
        val profile = engine.buildProfile(revenge + other)
        assertEquals(TraderArchetype.REVENGE_TRADER, profile.archetype)
    }

    @Test
    fun `missing stops trigger a critical discipline leak`() {
        // entryPrice == stopLoss => no stop distance.
        val noStop = List(10) { trade(win = it % 2 == 0, stopLoss = 100.0, entryPrice = 100.0) }
        val profile = engine.buildProfile(noStop)
        assertTrue(
            profile.leaks.any { it.severity == InsightSeverity.CRITICAL && it.title.contains("without a defined stop") },
        )
    }

    @Test
    fun `session bucketing maps entry hour to the correct session`() {
        assertEquals(TradingSession.ASIA, TradingSession.fromHourUtc(3))
        assertEquals(TradingSession.LONDON, TradingSession.fromHourUtc(9))
        assertEquals(TradingSession.NEW_YORK, TradingSession.fromHourUtc(15))
        assertEquals(TradingSession.OFF_HOURS, TradingSession.fromHourUtc(22))
    }

    @Test
    fun `setup performance partitions all closed trades`() {
        val entries = List(6) { trade(setup = "A") } + List(4) { trade(setup = "B", win = false) }
        val profile = engine.buildProfile(entries)
        assertEquals(10, profile.setupPerformance.sumOf { it.trades })
        assertNotNull(profile.setupPerformance.find { it.category == "A" })
        assertNotNull(profile.setupPerformance.find { it.category == "B" })
    }

    @Test
    fun `profiling is deterministic for identical inputs`() {
        val entries = (0 until 15).map { trade(win = it % 3 != 0, emotion = EmotionTag.entries[it % EmotionTag.entries.size]) }
        val first = engine.buildProfile(entries)
        val second = engine.buildProfile(entries)
        assertEquals(first.archetype, second.archetype)
        assertEquals(first.disciplineScore, second.disciplineScore)
        assertEquals(first.consistencyScore, second.consistencyScore)
        assertEquals(first.edges.size, second.edges.size)
        assertEquals(first.leaks.size, second.leaks.size)
    }
}
