package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.AutomationCandidate
import com.foxtrader.app.domain.model.AutomationDecision
import com.foxtrader.app.domain.model.AutomationEnvironment
import com.foxtrader.app.domain.model.AutomationMode
import com.foxtrader.app.domain.model.AutomationPolicy
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7AutomationEngineTest {
    private val engine = Phase7AutomationEngine()
    private val candidate = AutomationCandidate(
        id = "xau-h1-1",
        symbol = "XAUUSD",
        direction = Direction.BULLISH,
        confidence = 88,
        confirmedBar = true,
        phase4Actionable = true,
        sourceIsTrustworthy = true,
        generatedAt = 1L,
    )

    @Test fun liveAlwaysQueuesForManualReview() {
        val result = engine.evaluate(candidate, AutomationPolicy(mode = AutomationMode.AUTO_PAPER_DEMO), AutomationEnvironment.LIVE)
        assertTrue(result is AutomationDecision.QueuedForReview)
    }

    @Test fun paperCanUseEligibleAutoRouting() {
        val result = engine.evaluate(candidate, AutomationPolicy(mode = AutomationMode.AUTO_PAPER_DEMO), AutomationEnvironment.PAPER)
        assertTrue(result is AutomationDecision.EligibleForSimulatedAutoExecution)
    }

    @Test fun untrustedDataFailsClosed() {
        val result = engine.evaluate(candidate.copy(sourceIsTrustworthy = false), AutomationPolicy(), AutomationEnvironment.PAPER)
        assertTrue(result is AutomationDecision.Rejected)
    }
    @Test fun queueDeduplicatesSameCandidate() {
        val queue = Phase7AutomationQueue(engine)
        val policy = AutomationPolicy(mode = AutomationMode.REVIEW_QUEUE, cooldownMinutes = 1)
        val first = queue.route(candidate, policy, AutomationEnvironment.LIVE)
        val second = queue.route(candidate, policy, AutomationEnvironment.LIVE)
        assertTrue(first is AutomationRouteResult.Queued)
        assertTrue(second is AutomationRouteResult.Duplicate)
    }

    @Test fun queueIsBounded() {
        val queue = Phase7AutomationQueue(engine)
        val policy = AutomationPolicy(mode = AutomationMode.REVIEW_QUEUE, maxQueuedSignals = 2, cooldownMinutes = 1)
        queue.route(candidate.copy(id = "a", symbol = "XAUUSD", generatedAt = 1L), policy, AutomationEnvironment.LIVE)
        queue.route(candidate.copy(id = "b", symbol = "EURUSD", generatedAt = 2L), policy, AutomationEnvironment.LIVE)
        queue.route(candidate.copy(id = "c", symbol = "BTCUSD", generatedAt = 3L), policy, AutomationEnvironment.LIVE)
        assertTrue(queue.snapshot().pending.size == 2)
    }

}
