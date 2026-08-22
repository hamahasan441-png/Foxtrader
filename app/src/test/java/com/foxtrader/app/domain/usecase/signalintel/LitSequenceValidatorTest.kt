package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitEventType
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.domain.model.LitProContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LitSequenceValidatorTest {
    private val validator = LitSequenceValidator()

    @Test
    fun `accepts repository-defined idm bos choch chronology`() {
        val result = validator.validate(
            context = context(idmIndex = 20, bosIndex = 25, chochIndex = 31),
            config = LitConfig(maxIdmToBosBars = 8, maxBosToChochBars = 8),
        )

        assertTrue(result.reason, result.valid)
        assertTrue(result.idmToBosBars == 5)
        assertTrue(result.bosToChochBars == 6)
    }

    @Test
    fun `rejects idm that is confirmed after bos`() {
        val result = validator.validate(
            context = context(idmIndex = 26, bosIndex = 25, chochIndex = 31),
            config = LitConfig(),
        )

        assertFalse(result.valid)
        assertTrue(result.reason.contains("IDM must be confirmed before BOS"))
    }

    @Test
    fun `rejects stale idm to bos transition`() {
        val result = validator.validate(
            context = context(idmIndex = 10, bosIndex = 25, chochIndex = 31),
            config = LitConfig(maxIdmToBosBars = 8, maxBosToChochBars = 8),
        )

        assertFalse(result.valid)
        assertTrue(result.reason.contains("IDM->BOS sequence expired"))
    }

    @Test
    fun `rejects stale bos to choch transition`() {
        val result = validator.validate(
            context = context(idmIndex = 20, bosIndex = 24, chochIndex = 40),
            config = LitConfig(maxIdmToBosBars = 8, maxBosToChochBars = 8),
        )

        assertFalse(result.valid)
        assertTrue(result.reason.contains("BOS->CHOCH sequence expired"))
    }

    @Test
    fun `rejects same-direction bos and choch`() {
        val ctx = context(idmIndex = 20, bosIndex = 25, chochIndex = 31).copy(
            bos = level(LitEventType.BOS, Direction.BULLISH, 25),
        )
        val result = validator.validate(ctx, LitConfig())

        assertFalse(result.valid)
        assertTrue(result.reason.contains("BOS must be opposite"))
    }

    @Test
    fun `rejects confirmation boundary that predates origin`() {
        val invalidIdm = LitLevel(
            type = LitEventType.IDM,
            direction = Direction.BULLISH,
            price = 100.0,
            originIndex = 22,
            confirmationIndex = 20,
            swept = true,
        )
        val result = validator.validate(
            context = context(idmIndex = 20, bosIndex = 25, chochIndex = 31).copy(inducement = invalidIdm),
            config = LitConfig(),
        )

        assertFalse(result.valid)
        assertTrue(result.reason.contains("invalid origin/confirmation"))
    }

    private fun context(idmIndex: Int, bosIndex: Int, chochIndex: Int) = LitProContext(
        inducement = level(LitEventType.IDM, Direction.BULLISH, idmIndex, swept = true),
        bos = level(LitEventType.BOS, Direction.BEARISH, bosIndex),
        choch = level(LitEventType.CHOCH, Direction.BULLISH, chochIndex),
    )

    private fun level(
        type: LitEventType,
        direction: Direction,
        confirmationIndex: Int,
        swept: Boolean = false,
    ) = LitLevel(
        type = type,
        direction = direction,
        price = 100.0,
        originIndex = (confirmationIndex - 2).coerceAtLeast(0),
        confirmationIndex = confirmationIndex,
        swept = swept,
    )
}
