package com.foxtrader.app.di

import com.foxtrader.app.BuildConfig
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
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the MetaApi-specific Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MetaApiRetrofit

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

    @Provides
    @Singleton
    @MetaApiRetrofit
    fun provideMetaApiRetrofit(json: Json): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class Mt4RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMt4Repository(impl: Mt4RepositoryImpl): Mt4Repository
}
