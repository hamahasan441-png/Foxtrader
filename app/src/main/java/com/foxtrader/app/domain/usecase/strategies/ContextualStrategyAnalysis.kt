package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector

/**
 * One strategy-package result plus its external peer/HTF/provider context.
 *
 * Phase 2 keeps the established package scoring untouched while exposing the
 * external evidence beside it. The only hard gate here is provenance/freshness:
 * simulated context can never authorize an executable trade decision.
 *
 * Later phases can fold SMT/HTF evidence into package confidence without
 * changing callers again because they already consume this unified result.
 */
data class ContextualStrategyAnalysis(
    val packageAnalysis: StrategyPackageEngine.Analysis,
    val externalAnalysis: StrategyExternalContextAnalyzer.Analysis,
    val allEvidence: List<StrategyPackageEngine.Evidence>,
    val decisionEligible: Boolean,
) {
    val signal: StrategySignal?
        get() = packageAnalysis.signal.takeIf { decisionEligible }

    val smtDivergences: List<SmtDivergenceDetector.SmtDivergence>
        get() = externalAnalysis.smtDivergences

    val higherTimeframeBiases: Map<Timeframe, Bias>
        get() = externalAnalysis.higherTimeframeBiases
}
