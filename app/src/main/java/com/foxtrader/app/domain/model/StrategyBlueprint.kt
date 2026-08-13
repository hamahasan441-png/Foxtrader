package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Combinators for the visual strategy builder. */
@Serializable
enum class LogicOp { AND, OR, NOT }

@Serializable
enum class StrategyConditionKind {
    MARKET_STRUCTURE,
    LIQUIDITY,
    SMT,
    FVG,
    ORDER_BLOCK,
    INDICATOR,
    SESSION,
    RISK,
}

@Serializable
data class StrategyCondition(
    val id: String = UUID.randomUUID().toString(),
    val kind: StrategyConditionKind,
    val label: String,
    val negated: Boolean = false,
)

@Serializable
data class StrategyAction(
    val entry: String = "Market",
    val stopLoss: String = "Structure / ATR",
    val takeProfit: String = "1.5R – 3R",
    val riskPercent: Double = 1.0,
)

/**
 * User-authored strategy definition. This is a research template, not a
 * promise of profit. Evaluation still happens in the backtester.
 */
@Serializable
data class StrategyBlueprint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val combinator: LogicOp = LogicOp.AND,
    val conditions: List<StrategyCondition> = emptyList(),
    val action: StrategyAction = StrategyAction(),
    val market: String = "Any",
    val timeframe: String = "H1",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isValid: Boolean
        get() = name.isNotBlank() && conditions.isNotEmpty()

    fun summary(): String {
        if (conditions.isEmpty()) return "No conditions yet"
        val joiner = when (combinator) {
            LogicOp.AND -> " AND "
            LogicOp.OR -> " OR "
            LogicOp.NOT -> " NOT "
        }
        val body = conditions.joinToString(joiner) { condition ->
            if (condition.negated) "NOT ${condition.label}" else condition.label
        }
        return "IF $body THEN ${action.entry}"
    }
}

object StrategyConditionCatalog {
    val defaults: List<StrategyCondition>
        get() = listOf(
            StrategyCondition(kind = StrategyConditionKind.MARKET_STRUCTURE, label = "BOS in trade direction"),
            StrategyCondition(kind = StrategyConditionKind.MARKET_STRUCTURE, label = "CHOCH against prior swing"),
            StrategyCondition(kind = StrategyConditionKind.MARKET_STRUCTURE, label = "MSS confirmed"),
            StrategyCondition(kind = StrategyConditionKind.LIQUIDITY, label = "Liquidity sweep"),
            StrategyCondition(kind = StrategyConditionKind.FVG, label = "Unfilled FVG in premium/discount"),
            StrategyCondition(kind = StrategyConditionKind.ORDER_BLOCK, label = "Fresh order block tap"),
            StrategyCondition(kind = StrategyConditionKind.SMT, label = "SMT divergence vs correlated pair"),
            StrategyCondition(kind = StrategyConditionKind.INDICATOR, label = "EMA 20 above EMA 50"),
            StrategyCondition(kind = StrategyConditionKind.INDICATOR, label = "RSI leaving 30/70"),
            StrategyCondition(kind = StrategyConditionKind.SESSION, label = "London or New York kill zone"),
            StrategyCondition(kind = StrategyConditionKind.RISK, label = "Risk ≤ configured per-trade cap"),
        )
}
