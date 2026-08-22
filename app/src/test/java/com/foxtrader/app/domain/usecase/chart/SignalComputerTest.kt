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
    fun `rejects malformed strategy signals before chart rendering`() {
        val malformed = com.foxtrader.app.domain.model.ChartSignal(
            id = "bad",
            source = SignalSource.STRATEGY,
            direction = Direction.BULLISH,
            entry = Double.NaN,
            sl = 1.0,
            tp = 2.0,
            barIndex = sampleCandles.lastIndex,
            timestamp = sampleCandles.last().timestamp,
            confidence = 0.9,
            isLive = true,
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = sampleCandles,
            strategySignals = listOf(malformed),
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
        assertEquals(sampleCandles.last().timestamp, signal.timestamp)
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
    fun `normalizes SMT percentage confidence and marks only newly confirmed divergence live`() {
        val divergences = listOf(
            buildSmtDivergence(
                primaryIndex = 3,
                primaryPrice = 1.0800,
                direction = Direction.BULLISH,
                confidence = 72.0,
                confirmationIndex = 6,
            ),
            buildSmtDivergence(
                primaryIndex = 7,
                primaryPrice = 1.0820,
                direction = Direction.BULLISH,
                confidence = 78.0,
                confirmationIndex = sampleCandles.lastIndex,
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
        assertEquals(6, first.barIndex)
        assertEquals(sampleCandles[6].close, first.entry, 0.0001)
        assertEquals(0.72, first.confidence, 0.01)
        assertFalse(first.isLive)

        // Second (latest) divergence should be live
        val second = result[1]
        assertEquals(SignalSource.SMT, second.source)
        assertEquals(sampleCandles.lastIndex, second.barIndex)
        assertEquals(sampleCandles.last().close, second.entry, 0.0001)
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
                confidence = 65.0,
                confirmationIndex = sampleCandles.lastIndex,
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
                confidence = 68.0,
                confirmationIndex = sampleCandles.lastIndex,
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

    @Test
    fun `newest SMT divergence stays historical after its confirmation bar`() {
        val divergence = buildSmtDivergence(
            primaryIndex = 4,
            primaryPrice = 1.0840,
            direction = Direction.BEARISH,
            confidence = 74.0,
            confirmationIndex = sampleCandles.lastIndex - 1,
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = listOf(divergence),
            candles = sampleCandles,
            currentTimeMillis = 1700009000000L,
        )

        assertEquals(1, result.size)
        assertFalse(result.single().isLive)
        assertEquals(sampleCandles[sampleCandles.lastIndex - 1].timestamp, result.single().timestamp)
    }

    // ========================================================================
    // CROSS-SOURCE CONFLUENCE
    // ========================================================================

    @Test
    fun `binary3m marker keeps fixed strategy confidence and does not boost other sources`() {
        val binary = com.foxtrader.app.domain.model.ChartSignal(
            id = "binary3m_test",
            source = SignalSource.BINARY3M,
            direction = Direction.BULLISH,
            entry = sampleCandles.last().close,
            sl = 0.0,
            tp = 0.0,
            barIndex = sampleCandles.lastIndex,
            timestamp = sampleCandles.last().timestamp,
            confidence = 82.0,
            isLive = true,
        )
        val litX = buildLitXAnalysis(
            direction = Direction.BULLISH,
            entry = 1.0850, stopLoss = 1.0800, tp1 = 1.0900, tp2 = 1.0950,
            score = 80, timestamp = 1700005000000L,
        )

        val result = computer.computeSignals(
            litXAnalysis = litX,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = sampleCandles,
            strategySignals = listOf(binary),
        )

        assertEquals(0.82, result.first { it.source == SignalSource.BINARY3M }.confidence, 1e-4)
        assertEquals(0.80, result.first { it.source == SignalSource.LITX }.confidence, 1e-4)
    }

    @Test
    fun `boosts confidence when LitX and TradePro agree on direction`() {
        val litX = buildLitXAnalysis(
            direction = Direction.BULLISH,
            entry = 1.0850, stopLoss = 1.0800, tp1 = 1.0900, tp2 = 1.0950,
            score = 80, timestamp = 1700005000000L,
        )
        val analysis = TradeProAnalysis(
            symbol = "EURUSD", flipZone = null, holdZones = emptyList(),
            imbalances = emptyList(), absorptions = emptyList(),
            setup = buildTradeProSetup(
                stage = SetupStage.EXECUTE, direction = Direction.BULLISH,
                entry = 1.0855, stopLoss = 1.0810, target1 = 1.0920, confidence = 75,
            ),
            stage = SetupStage.EXECUTE, narrative = "Buy setup confirmed",
        )

        val result = computer.computeSignals(litX, analysis, emptyList(), sampleCandles, 1700009000000L)

        // Each source has exactly one *other* distinct source agreeing → +0.04.
        assertEquals(0.84, result.first { it.source == SignalSource.LITX }.confidence, 1e-4)
        assertEquals(0.79, result.first { it.source == SignalSource.TRADEPRO }.confidence, 1e-4)
    }

    @Test
    fun `confluence boost scales and caps with three agreeing sources`() {
        val litX = buildLitXAnalysis(
            direction = Direction.BULLISH,
            entry = 1.0850, stopLoss = 1.0800, tp1 = 1.0900, tp2 = 1.0950,
            score = 80, timestamp = 1700005000000L,
        )
        val analysis = TradeProAnalysis(
            symbol = "EURUSD", flipZone = null, holdZones = emptyList(),
            imbalances = emptyList(), absorptions = emptyList(),
            setup = buildTradeProSetup(
                stage = SetupStage.EXECUTE, direction = Direction.BULLISH,
                entry = 1.0855, stopLoss = 1.0810, target1 = 1.0920, confidence = 75,
            ),
            stage = SetupStage.EXECUTE, narrative = "Buy setup confirmed",
        )
        val divergences = listOf(
            buildSmtDivergence(
                primaryIndex = 5,
                primaryPrice = 1.0790,
                direction = Direction.BULLISH,
                confidence = 65.0,
                confirmationIndex = sampleCandles.lastIndex,
            ),
        )

        val result = computer.computeSignals(litX, analysis, divergences, sampleCandles, 1700009000000L)

        // Two other distinct sources each → +0.08 (the cap).
        assertEquals(0.88, result.first { it.source == SignalSource.LITX }.confidence, 1e-4)
        assertEquals(0.83, result.first { it.source == SignalSource.TRADEPRO }.confidence, 1e-4)
        assertEquals(0.73, result.first { it.source == SignalSource.SMT }.confidence, 1e-4)
    }

    @Test
    fun `no confluence boost when sources disagree in direction`() {
        val litX = buildLitXAnalysis(
            direction = Direction.BULLISH,
            entry = 1.0850, stopLoss = 1.0800, tp1 = 1.0900, tp2 = 1.0950,
            score = 80, timestamp = 1700005000000L,
        )
        val analysis = TradeProAnalysis(
            symbol = "GBPUSD", flipZone = null, holdZones = emptyList(),
            imbalances = emptyList(), absorptions = emptyList(),
            setup = buildTradeProSetup(
                stage = SetupStage.EXECUTE, direction = Direction.BEARISH,
                entry = 1.2650, stopLoss = 1.2700, target1 = 1.2600, confidence = 75,
            ),
            stage = SetupStage.EXECUTE, narrative = "Sell setup confirmed",
        )

        val result = computer.computeSignals(litX, analysis, emptyList(), sampleCandles, 1700009000000L)

        assertEquals(0.80, result.first { it.source == SignalSource.LITX }.confidence, 1e-4)
        assertEquals(0.75, result.first { it.source == SignalSource.TRADEPRO }.confidence, 1e-4)
    }

    @Test
    fun `multiple SMT divergences from the same source do not self-boost`() {
        val divergences = listOf(
            buildSmtDivergence(
                primaryIndex = 3,
                primaryPrice = 1.0800,
                direction = Direction.BULLISH,
                confidence = 72.0,
                confirmationIndex = 6,
            ),
            buildSmtDivergence(
                primaryIndex = 7,
                primaryPrice = 1.0820,
                direction = Direction.BULLISH,
                confidence = 78.0,
                confirmationIndex = sampleCandles.lastIndex,
            ),
        )

        val result = computer.computeSignals(null, null, divergences, sampleCandles, 1700009000000L)

        // Same source → no other distinct source → confidences unchanged.
        assertEquals(0.72, result[0].confidence, 1e-4)
        assertEquals(0.78, result[1].confidence, 1e-4)
    }

    @Test
    fun `confluence never pushes confidence above one`() {
        val litX = buildLitXAnalysis(
            direction = Direction.BULLISH,
            entry = 1.0850, stopLoss = 1.0800, tp1 = 1.0900, tp2 = 1.0950,
            score = 99, timestamp = 1700005000000L,
        )
        val analysis = TradeProAnalysis(
            symbol = "EURUSD", flipZone = null, holdZones = emptyList(),
            imbalances = emptyList(), absorptions = emptyList(),
            setup = buildTradeProSetup(
                stage = SetupStage.EXECUTE, direction = Direction.BULLISH,
                entry = 1.0855, stopLoss = 1.0810, target1 = 1.0920, confidence = 98,
            ),
            stage = SetupStage.EXECUTE, narrative = "Buy setup confirmed",
        )
        val divergences = listOf(
            buildSmtDivergence(
                primaryIndex = 5,
                primaryPrice = 1.0790,
                direction = Direction.BULLISH,
                confidence = 97.0,
                confirmationIndex = sampleCandles.lastIndex,
            ),
        )

        val result = computer.computeSignals(litX, analysis, divergences, sampleCandles, 1700009000000L)

        assertEquals(1.0, result.first { it.source == SignalSource.LITX }.confidence, 1e-4)
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
        confirmationIndex: Int = primaryIndex,
    ): SmtDivergenceDetector.SmtDivergence = SmtDivergenceDetector.SmtDivergence(
        primarySymbol = "EURUSD",
        peerSymbol = "DXY",
        direction = direction,
        type = SmtDivergenceDetector.SmtType.PRIMARY_SWEEP_PEER_FAIL,
        primaryIndex = primaryIndex,
        peerIndex = primaryIndex + 1,
        confirmationIndex = confirmationIndex,
        primaryPrice = primaryPrice,
        peerPrice = primaryPrice + 0.002,
        correlation = 0.85,
        confidence = confidence,
        detail = "EURUSD swept sell-side while DXY held",
    )
}
