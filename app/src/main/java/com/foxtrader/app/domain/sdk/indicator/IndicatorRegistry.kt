package com.foxtrader.app.domain.sdk.indicator

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for all available indicators (built-in + user/marketplace plugins).
 *
 * Core code depends only on the [Indicator] interface and this registry —
 * adding a new indicator never requires modifying the chart renderer,
 * ViewModel, or any existing indicator (Open/Closed Principle).
 *
 * Built-in indicators are registered at app startup via Hilt; plugin
 * indicators will be registered dynamically (H4 marketplace).
 */
@Singleton
class IndicatorRegistry @Inject constructor() {

    private val indicators = LinkedHashMap<String, Indicator>()

    /** Register an indicator. Replaces any existing with the same ID. */
    fun register(indicator: Indicator) {
        indicators[indicator.id] = indicator
    }

    /** Unregister by ID. */
    fun unregister(id: String) {
        indicators.remove(id)
    }

    /** Get all registered indicators. */
    fun getAll(): List<Indicator> = indicators.values.toList()

    /** Get overlay indicators only (drawn on the price chart). */
    fun getOverlays(): List<Indicator> = indicators.values.filter { it.isOverlay }

    /** Get sub-panel indicators (RSI, MACD, etc.). */
    fun getSubPanels(): List<Indicator> = indicators.values.filter { !it.isOverlay }

    /** Look up by ID. */
    fun get(id: String): Indicator? = indicators[id]

    /** Whether an indicator is registered. */
    fun contains(id: String): Boolean = indicators.containsKey(id)

    /** Total count. */
    val size: Int get() = indicators.size
}
