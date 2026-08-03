package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.tradepro.AlertPriority
import com.foxtrader.app.domain.model.tradepro.AlertRule
import com.foxtrader.app.domain.model.tradepro.AlertStage
import com.foxtrader.app.domain.model.tradepro.AlertTriggerType
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TriggeredAlert
import javax.inject.Inject

/**
 * Evaluates user-defined [AlertRule]s against live TRADEPRO reads and emits [TriggeredAlert]s,
 * with per-rule+symbol cooldown so the same condition doesn't spam.
 *
 * Pure and deterministic: cooldown state is passed in and updated state is returned, rather than held
 * as mutable engine state — which keeps the whole thing trivially unit-testable and thread-safe.
 */
class AlertRuleEngine @Inject constructor() {

    /**
     * Evaluate a single [rule] against one symbol's [analysis].
     *
     * @param previousBias the Flip Zone bias from the prior evaluation (for BIAS_FLIP); null if unknown.
     * @return a [TriggeredAlert] when the condition is met, else null. Cooldown is NOT applied here;
     *   use [evaluateBatch] for cooldown-aware firing.
     */
    fun evaluate(
        rule: AlertRule,
        symbol: String,
        analysis: TradeProAnalysis,
        currentPrice: Double,
        previousBias: Bias? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TriggeredAlert? {
        if (!rule.enabled) return null
        if (!rule.appliesToAllSymbols && !rule.symbol.equals(symbol, ignoreCase = true)) return null

        val matched = when (rule.trigger) {
            AlertTriggerType.EXECUTABLE_SETUP -> analysis.setup?.isExecutable == true
            AlertTriggerType.STAGE_REACHED -> analysis.stage.ordinal >= toSetupStage(rule.minStage).ordinal
            AlertTriggerType.ZONE_ENTERED -> priceInAnyZone(analysis, currentPrice)
            AlertTriggerType.HTF_ALIGNED -> analysis.setup?.confluences?.any { it.startsWith(HTF_ALIGNED_PREFIX) } == true
            AlertTriggerType.CONFIDENCE_ABOVE -> (analysis.setup?.confidence ?: 0) > rule.threshold
            AlertTriggerType.RR_ABOVE -> (analysis.setup?.riskReward ?: 0.0) > rule.threshold
            AlertTriggerType.BIAS_FLIP -> isBiasFlip(previousBias, analysis.flipZone?.bias)
        }
        if (!matched) return null

        return TriggeredAlert(
            ruleId = rule.id,
            ruleName = rule.name,
            symbol = symbol,
            priority = priorityFor(rule, analysis),
            message = buildMessage(rule, symbol, analysis),
            triggeredAtEpochMs = nowEpochMs,
        )
    }

    /**
     * Evaluate every rule against every provided symbol analysis, honouring cooldown.
     *
     * @param analysesBySymbol current reads keyed by symbol.
     * @param currentPriceBySymbol latest price per symbol (for ZONE_ENTERED / distance logic).
     * @param previousBiasBySymbol prior bias per symbol (for BIAS_FLIP).
     * @param lastFiredByKey epoch-ms of the last fire per [TriggeredAlert.dedupeKey].
     * @return the fired alerts plus the updated last-fired map to persist for the next pass.
     */
    fun evaluateBatch(
        rules: List<AlertRule>,
        analysesBySymbol: Map<String, TradeProAnalysis>,
        currentPriceBySymbol: Map<String, Double>,
        previousBiasBySymbol: Map<String, Bias> = emptyMap(),
        lastFiredByKey: Map<String, Long> = emptyMap(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): BatchResult {
        val fired = ArrayList<TriggeredAlert>()
        val updatedLastFired = HashMap(lastFiredByKey)

        for (rule in rules) {
            if (!rule.enabled) continue
            val targetSymbols = if (rule.appliesToAllSymbols) {
                analysesBySymbol.keys
            } else {
                analysesBySymbol.keys.filter { it.equals(rule.symbol, ignoreCase = true) }
            }
            for (symbol in targetSymbols) {
                val analysis = analysesBySymbol[symbol] ?: continue
                val price = currentPriceBySymbol[symbol] ?: 0.0
                val alert = evaluate(rule, symbol, analysis, price, previousBiasBySymbol[symbol], nowEpochMs)
                    ?: continue
                val lastFired = updatedLastFired[alert.dedupeKey]
                val cooldownMs = rule.cooldownMinutes.coerceAtLeast(0) * 60_000L
                if (lastFired != null && nowEpochMs - lastFired < cooldownMs) continue
                fired += alert
                updatedLastFired[alert.dedupeKey] = nowEpochMs
            }
        }

        fired.sortWith(compareByDescending<TriggeredAlert> { it.priority.rank }.thenBy { it.symbol })
        return BatchResult(fired, updatedLastFired)
    }

    /** Result of a cooldown-aware batch evaluation. */
    data class BatchResult(
        val alerts: List<TriggeredAlert>,
        val lastFiredByKey: Map<String, Long>,
    )

    // --- Condition helpers ---

    private fun priceInAnyZone(analysis: TradeProAnalysis, currentPrice: Double): Boolean {
        if (currentPrice <= 0.0) return false
        return analysis.holdZones.any { currentPrice in it.low..it.high }
    }

    private fun isBiasFlip(previous: Bias?, current: Bias?): Boolean {
        if (previous == null || current == null) return false
        if (current == Bias.NEUTRAL || previous == Bias.NEUTRAL) return false
        return previous != current
    }

    private fun priorityFor(rule: AlertRule, analysis: TradeProAnalysis): AlertPriority {
        val executable = analysis.setup?.isExecutable == true
        val confidence = analysis.setup?.confidence ?: 0
        return when {
            executable && confidence >= HIGH_CONFIDENCE -> AlertPriority.CRITICAL
            executable -> AlertPriority.HIGH
            analysis.stage == SetupStage.CONFIRMATION -> AlertPriority.MEDIUM
            rule.trigger == AlertTriggerType.BIAS_FLIP -> AlertPriority.MEDIUM
            else -> AlertPriority.LOW
        }
    }

    private fun buildMessage(rule: AlertRule, symbol: String, analysis: TradeProAnalysis): String {
        val setup = analysis.setup
        val dir = setup?.direction?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
        val base = "$symbol \u2014 ${rule.conditionText()}"
        return when {
            setup != null && setup.isExecutable ->
                "$base. ${dir} setup ready: entry ${"%.4f".format(setup.entry)}, ${setup.confidence}% conf, RR ${"%.1f".format(setup.riskReward)}."
            setup != null ->
                "$base. Stage ${analysis.stage.name}, ${setup.confidence}% conf."
            else -> "$base."
        }
    }

    private fun toSetupStage(stage: AlertStage): SetupStage = when (stage) {
        AlertStage.LEVEL -> SetupStage.LEVEL
        AlertStage.ZONE -> SetupStage.ZONE
        AlertStage.CONFIRMATION -> SetupStage.CONFIRMATION
        AlertStage.EXECUTE -> SetupStage.EXECUTE
    }

    companion object {
        private const val HTF_ALIGNED_PREFIX = "HTF_ALIGNED"
        private const val HIGH_CONFIDENCE = 75
    }
}
