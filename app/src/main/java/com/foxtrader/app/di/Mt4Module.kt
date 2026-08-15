package com.foxtrader.app.di

import com.foxtrader.app.data.remote.api.MetaApiService
import com.foxtrader.app.data.repository.Mt4RepositoryImpl
import com.foxtrader.app.domain.repository.Mt4Repository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the MetaApi-specific Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MetaApiRetrofit

/**
 * Qualifier for the OkHttpClient used exclusively for the MetaApi streaming
 * WebSocket. This client must NEVER install any HTTP logging interceptor, in
 * debug or release: the stream authenticates by embedding the MetaApi token in
 * the WebSocket URL query string, so any request/response logging of that
 * client would leak the live credential to logcat.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MetaApiWebSocketClient

/**
 * Hilt module providing MetaApi networking components and binding the
 * [Mt4Repository] interface to its implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
object Mt4NetworkModule {

    /** MetaApi REST API base URL. */
    private const val METAAPI_BASE_URL = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/"

    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 30L
    private const val WRITE_TIMEOUT = 30L
    private const val CALL_TIMEOUT = 60L

    /**
     * MetaApi REST client. No HTTP logging interceptor is installed here (in
     * debug or release): every MetaApi call carries the `auth-token` header,
     * and logging that would expose the live credential. Credentials are only
     * ever sent over TLS and are never written to logcat.
     */
    @Provides
    @Singleton
    @MetaApiRetrofit
    fun provideMetaApiRetrofit(json: Json): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(METAAPI_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    /**
     * Dedicated WebSocket client for the MetaApi quote stream. Deliberately has
     * NO logging interceptor and no auth header injection: the stream embeds
     * the token in the URL query, and this client's requests are the only ones
     * that carry it. See [MetaApiWebSocketClient].
     */
    @Provides
    @Singleton
    @MetaApiWebSocketClient
    fun provideMetaApiWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .pingInterval(MT4_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun provideMetaApiService(@MetaApiRetrofit retrofit: Retrofit): MetaApiService =
        retrofit.create(MetaApiService::class.java)

    private const val MT4_PING_INTERVAL_MS = 15_000L
}

@Module
@InstallIn(SingletonComponent::class)
abstract class Mt4RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMt4Repository(impl: Mt4RepositoryImpl): Mt4Repository
}
