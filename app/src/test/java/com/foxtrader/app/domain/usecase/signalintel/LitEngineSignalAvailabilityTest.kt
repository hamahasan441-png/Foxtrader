package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitEventType
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.domain.model.LitPoiKind
import com.foxtrader.app.domain.model.LitPoiZone
import com.foxtrader.app.domain.model.LitProContext
import com.foxtrader.app.domain.model.LitScob
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LitEngineSignalAvailabilityTest {

    @Test
    fun `confirmed POI rejection emits without also requiring regular divergence`() {
        val candles = fixture()
        val context = LitProContext(
            trend = Direction.BULLISH,
            inducement = LitLevel(LitEventType.IDM, Direction.BULLISH, 99.0, 58, 60, swept = true),
            bos = LitLevel(LitEventType.BOS, Direction.BEARISH, 99.2, 62, 65),
            choch = LitLevel(LitEventType.CHOCH, Direction.BULLISH, 101.0, 66, 70),
            poi = LitPoiZone(
                kind = LitPoiKind.DECISIONAL,
                direction = Direction.BULLISH,
                low = 99.0,
                high = 100.0,
                originIndex = 69,
                confirmationIndex = 70,
                quality = 80,
            ),
            scob = LitScob(
                direction = Direction.BULLISH,
                low = 99.25,
                high = 99.82,
                originIndex = 72,
                confirmationIndex = 72,
                quality = 82,
            ),
        )
        val structure = object : LitProStructureDetector() {
            override fun detect(candles: List<Candle>, config: LitConfig): LitProContext = context
        }
        val divergence = object : LitPoiDivergenceDetector() {
            override fun detect(
                candles: List<Candle>,
                retestIndex: Int,
                direction: Direction,
                lookback: Int,
                rsiPeriod: Int,
                minRsiGap: Double,
                pivotLeft: Int,
                pivotRight: Int,
            ): Divergence? = null
        }
        val engine = LitEngine(
            smcDetector = SmcDetector(),
            analyzeStructure = AnalyzeMarketStructureUseCase(),
            displacementDetector = DisplacementDetector(),
            premiumDiscount = PremiumDiscountCalculator(),
            structureDetector = structure,
            poiDivergenceDetector = divergence,
        )

        val analysis = engine.analyze(
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            candles = candles,
            config = LitConfig(
                minConfidence = 50,
                minRiskReward = 1.0,
                displacementAtrMultiple = 0.8,
                requireDirectionalZone = false,
                requirePoiDivergence = true,
                requireScob = false,
                stopAtrBuffer = 0.02,
            ),
        )

        val signal = analysis.signal
        assertNotNull(analysis.narrative, signal)
        requireNotNull(signal)
        assertEquals(candles.lastIndex, signal.confirmationIndex)
        assertEquals(Direction.BULLISH, signal.direction)
        assertTrue(signal.confirmations.contains("SCOB_MOMENTUM_CONFIRMATION"))
        assertTrue(signal.confirmations.contains("NON_REPAINT"))
    }

    private fun fixture(): List<Candle> = (0..72).map { index ->
        val base = 100.0 + if (index % 2 == 0) 0.04 else -0.04
        when (index) {
            55 -> candle(index, 102.0, 105.0, 101.8, 102.2)
            69 -> candle(index, 100.0, 100.1, 99.0, 99.2)
            70 -> candle(index, 99.2, 101.8, 99.1, 101.6)
            // First mitigation can be indecisive; the next closed candle gives
            // the causal SCOB rejection confirmation.
            71 -> candle(index, 100.05, 100.15, 99.75, 99.90)
            72 -> candle(index, 99.25, 100.0, 99.10, 99.82)
            else -> candle(index, base - 0.03, base + 0.10, base - 0.10, base + 0.03)
        }
    }

    private fun candle(index: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        timestamp = 1_700_000_000_000L + index * 15 * 60_000L,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000.0,
    )
}
