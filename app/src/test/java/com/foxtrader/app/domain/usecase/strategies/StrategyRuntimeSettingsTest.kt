package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StrategyRuntimeSettingsTest {

    @After
    fun tearDown() {
        StrategyRuntimeSettingsRegistry.resetAll()
    }

    @Test
    fun `neutral defaults preserve canonical signal`() {
        val original = signal(direction = Direction.BULLISH, confidence = 72, takeProfit = 106.0)
        val filtered = StrategyRuntimeSettingsRegistry.apply(StrategyType.TREND_FOLLOWING, original)
        assertEquals(original, filtered)
    }

    @Test
    fun `direction controls fail closed`() {
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.TREND_FOLLOWING,
            StrategyRuntimeSettings(allowBullish = false, allowBearish = true),
        )
        assertNull(
            StrategyRuntimeSettingsRegistry.apply(
                StrategyType.TREND_FOLLOWING,
                signal(direction = Direction.BULLISH),
            ),
        )
        assertNotNull(
            StrategyRuntimeSettingsRegistry.apply(
                StrategyType.TREND_FOLLOWING,
                signal(direction = Direction.BEARISH, stopLoss = 102.0, takeProfit = 94.0),
            ),
        )
    }

    @Test
    fun `minimum confidence uses same 60 fallback as live strategy engine`() {
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.MEAN_REVERSION,
            StrategyRuntimeSettings(minimumConfidence = 61),
        )
        assertNull(
            StrategyRuntimeSettingsRegistry.apply(
                StrategyType.MEAN_REVERSION,
                signal(direction = Direction.BULLISH, confidence = null),
            ),
        )
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.MEAN_REVERSION,
            StrategyRuntimeSettings(minimumConfidence = 60),
        )
        assertNotNull(
            StrategyRuntimeSettingsRegistry.apply(
                StrategyType.MEAN_REVERSION,
                signal(direction = Direction.BULLISH, confidence = null),
            ),
        )
    }

    @Test
    fun `minimum risk reward rejects weaker canonical target`() {
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.BREAKOUT,
            StrategyRuntimeSettings(minimumRiskReward = 3.1),
        )
        // Entry 100, stop 98 = 2 risk; target 106 = 6 reward = exactly 3R.
        assertNull(
            StrategyRuntimeSettingsRegistry.apply(
                StrategyType.BREAKOUT,
                signal(direction = Direction.BULLISH, stopLoss = 98.0, takeProfit = 106.0),
            ),
        )
    }

    @Test
    fun `target risk reward override preserves entry and stop and rewrites only target`() {
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.PATTERN,
            StrategyRuntimeSettings(targetRiskReward = 2.5),
        )
        val adjusted = StrategyRuntimeSettingsRegistry.apply(
            StrategyType.PATTERN,
            signal(direction = Direction.BULLISH, stopLoss = 98.0, takeProfit = 110.0),
        )
        requireNotNull(adjusted)
        assertEquals(100.0, adjusted.entry, 0.0)
        assertEquals(98.0, adjusted.stopLoss, 0.0)
        assertEquals(105.0, adjusted.takeProfit, 1e-12)
    }

    @Test
    fun `target risk reward cannot be sanitized below minimum risk reward`() {
        val sanitized = StrategyRuntimeSettings(
            minimumRiskReward = 3.0,
            targetRiskReward = 2.0,
        ).sanitized()

        assertEquals(3.0, sanitized.minimumRiskReward, 0.0)
        assertEquals(3.0, sanitized.targetRiskReward, 0.0)
    }

    @Test
    fun `definition freezes settings while newly resolved definition sees later change`() {
        val oldDefinition = StrategyDefinition(
            name = "Test",
            type = StrategyType.CONFLUENCE,
            description = "",
            minimumBars = 1,
            function = { _, _ -> signal(direction = Direction.BULLISH, confidence = 80) },
        )

        assertNotNull(oldDefinition.function(emptyList(), 0))
        StrategyRuntimeSettingsRegistry.set(
            StrategyType.CONFLUENCE,
            StrategyRuntimeSettings(allowBullish = false),
        )

        // A run that already resolved its definition stays reproducible.
        assertNotNull(oldDefinition.function(emptyList(), 0))

        // The next live/research resolution receives the new settings.
        val newDefinition = StrategyDefinition(
            name = "Test",
            type = StrategyType.CONFLUENCE,
            description = "",
            minimumBars = 1,
            function = { _, _ -> signal(direction = Direction.BULLISH, confidence = 80) },
        )
        assertNull(newDefinition.function(emptyList(), 0))
    }

    private fun signal(
        direction: Direction,
        confidence: Int? = 75,
        stopLoss: Double = if (direction == Direction.BULLISH) 98.0 else 102.0,
        takeProfit: Double = if (direction == Direction.BULLISH) 106.0 else 94.0,
    ) = StrategySignal(
        index = 0,
        timestamp = 1_000L,
        direction = direction,
        entry = 100.0,
        stopLoss = stopLoss,
        takeProfit = takeProfit,
        confidence = confidence,
        setupType = "TEST",
    )
}
