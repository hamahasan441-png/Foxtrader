package com.foxtrader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyBlueprintTest {

    @Test
    fun `empty conditions are invalid`() {
        val blueprint = StrategyBlueprint(name = "Test")
        assertFalse(blueprint.isValid)
        assertEquals("No conditions yet", blueprint.summary())
    }

    @Test
    fun `and summary reads as a research sentence`() {
        val blueprint = StrategyBlueprint(
            name = "Sweep reversal",
            combinator = LogicOp.AND,
            conditions = listOf(
                StrategyCondition(kind = StrategyConditionKind.LIQUIDITY, label = "Liquidity sweep"),
                StrategyCondition(kind = StrategyConditionKind.FVG, label = "Unfilled FVG"),
            ),
        )
        assertTrue(blueprint.isValid)
        assertEquals("IF Liquidity sweep AND Unfilled FVG THEN Market", blueprint.summary())
    }

    @Test
    fun `catalog covers the smart money vocabulary`() {
        val kinds = StrategyConditionCatalog.defaults.map { it.kind }.toSet()
        assertTrue(kinds.contains(StrategyConditionKind.MARKET_STRUCTURE))
        assertTrue(kinds.contains(StrategyConditionKind.LIQUIDITY))
        assertTrue(kinds.contains(StrategyConditionKind.SMT))
        assertTrue(kinds.contains(StrategyConditionKind.ORDER_BLOCK))
        assertTrue(kinds.contains(StrategyConditionKind.SESSION))
    }
}
