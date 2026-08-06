package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitXConfidence
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.LitXSignal
import com.foxtrader.app.domain.model.LitXStage
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProManagementPlan
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalComputerTest {

    private val computer = SignalComputer()

    private val sampleCandles = List(10) { i ->
        Candle(
            timestamp = 1700000000000L + i * 60_000L,
            open = 1.08 + i * 0.001,
            high = 1.081 + i * 0.001,
            low = 1.079 + i * 0.001,
            close = 1.0805 + i * 0.001,
            volume = 100.0,
        )
    }

    @Test
    fun `returns empty list when candles are empty`() {
        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = emptyList(),
            currentTimeMillis = 1700000000000L,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list when all inputs are null or empty`() {
        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = sampleCandles,
            currentTimeMillis = 1700000000000L,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `produces LitX signal when analysis has validated signal`() {
        val litXAnalysis = buildLitXAnalysis(
            direction = Direction.BULLISH,
            entry = 1.0850,
            stopLoss = 1.0800,
            tp1 = 1.0900,
            tp2 = 1.0950,
            score = 78,
            timestamp = 1700005000000L,
        )

        val result = computer.computeSignals(
            litXAnalysis = litXAnalysis,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = sampleCandles,
            currentTimeMillis = 1700006000000L,
        )

        assertEquals(1, result.size)
        val signal = result[0]
        assertEquals(SignalSource.LITX, signal.source)
        assertEquals(Direction.BULLISH, signal.direction)
        assertEquals(1.0850, signal.entry, 0.0001)
        assertEquals(1.0800, signal.sl, 0.0001)
        assertEquals(1.0900, signal.tp, 0.0001)
        assertEquals(sampleCandles.lastIndex, signal.barIndex)
        assertEquals(1700005000000L, signal.timestamp)
        assertEquals(0.78, signal.confidence, 0.01)
        assertTrue(signal.isLive)
        assertEquals("litx_1700005000000", signal.id)
    }

    @Test
    fun `ignores LitX analysis when signal is null`() {
        val litXAnalysis = LitXAnalysis.empty("EURUSD", Timeframe.M15)

        val result = computer.computeSignals(
            litXAnalysis = litXAnalysis,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = sampleCandles,
            currentTimeMillis = 1700006000000L,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `produces TradePro signal only for EXECUTE stage`() {
        val setup = buildTradeProSetup(
            stage = SetupStage.EXECUTE,
            direction = Direction.BEARISH,
            entry = 1.2650,
            stopLoss = 1.2700,
            target1 = 1.2600,
            confidence = 82,
        )
        val analysis = TradeProAnalysis(
            symbol = "GBPUSD",
            flipZone = null,
            holdZones = emptyList(),
            imbalances = emptyList(),
            absorptions = emptyList(),
            setup = setup,
            stage = SetupStage.EXECUTE,
            narrative = "Sell setup confirmed",
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = analysis,
            smtDivergences = emptyList(),
            candles = sampleCandles,
            currentTimeMillis = 1700007000000L,
        )

        assertEquals(1, result.size)
        val signal = result[0]
        assertEquals(SignalSource.TRADEPRO, signal.source)
        assertEquals(Direction.BEARISH, signal.direction)
        assertEquals(1.2650, signal.entry, 0.0001)
        assertEquals(1.2700, signal.sl, 0.0001)
        assertEquals(1.2600, signal.tp, 0.0001)
        assertEquals(sampleCandles.lastIndex, signal.barIndex)
        assertEquals(1700007000000L, signal.timestamp)
        assertEquals(0.82, signal.confidence, 0.01)
        assertTrue(signal.isLive)
    }

    @Test
    fun `does not produce TradePro signal for CONFIRMATION stage`() {
        val setup = buildTradeProSetup(
            stage = SetupStage.CONFIRMATION,
            direction = Direction.BULLISH,
            entry = 1.0850,
            stopLoss = 1.0800,
            target1 = 1.0900,
            confidence = 70,
        )
        val analysis = TradeProAnalysis(
            symbol = "EURUSD",
            flipZone = null,
            holdZones = emptyList(),
            imbalances = emptyList(),
            absorptions = emptyList(),
            setup = setup,
            stage = SetupStage.CONFIRMATION,
            narrative = "Zone confirmation pending",
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = analysis,
            smtDivergences = emptyList(),
            candles = sampleCandles,
            currentTimeMillis = 1700007000000L,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `produces SMT divergence signals with last one marked as live`() {
        val divergences = listOf(
            buildSmtDivergence(
                primaryIndex = 3,
                primaryPrice = 1.0800,
                direction = Direction.BULLISH,
                confidence = 0.72,
            ),
            buildSmtDivergence(
                primaryIndex = 7,
                primaryPrice = 1.0820,
                direction = Direction.BULLISH,
                confidence = 0.78,
            ),
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = divergences,
            candles = sampleCandles,
            currentTimeMillis = 1700008000000L,
        )

        assertEquals(2, result.size)

        // First (older) divergence should NOT be live
        val first = result[0]
        assertEquals(SignalSource.SMT, first.source)
        assertEquals(3, first.barIndex)
        assertEquals(1.0800, first.entry, 0.0001)
        assertEquals(0.72, first.confidence, 0.01)
        assertFalse(first.isLive)

        // Second (latest) divergence should be live
        val second = result[1]
        assertEquals(SignalSource.SMT, second.source)
        assertEquals(7, second.barIndex)
        assertEquals(1.0820, second.entry, 0.0001)
        assertEquals(0.78, second.confidence, 0.01)
        assertTrue(second.isLive)
    }

    @Test
    fun `combines signals from all three sources`() {
        val litXAnalysis = buildLitXAnalysis(
            direction = Direction.BULLISH,
            entry = 1.0850,
            stopLoss = 1.0800,
            tp1 = 1.0900,
            tp2 = 1.0950,
            score = 80,
            timestamp = 1700005000000L,
        )
        val setup = buildTradeProSetup(
            stage = SetupStage.EXECUTE,
            direction = Direction.BULLISH,
            entry = 1.0855,
            stopLoss = 1.0810,
            target1 = 1.0920,
            confidence = 75,
        )
        val tradeProAnalysis = TradeProAnalysis(
            symbol = "EURUSD",
            flipZone = null,
            holdZones = emptyList(),
            imbalances = emptyList(),
            absorptions = emptyList(),
            setup = setup,
            stage = SetupStage.EXECUTE,
            narrative = "Buy setup confirmed",
        )
        val divergences = listOf(
            buildSmtDivergence(
                primaryIndex = 5,
                primaryPrice = 1.0790,
                direction = Direction.BULLISH,
                confidence = 0.65,
            ),
        )

        val result = computer.computeSignals(
            litXAnalysis = litXAnalysis,
            tradeProAnalysis = tradeProAnalysis,
            smtDivergences = divergences,
            candles = sampleCandles,
            currentTimeMillis = 1700009000000L,
        )

        assertEquals(3, result.size)
        assertEquals(SignalSource.LITX, result[0].source)
        assertEquals(SignalSource.TRADEPRO, result[1].source)
        assertEquals(SignalSource.SMT, result[2].source)
    }

    @Test
    fun `single SMT divergence is marked as live`() {
        val divergences = listOf(
            buildSmtDivergence(
                primaryIndex = 5,
                primaryPrice = 1.0850,
                direction = Direction.BEARISH,
                confidence = 0.68,
            ),
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = divergences,
            candles = sampleCandles,
            currentTimeMillis = 1700009000000L,
        )

        assertEquals(1, result.size)
        assertTrue(result[0].isLive)
        assertEquals(Direction.BEARISH, result[0].direction)
    }

    // ========================================================================
    // HELPER BUILDERS
    // ========================================================================

    private fun buildLitXAnalysis(
        direction: Direction,
        entry: Double,
        stopLoss: Double,
        tp1: Double,
        tp2: Double,
        score: Int,
        timestamp: Long,
    ): LitXAnalysis {
        val confidence = LitXConfidence(
            score = score,
            grade = if (score >= 80) LitXGrade.A_PLUS else LitXGrade.A,
            factors = emptyList(),
        )
        val signal = LitXSignal(
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            direction = direction,
            stage = LitXStage.VALIDATED,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            riskReward = (tp1 - entry) / (entry - stopLoss),
            confidence = confidence,
            zone = null,
            rationale = "Test signal",
            timestamp = timestamp,
        )
        return LitXAnalysis(
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            stage = LitXStage.VALIDATED,
            bias = Bias.BULLISH,
            htfBias = Bias.BULLISH,
            displacement = null,
            mitigationBlocks = emptyList(),
            premiumDiscount = null,
            signal = signal,
            narrative = "Test analysis",
            timestamp = timestamp,
        )
    }

    private fun buildTradeProSetup(
        stage: SetupStage,
        direction: Direction,
        entry: Double,
        stopLoss: Double,
        target1: Double,
        confidence: Int,
    ): TradeProSetup = TradeProSetup(
        symbol = "EURUSD",
        direction = direction,
        stage = stage,
        entry = entry,
        stopLoss = stopLoss,
        target1 = target1,
        target2 = target1 + (target1 - entry),
        runnerTarget = target1 + 2 * (target1 - entry),
        riskPoints = kotlin.math.abs(entry - stopLoss) * 10000.0,
        riskReward = kotlin.math.abs(target1 - entry) / kotlin.math.abs(entry - stopLoss),
        confidence = confidence,
        flipZone = null,
        holdZone = null,
        managementPlan = TradeProManagementPlan(
            contracts = 3,
            stopPoints = kotlin.math.abs(entry - stopLoss) * 10000.0,
            t1Points = kotlin.math.abs(target1 - entry) * 10000.0,
            t2Points = kotlin.math.abs(target1 - entry) * 10000.0 * 2,
            t1Contracts = 1,
            t2Contracts = 1,
            runnerContracts = 1,
            totalRiskPoints = kotlin.math.abs(entry - stopLoss) * 10000.0 * 3,
            breakevenWinRate = 0.40,
        ),
        confluences = listOf("imbalance", "absorption"),
        note = "Test setup",
    )

    private fun buildSmtDivergence(
        primaryIndex: Int,
        primaryPrice: Double,
        direction: Direction,
        confidence: Double,
    ): SmtDivergenceDetector.SmtDivergence = SmtDivergenceDetector.SmtDivergence(
        primarySymbol = "EURUSD",
        peerSymbol = "DXY",
        direction = direction,
        type = SmtDivergenceDetector.SmtType.PRIMARY_SWEEP_PEER_FAIL,
        primaryIndex = primaryIndex,
        peerIndex = primaryIndex + 1,
        primaryPrice = primaryPrice,
        peerPrice = primaryPrice + 0.002,
        correlation = 0.85,
        confidence = confidence,
        detail = "EURUSD swept sell-side while DXY held",
    )
}
