package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.SignalFusionComponent
import javax.inject.Inject

/**
 * Collapses correlated signal engines into evidence families before confidence
 * fusion. Multiple implementations that read the same structure/liquidity facts
 * must not be counted as independent confirmations simply because they have
 * different product names.
 */
class SignalEvidenceReducer @Inject constructor() {

    enum class Family {
        STRUCTURE_LIQUIDITY,
        DIVERGENCE,
        COMPOSITE,
        OTHER,
    }

    /**
     * Keep the strongest component for each (family, direction) pair.
     *
     * Direction is part of the key so disagreement inside one correlated family
     * remains visible as conflict instead of being silently discarded.
     */
    fun reduce(components: List<SignalFusionComponent>): List<SignalFusionComponent> =
        components
            .filter { it.active }
            .groupBy { family(it.name) to it.direction }
            .values
            .mapNotNull { group ->
                group.maxWithOrNull(compareBy<SignalFusionComponent> { it.score }.thenBy { it.name })
            }

    fun family(name: String): Family = when (name) {
        "LiTX", "LiT", "SMS" -> Family.STRUCTURE_LIQUIDITY
        "SMT" -> Family.DIVERGENCE
        "TradePro" -> Family.COMPOSITE
        else -> Family.OTHER
    }

    fun distinctFamilyCount(components: List<SignalFusionComponent>): Int =
        components.map { family(it.name) }.distinct().size
}
