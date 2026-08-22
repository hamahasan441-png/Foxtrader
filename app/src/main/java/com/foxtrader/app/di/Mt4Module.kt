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

/** Zero-log transport used by MetaApi Socket.IO. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MetaApiSocketClient

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

    @Provides
    @Singleton
    fun provideMetaApiService(@MetaApiRetrofit retrofit: Retrofit): MetaApiService =
        retrofit.create(MetaApiService::class.java)

    /**
     * Dedicated zero-log WebSocket transport. The MetaApi auth token is carried
     * in the Socket.IO handshake query, therefore no network logging interceptor
     * may ever be attached to this client.
     */
    @Provides
    @Singleton
    @MetaApiSocketClient
    fun provideMetaApiSocketClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

}

@Module
@InstallIn(SingletonComponent::class)
abstract class Mt4RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMt4Repository(impl: Mt4RepositoryImpl): Mt4Repository
}
