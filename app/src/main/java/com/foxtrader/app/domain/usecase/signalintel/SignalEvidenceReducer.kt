package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.SignalFusionComponent
import com.foxtrader.app.domain.model.SignalSource
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
        MOMENTUM_ORDERFLOW,
        COMPOSITE,
        AUCTION_PROFILE,
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
        "RSI Orderflow", "RSI Orderflow Candle", "RSI OrderFlow",
        "RSI Orderflow Divergence", "RSI Orderflow Reversal" -> Family.MOMENTUM_ORDERFLOW
        "TradePro" -> Family.COMPOSITE
        else -> Family.OTHER
    }

    /** Same correlation policy at the chart-signal boundary. */
    fun family(source: SignalSource): Family = when (source) {
        SignalSource.LITX,
        SignalSource.LIT,
        SignalSource.SMS -> Family.STRUCTURE_LIQUIDITY
        SignalSource.SMT -> Family.DIVERGENCE
        SignalSource.LIQUIDITY_SWEEP,
        SignalSource.VIRGIN_WICK -> Family.STRUCTURE_LIQUIDITY
        SignalSource.RSI_ORDERFLOW,
        SignalSource.RSI_REVERSAL -> Family.MOMENTUM_ORDERFLOW
        SignalSource.PIVOT_SWEEP_DIVERGENCE -> Family.COMPOSITE
        // Apex is an aggregate of the other members, so it is not independent
        // of any of them. Its family only decides where it lands here; the
        // reason it can never reinforce them is handled at the confluence step.
        SignalSource.APEX -> Family.COMPOSITE
        SignalSource.VALUE_AREA_LIQUIDITY_REJECTION -> Family.AUCTION_PROFILE
        SignalSource.ACCUMULATION_MANIPULATION_DISTRIBUTION -> Family.STRUCTURE_LIQUIDITY
        SignalSource.NASCENT -> Family.STRUCTURE_LIQUIDITY
        SignalSource.TRADEPRO -> Family.COMPOSITE
        SignalSource.BINARY3M,
        SignalSource.STRATEGY -> Family.OTHER
    }

    fun distinctFamilyCount(components: List<SignalFusionComponent>): Int =
        components.map { family(it.name) }.distinct().size
}
