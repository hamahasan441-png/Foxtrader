package com.foxtrader.app.di

import com.foxtrader.app.domain.sdk.indicator.IndicatorRegistry
import com.foxtrader.app.domain.sdk.indicator.builtin.AdxIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.BollingerIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.EmaIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.IchimokuIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.MacdIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.ParabolicSarIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.RsiIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.StochasticIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.SuperTrendIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.VwapIndicator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Registers all built-in indicators in the [IndicatorRegistry].
 * Plugin/marketplace indicators will be registered dynamically later (H4+).
 *
 * Overlay indicators (isOverlay = true): drawn on the price chart.
 * Sub-panel indicators (isOverlay = false): drawn in a dedicated panel below the chart.
 */
@Module
@InstallIn(SingletonComponent::class)
object IndicatorModule {

    @Provides
    @Singleton
    fun provideIndicatorRegistry(): IndicatorRegistry = IndicatorRegistry().apply {
        // ── Overlay indicators ────────────────────────────────────────────
        register(EmaIndicator(20))
        register(EmaIndicator(50))
        register(EmaIndicator(200))
        register(BollingerIndicator())
        register(VwapIndicator())
        register(SuperTrendIndicator())
        register(ParabolicSarIndicator())
        register(IchimokuIndicator())
        // ── Sub-panel indicators ──────────────────────────────────────────
        register(RsiIndicator(14))
        register(MacdIndicator())
        register(StochasticIndicator())
        register(AdxIndicator())
    }
}
