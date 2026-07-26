package com.foxtrader.app.di

import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import com.foxtrader.app.data.remote.websocket.ProviderMarketWebSocket
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module exposing the provider-aware WebSocket router.
 *
 * Concrete exchange sockets (Binance, Bybit) remain injectable implementation
 * details; app features depend only on [MarketWebSocket].
 */
@Module
@InstallIn(SingletonComponent::class)
object WebSocketModule {

    @Provides
    @Singleton
    fun provideMarketWebSocket(impl: ProviderMarketWebSocket): MarketWebSocket = impl
}
