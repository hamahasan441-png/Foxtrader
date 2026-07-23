package com.foxtrader.app.di

import com.foxtrader.app.BuildConfig
import com.foxtrader.app.data.remote.api.AlphaVantageApi
import com.foxtrader.app.data.auth.AuthInterceptor
import com.foxtrader.app.data.remote.api.BinanceApi
import com.foxtrader.app.data.remote.api.MarketApi
import com.foxtrader.app.data.remote.api.SyncApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
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

/** Qualifier for the Binance-specific Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BinanceRetrofit

/** Qualifier for the Alpha Vantage Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AlphaVantageRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** FoxTrader backend base URL. 10.0.2.2 = host machine from the emulator. */
    private const val BASE_URL = "http://10.0.2.2:8000/"

    /** Binance public API base URL. */
    private const val BINANCE_BASE_URL = "https://api.binance.com/"
    private const val ALPHA_VANTAGE_BASE_URL = "https://www.alphavantage.co/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(authInterceptor: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideMarketApi(retrofit: Retrofit): MarketApi = retrofit.create(MarketApi::class.java)

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)

    // ========================================================================
    // BINANCE PUBLIC API (separate base URL, no auth interceptor needed)
    // ========================================================================

    @Provides
    @Singleton
    @BinanceRetrofit
    fun provideBinanceRetrofit(json: Json): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BINANCE_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideBinanceApi(@BinanceRetrofit retrofit: Retrofit): BinanceApi =
        retrofit.create(BinanceApi::class.java)

    @Provides
    @Singleton
    @AlphaVantageRetrofit
    fun provideAlphaVantageRetrofit(json: Json): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(ALPHA_VANTAGE_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideAlphaVantageApi(@AlphaVantageRetrofit retrofit: Retrofit): AlphaVantageApi =
        retrofit.create(AlphaVantageApi::class.java)
}
