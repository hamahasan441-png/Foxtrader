package com.foxtrader.app.di

import com.foxtrader.app.domain.sdk.indicator.IndicatorRegistry
import com.foxtrader.app.domain.sdk.indicator.builtin.BollingerIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.EmaIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.RsiIndicator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Registers all built-in indicators in the [IndicatorRegistry].
 * Plugin/marketplace indicators will be registered dynamically later (H4).
 */
@Module
@InstallIn(SingletonComponent::class)
object IndicatorModule {

    @Provides
    @Singleton
    fun provideIndicatorRegistry(): IndicatorRegistry = IndicatorRegistry().apply {
        register(EmaIndicator(20))
        register(EmaIndicator(50))
        register(EmaIndicator(200))
        register(BollingerIndicator())
        register(RsiIndicator(14))
    }
}
