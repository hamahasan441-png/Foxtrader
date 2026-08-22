package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalFusionComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEvidenceReducerTest {
    private val reducer = SignalEvidenceReducer()

    @Test
    fun `litx lit and sms count as one supportive evidence family`() {
        val reduced = reducer.reduce(
            listOf(
                component("LiTX", Direction.BULLISH, 78),
                component("LiT", Direction.BULLISH, 86),
                component("SMS", Direction.BULLISH, 81),
            ),
        )

        assertEquals(1, reduced.size)
        assertEquals("LiT", reduced.single().name)
        assertEquals(1, reducer.distinctFamilyCount(reduced))
        assertEquals(SignalEvidenceReducer.Family.STRUCTURE_LIQUIDITY, reducer.family(reduced.single().name))
    }

    @Test
    fun `opposite directions inside same correlated family remain visible`() {
        val reduced = reducer.reduce(
            listOf(
                component("LiTX", Direction.BULLISH, 82),
                component("LiT", Direction.BULLISH, 75),
                component("SMS", Direction.BEARISH, 84),
            ),
        )

        assertEquals(2, reduced.size)
        assertTrue(reduced.any { it.direction == Direction.BULLISH })
        assertTrue(reduced.any { it.direction == Direction.BEARISH })
        assertEquals(1, reducer.distinctFamilyCount(reduced))
    }

    @Test
    fun `smt and tradepro remain independent families`() {
        val reduced = reducer.reduce(
            listOf(
                component("LiT", Direction.BULLISH, 80),
                component("SMT", Direction.BULLISH, 76),
                component("TradePro", Direction.BULLISH, 83),
            ),
        )

        assertEquals(3, reduced.size)
        assertEquals(3, reducer.distinctFamilyCount(reduced))
    }

    @Test
    fun `inactive components cannot influence fusion evidence`() {
        val reduced = reducer.reduce(
            listOf(
                component("LiT", Direction.BULLISH, 70, active = false),
                component("SMT", Direction.BEARISH, 80),
            ),
        )

        assertEquals(1, reduced.size)
        assertEquals("SMT", reduced.single().name)
    }

    private fun component(
        name: String,
        direction: Direction,
        score: Int,
        active: Boolean = true,
    ) = SignalFusionComponent(
        name = name,
        direction = direction,
        score = score,
        active = active,
        detail = "test",
    )
}
