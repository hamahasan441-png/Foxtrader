package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TradeJournalAi - validates behavior pattern detection,
 * emotion analysis, setup rankings, trade reviews, and recommendations.
 */
class TradeJournalAiTest {

    private val ai = TradeJournalAi()

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun entry(
        id: String = "t1",
        pnl: Double = 100.0,
        rMultiple: Double = 1.5,
        emotion: EmotionTag = EmotionTag.NEUTRAL,
        setupType: String = "BOS Long",
        entryTime: Long = 1000L,
        exitTime: Long = 2000L,
        direction: Direction = Direction.BULLISH,
    ): JournalEntry = JournalEntry(
        id = id,
        symbol = "EURUSD",
        direction = direction,
        timeframe = Timeframe.H1,
        entryPrice = 1.10,
        exitPrice = 1.11,
        stopLoss = 1.09,
        takeProfit = 1.13,
        volume = 0.1,
        entryTime = entryTime,
        exitTime = exitTime,
        pnl = pnl,
        rMultiple = rMultiple,
        setupType = setupType,
        emotionTag = emotion,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Empty input
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty input produces empty report without crashing`() {
        val report = ai.analyze(emptyList())

        assertEquals(0, report.stats.totalTrades)
        assertTrue(report.behaviorPatterns.isEmpty())
        assertTrue(report.setupRankings.isEmpty())
        assertTrue(report.tradeReviews.isEmpty())
        assertTrue(report.recommendations.isEmpty())
        assertEquals(0, report.streakAnalysis.currentStreak)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Revenge trading detection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `detects revenge trading pattern`() {
        val entries = listOf(
            // Loss trade
            entry(id = "loss1", pnl = -50.0, rMultiple = -1.0, entryTime = 1000L, exitTime = 2000L),
            // Quick follow-up with REVENGE tag (within 30 minutes)
            entry(
                id = "revenge1",
                pnl = -30.0,
                rMultiple = -0.5,
                emotion = EmotionTag.REVENGE,
                entryTime = 1000L + 10 * 60 * 1000, // 10 minutes later
                exitTime = 1000L + 20 * 60 * 1000,
            ),
        )

        val report = ai.analyze(entries)
        val revengePattern = report.behaviorPatterns.find { it.type == BehaviorPatternType.REVENGE_TRADING }
        assertNotNull(revengePattern)
        assertTrue(revengePattern!!.confidence > 0)
        assertTrue(revengePattern.evidence.contains("instances"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overtrading detection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `detects overtrading pattern`() {
        val dayBase = 86_400_000L * 10 // day 10
        // 6 trades in one day with declining R
        val entries = (1..6).map { i ->
            entry(
                id = "t$i",
                pnl = if (i <= 3) 50.0 else -20.0,
                rMultiple = (4.0 - i * 0.5), // declining: 3.5, 3.0, 2.5, 2.0, 1.5, 1.0
                entryTime = dayBase + i * 1000L,
                exitTime = dayBase + i * 1000L + 500L,
            )
        }

        val report = ai.analyze(entries)
        val overtrading = report.behaviorPatterns.find { it.type == BehaviorPatternType.OVERTRADING }
        assertNotNull(overtrading)
        assertTrue(overtrading!!.evidence.contains("days"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Emotion analysis
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `emotion analysis identifies worst emotion`() {
        val entries = listOf(
            entry(id = "t1", pnl = 100.0, rMultiple = 2.0, emotion = EmotionTag.CONFIDENT),
            entry(id = "t2", pnl = 80.0, rMultiple = 1.5, emotion = EmotionTag.CONFIDENT),
            entry(id = "t3", pnl = -50.0, rMultiple = -1.0, emotion = EmotionTag.FOMO),
            entry(id = "t4", pnl = -40.0, rMultiple = -0.8, emotion = EmotionTag.FOMO),
        )

        val report = ai.analyze(entries)
        assertEquals(EmotionTag.FOMO, report.emotionAnalysis.worstEmotion)
    }

    @Test
    fun `emotion analysis identifies best emotion`() {
        val entries = listOf(
            entry(id = "t1", pnl = 100.0, rMultiple = 2.0, emotion = EmotionTag.PATIENT),
            entry(id = "t2", pnl = 80.0, rMultiple = 1.5, emotion = EmotionTag.PATIENT),
            entry(id = "t3", pnl = -50.0, rMultiple = -1.0, emotion = EmotionTag.REVENGE),
            entry(id = "t4", pnl = -40.0, rMultiple = -0.8, emotion = EmotionTag.REVENGE),
        )

        val report = ai.analyze(entries)
        assertEquals(EmotionTag.PATIENT, report.emotionAnalysis.bestEmotion)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup rankings
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setup rankings sorted by average R descending`() {
        val entries = listOf(
            entry(id = "t1", pnl = 200.0, rMultiple = 3.0, setupType = "FVG Rejection"),
            entry(id = "t2", pnl = 150.0, rMultiple = 2.5, setupType = "FVG Rejection"),
            entry(id = "t3", pnl = 50.0, rMultiple = 1.0, setupType = "BOS Long"),
            entry(id = "t4", pnl = 30.0, rMultiple = 0.5, setupType = "BOS Long"),
        )

        val report = ai.analyze(entries)
        assertEquals(2, report.setupRankings.size)
        assertEquals("FVG Rejection", report.setupRankings[0].setupType)
        assertEquals("BOS Long", report.setupRankings[1].setupType)
        assertTrue(report.setupRankings[0].avgR > report.setupRankings[1].avgR)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trade review grading
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `trade review grades EXCELLENT for high R with PATIENT emotion`() {
        val entries = listOf(
            entry(id = "excellent", pnl = 300.0, rMultiple = 3.5, emotion = EmotionTag.PATIENT),
        )

        val report = ai.analyze(entries)
        val review = report.tradeReviews.find { it.entryId == "excellent" }
        assertNotNull(review)
        assertEquals(TradeReviewVerdict.EXCELLENT, review!!.verdict)
    }

    @Test
    fun `trade review grades TERRIBLE for negative R with REVENGE emotion`() {
        val entries = listOf(
            entry(id = "terrible", pnl = -100.0, rMultiple = -2.0, emotion = EmotionTag.REVENGE),
        )

        val report = ai.analyze(entries)
        val review = report.tradeReviews.find { it.entryId == "terrible" }
        assertNotNull(review)
        assertEquals(TradeReviewVerdict.TERRIBLE, review!!.verdict)
    }

    @Test
    fun `trade review grades GOOD for R above 1_5`() {
        val entries = listOf(
            entry(id = "good", pnl = 100.0, rMultiple = 2.0, emotion = EmotionTag.NEUTRAL),
        )

        val report = ai.analyze(entries)
        val review = report.tradeReviews.find { it.entryId == "good" }
        assertNotNull(review)
        assertEquals(TradeReviewVerdict.GOOD, review!!.verdict)
    }

    @Test
    fun `trade review skips open trades`() {
        val openEntry = JournalEntry(
            id = "open1",
            symbol = "EURUSD",
            direction = Direction.BULLISH,
            timeframe = Timeframe.H1,
            entryPrice = 1.10,
            exitPrice = null,
            stopLoss = 1.09,
            takeProfit = 1.13,
            volume = 0.1,
            entryTime = 1000L,
            exitTime = null,
            pnl = null,
            rMultiple = null,
            setupType = "BOS Long",
        )

        val report = ai.analyze(listOf(openEntry))
        assertTrue(report.tradeReviews.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Improving trend
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `improving trend detected when recent trades are better`() {
        // First 10 trades: average R = 0.5
        val older = (1..10).map { i ->
            entry(
                id = "old$i",
                pnl = 25.0,
                rMultiple = 0.5,
                entryTime = (i * 1000L),
                exitTime = (i * 1000L + 500L),
            )
        }
        // Next 10 trades: average R = 2.0
        val recent = (1..10).map { i ->
            entry(
                id = "new$i",
                pnl = 100.0,
                rMultiple = 2.0,
                entryTime = (20000L + i * 1000L),
                exitTime = (20000L + i * 1000L + 500L),
            )
        }

        val report = ai.analyze(older + recent)
        val improving = report.behaviorPatterns.find { it.type == BehaviorPatternType.IMPROVING_TREND }
        assertNotNull(improving)
        assertTrue(improving!!.confidence > 0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recommendations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recommendations are non-empty when patterns are detected`() {
        // Build a dataset that triggers FOMO pattern
        val normalTrades = (1..5).map { i ->
            entry(
                id = "normal$i",
                pnl = 50.0,
                rMultiple = 1.5,
                emotion = EmotionTag.CONFIDENT,
                entryTime = (i * 100000L),
                exitTime = (i * 100000L + 5000L),
            )
        }
        val fomoTrades = (1..5).map { i ->
            entry(
                id = "fomo$i",
                pnl = -30.0,
                rMultiple = -0.5,
                emotion = EmotionTag.FOMO,
                entryTime = (500000L + i * 100000L),
                exitTime = (500000L + i * 100000L + 5000L),
            )
        }

        val report = ai.analyze(normalTrades + fomoTrades)
        assertTrue(report.recommendations.isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats computation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `stats are computed correctly from entries`() {
        val entries = listOf(
            entry(id = "t1", pnl = 100.0, rMultiple = 2.0),
            entry(id = "t2", pnl = -50.0, rMultiple = -1.0),
            entry(id = "t3", pnl = 80.0, rMultiple = 1.5),
            entry(id = "t4", pnl = 60.0, rMultiple = 1.2),
        )

        val report = ai.analyze(entries)
        val stats = report.stats

        assertEquals(4, stats.totalTrades)
        assertEquals(0.75, stats.winRate, 0.01)
        assertEquals(190.0, stats.totalPnl, 0.01)
        assertEquals(100.0, stats.bestTrade, 0.01)
        assertEquals(-50.0, stats.worstTrade, 0.01)
        assertTrue(stats.profitFactor > 0)
    }

    @Test
    fun `stats handle all losses correctly`() {
        val entries = listOf(
            entry(id = "t1", pnl = -50.0, rMultiple = -1.0),
            entry(id = "t2", pnl = -30.0, rMultiple = -0.5),
        )

        val report = ai.analyze(entries)
        assertEquals(0.0, report.stats.winRate, 0.01)
        assertEquals(0.0, report.stats.profitFactor, 0.01)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streak analysis
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `streak analysis computes longest streaks correctly`() {
        val entries = listOf(
            entry(id = "t1", pnl = 100.0, rMultiple = 2.0, entryTime = 1000L, exitTime = 2000L),
            entry(id = "t2", pnl = 80.0, rMultiple = 1.5, entryTime = 3000L, exitTime = 4000L),
            entry(id = "t3", pnl = 60.0, rMultiple = 1.0, entryTime = 5000L, exitTime = 6000L),
            entry(id = "t4", pnl = -20.0, rMultiple = -0.5, entryTime = 7000L, exitTime = 8000L),
            entry(id = "t5", pnl = -30.0, rMultiple = -0.8, entryTime = 9000L, exitTime = 10000L),
        )

        val report = ai.analyze(entries)
        val streaks = report.streakAnalysis

        assertEquals(3, streaks.longestWinStreak)
        assertEquals(2, streaks.longestLossStreak)
        assertEquals(2, streaks.currentStreak)
        assertEquals(false, streaks.isWinStreak)
    }
}
