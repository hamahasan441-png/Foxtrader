package com.foxtrader.app.di

import com.foxtrader.app.data.repository.MarketRepositoryImpl
import com.foxtrader.app.domain.repository.MarketRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds domain repository interfaces to their data-layer implementations.
 * ViewModels depend only on the interface (testable, swappable).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMarketRepository(impl: MarketRepositoryImpl): MarketRepository
}
