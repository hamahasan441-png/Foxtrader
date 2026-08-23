package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitProContext
import com.foxtrader.app.domain.model.LitSignal
import com.foxtrader.app.domain.model.LitStage
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitXConfidence
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.LitXSignal
import com.foxtrader.app.domain.model.LitXStage
import com.foxtrader.app.domain.model.SignalIdentity
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.signalintel.SignalEvidenceReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SignalStableIdentityDedupTest {
    private val computer = SignalComputer(SignalEvidenceReducer())
    private val symbol = "EURUSD"
    private val timeframe = Timeframe.M1
    private val candles = List(4) { index ->
        val base = 1.1000 + index * 0.0010
        Candle(
            timestamp = 1_700_000_000_000L + index * 60_000L,
            open = base,
            high = base + 0.0020,
            low = base - 0.0020,
            close = base + 0.0005,
            volume = 100.0,
        )
    }

    @Test
    fun `canonical LiT replaces strategy mirror with one chart event`() {
        val index = candles.lastIndex
        val timestamp = candles[index].timestamp
        val id = SignalIdentity.lit(symbol, timeframe, timestamp, Direction.BULLISH, index)
        val mirror = ChartSignal(
            id = id,
            source = SignalSource.STRATEGY,
            direction = Direction.BULLISH,
            entry = 1.1035,
            sl = 1.1010,
            tp = 1.1085,
            barIndex = index,
            timestamp = timestamp,
            confidence = 0.71,
            isLive = true,
            label = "LIT Institutional Entry",
        )
        val canonical = LitAnalysis(
            symbol = symbol,
            timeframe = timeframe,
            stage = LitStage.VALIDATED,
            signal = LitSignal(
                symbol = symbol,
                timeframe = timeframe,
                direction = Direction.BULLISH,
                entry = 1.1035,
                stopLoss = 1.1010,
                takeProfit = 1.1085,
                confidence = 82,
                sweepIndex = 1,
                shiftIndex = 2,
                confirmationIndex = index,
                timestamp = timestamp,
                confirmations = listOf("SEQUENCE_VALIDATED"),
                rationale = "fixture",
            ),
            narrative = "fixture",
            context = LitProContext(),
        )

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = listOf(mirror),
            litAnalysis = canonical,
            latestConfirmedIndex = index,
        )

        assertEquals(1, result.size)
        assertEquals(id, result.single().id)
        assertEquals(SignalSource.LIT, result.single().source)
        assertEquals(0.82, result.single().confidence, 1e-9)
    }

    @Test
    fun `canonical LiTX replaces strategy mirror with one chart event`() {
        val index = candles.lastIndex
        val timestamp = candles[index].timestamp
        val id = SignalIdentity.litX(symbol, timeframe, timestamp, Direction.BEARISH, index)
        val mirror = ChartSignal(
            id = id,
            source = SignalSource.STRATEGY,
            direction = Direction.BEARISH,
            entry = 1.1030,
            sl = 1.1060,
            tp = 1.0970,
            barIndex = index,
            timestamp = timestamp,
            confidence = 0.70,
            isLive = true,
            label = "LIT X Institutional",
        )
        val canonicalSignal = LitXSignal(
            symbol = symbol,
            timeframe = timeframe,
            direction = Direction.BEARISH,
            stage = LitXStage.VALIDATED,
            entry = 1.1030,
            stopLoss = 1.1060,
            takeProfit1 = 1.0970,
            takeProfit2 = 1.0940,
            riskReward = 2.0,
            confidence = LitXConfidence(79, LitXGrade.A, emptyList()),
            zone = null,
            rationale = "fixture",
            timestamp = timestamp,
            confirmationIndex = index,
            confirmations = listOf("SHIFT_CONFIRMED"),
        )
        val canonical = LitXAnalysis(
            symbol = symbol,
            timeframe = timeframe,
            stage = LitXStage.VALIDATED,
            bias = Bias.BEARISH,
            htfBias = Bias.BEARISH,
            displacement = null,
            mitigationBlocks = emptyList(),
            premiumDiscount = null,
            signal = canonicalSignal,
            narrative = "fixture",
            timestamp = timestamp,
        )

        val result = computer.computeSignals(
            litXAnalysis = canonical,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = listOf(mirror),
            latestConfirmedIndex = index,
        )

        assertEquals(1, result.size)
        assertEquals(id, result.single().id)
        assertEquals(SignalSource.LITX, result.single().source)
        assertEquals(0.79, result.single().confidence, 1e-9)
    }

    @Test
    fun `different strategy identities on same bar are not over-deduplicated`() {
        val index = candles.lastIndex
        val timestamp = candles[index].timestamp
        val first = ChartSignal(
            id = "strategy_A_${index}_${timestamp}",
            source = SignalSource.STRATEGY,
            direction = Direction.BULLISH,
            entry = 1.1035,
            sl = 1.1010,
            tp = 1.1085,
            barIndex = index,
            timestamp = timestamp,
            confidence = 0.70,
            isLive = true,
        )
        val second = first.copy(id = "strategy_B_${index}_${timestamp}", confidence = 0.74)

        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = listOf(first, second),
            latestConfirmedIndex = index,
        )

        assertEquals(2, result.size)
        assertNotEquals(result[0].id, result[1].id)
    }

    @Test
    fun `stable identity normalizes symbol without mixing methodologies`() {
        val index = candles.lastIndex
        val timestamp = candles[index].timestamp
        val litLower = SignalIdentity.lit(" eurusd ", timeframe, timestamp, Direction.BULLISH, index)
        val litUpper = SignalIdentity.lit("EURUSD", timeframe, timestamp, Direction.BULLISH, index)
        val litX = SignalIdentity.litX("EURUSD", timeframe, timestamp, Direction.BULLISH, index)

        assertEquals(litUpper, litLower)
        assertNotEquals(litUpper, litX)
    }
}
