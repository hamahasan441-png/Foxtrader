package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.BrokerTradeDraft
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local one-shot handoff from chart analysis to broker execution UI.
 * No credential or order authorization is stored here. A draft expires quickly
 * and is consumed exactly once so stale chart context cannot silently prefill a
 * later account session.
 */
@Singleton
class BrokerTradeDraftStore @Inject constructor() {
    private val pending = AtomicReference<BrokerTradeDraft?>(null)

    fun stage(draft: BrokerTradeDraft) {
        require(draft.symbol.isNotBlank()) { "Draft symbol is required" }
        require(draft.referenceEntryPrice.isFinite() && draft.referenceEntryPrice > 0.0) { "Draft entry price is invalid" }
        pending.set(draft)
    }

    fun consume(now: Long = System.currentTimeMillis()): BrokerTradeDraft? {
        val draft = pending.getAndSet(null) ?: return null
        val age = now - draft.createdAt
        return draft.takeIf { age in 0..MAX_AGE_MS }
    }

    fun clear() {
        pending.set(null)
    }

    private companion object {
        const val MAX_AGE_MS = 5 * 60_000L
    }
}
