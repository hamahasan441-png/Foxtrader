package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Local commercial plan. There is no payment processor in this build —
 * the plan is a product-boundary flag so upgrade UX can exist without
 * locking working research tools behind an unpaid wall.
 */
@Serializable
enum class SubscriptionPlan {
    FREE,
    TRIAL,
    PRO,
}

@Serializable
data class SubscriptionState(
    val plan: SubscriptionPlan = SubscriptionPlan.FREE,
    val trialEndsAtEpochMs: Long = 0L,
) {
    fun isPro(nowMs: Long = System.currentTimeMillis()): Boolean = when (plan) {
        SubscriptionPlan.PRO -> true
        SubscriptionPlan.TRIAL -> trialEndsAtEpochMs > nowMs
        SubscriptionPlan.FREE -> false
    }

    fun label(nowMs: Long = System.currentTimeMillis()): String = when {
        plan == SubscriptionPlan.PRO -> "Pro"
        plan == SubscriptionPlan.TRIAL && trialEndsAtEpochMs > nowMs -> "Trial"
        else -> "Free"
    }
}

enum class PremiumSurface {
    CLOUD_SYNC,
    STRATEGY_OPTIMIZER,
    MONTE_CARLO,
    MULTI_PROVIDER_AI,
    UNLIMITED_ALERT_RULES,
}
