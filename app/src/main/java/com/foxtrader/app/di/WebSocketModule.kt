package com.foxtrader.app.di

import com.foxtrader.app.data.remote.websocket.BinanceWebSocket
import com.foxtrader.app.data.remote.websocket.MarketWebSocket
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds the WebSocket interface to its Binance implementation.
 * Singleton scope — one WebSocket connection shared across all features.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebSocketModule {

    @Binds
    @Singleton
    abstract fun bindMarketWebSocket(impl: BinanceWebSocket): MarketWebSocket
}
