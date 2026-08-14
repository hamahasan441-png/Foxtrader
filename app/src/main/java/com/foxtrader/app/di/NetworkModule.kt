package com.foxtrader.app.di

import com.foxtrader.app.BuildConfig
import com.foxtrader.app.data.remote.api.AlphaVantageApi
import com.foxtrader.app.data.auth.AuthInterceptor
import com.foxtrader.app.data.remote.DynamicBaseUrlInterceptor
import com.foxtrader.app.data.remote.api.BinanceApi
import com.foxtrader.app.data.remote.api.BybitApi
import com.foxtrader.app.data.remote.api.KuCoinApi
import com.foxtrader.app.data.remote.api.MarketApi
import com.foxtrader.app.data.remote.api.OkxApi
import com.foxtrader.app.data.remote.api.PolygonApi
import com.foxtrader.app.data.remote.api.SyncApi
import com.foxtrader.app.data.remote.api.TwelveDataApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for public market-data OkHttp clients without FoxTrader auth headers. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PublicMarketDataClient

/** Qualifier for the Binance-specific Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BinanceRetrofit

/** Qualifier for the Bybit-specific Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BybitRetrofit

/** Qualifier for the OKX-specific Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OkxRetrofit

/** Qualifier for the KuCoin-specific Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KuCoinRetrofit

/** Qualifier for the Alpha Vantage Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AlphaVantageRetrofit

/** Qualifier for the Twelve Data Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TwelveDataRetrofit

/** Qualifier for the Polygon.io Retrofit instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PolygonRetrofit

/** Qualifier for the Polygon.io WebSocket client (no credential logging). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PolygonMarketDataClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * FoxTrader backend base URL.
     * Override at build time by setting FOXTRADER_BASE_URL in your
     * local.properties or CI environment. Defaults to the local emulator
     * address used during development. The scheme must always be HTTPS in
     * staging/production — release builds will throw if a non-HTTPS URL is
     * configured, preventing accidental cleartext traffic in production.
     */
    private val BASE_URL: String get() {
        val url = BuildConfig.FOXTRADER_BASE_URL.takeIf { it.isNotBlank() }
            ?: "http://10.0.2.2:8000/"
        check(BuildConfig.DEBUG || url.startsWith("https://")) {
            "Release builds require HTTPS for FOXTRADER_BASE_URL. Got: $url"
        }
        return url
    }

    /** Binance public API base URL. */
    private const val BINANCE_BASE_URL = "https://api.binance.com/"
    /** Bybit public API base URL. */
    private const val BYBIT_BASE_URL = "https://api.bybit.com/"
    /** OKX public API base URL. */
    private const val OKX_BASE_URL = "https://www.okx.com/"
    /** KuCoin public API base URL. */
    private const val KUCOIN_BASE_URL = "https://api.kucoin.com/"
    /** Alpha Vantage public API base URL. */
    private const val ALPHA_VANTAGE_BASE_URL = "https://www.alphavantage.co/"
    /** Twelve Data public API base URL. */
    private const val TWELVE_DATA_BASE_URL = "https://api.twelvedata.com/"
    /** Polygon.io REST API base URL. */
    private const val POLYGON_BASE_URL = "https://api.polygon.io/"

    // Shared timeout constants (seconds).
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 30L
    private const val WRITE_TIMEOUT = 30L
    private const val CALL_TIMEOUT = 60L

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // In release builds, log nothing; tokens must never appear in logs.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            // Rewrite host first so auth + logging see the real destination.
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            // Certificate pinning for the FoxTrader backend. Only active when the
            // operator has supplied real SHA-256 pins via FOXTRADER_CERT_PINS and
            // the configured backend host is not a local/dev address; otherwise the
            // pinner is inert so local development (10.0.2.2) is unaffected.
            .certificatePinner(buildCertificatePinner())
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Builds a [CertificatePinner] for the configured backend host.
     *
     * Pins come from `BuildConfig.FOXTRADER_CERT_PINS` (comma-separated
     * `sha256/...` hashes). With no pins configured — or when the backend host
     * is a local/dev address (emulator loopback, localhost) — the returned
     * pinner pins nothing, so pinning never breaks a local dev build. Production
     * operators must supply the real hashes.
     */
    private fun buildCertificatePinner(): CertificatePinner {
        val pins = BuildConfig.FOXTRADER_CERT_PINS
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val host = backendHost() ?: return CertificatePinner.Builder().build()
        if (pins.isEmpty()) return CertificatePinner.Builder().build()

        val builder = CertificatePinner.Builder()
        for (pin in pins) {
            builder.add(host, pin)
        }
        return builder.build()
    }

    /** Resolve the backend host, or null for a local/dev address. */
    private fun backendHost(): String? = runCatching {
        val host = java.net.URI(BASE_URL).host ?: return@runCatching null
        if (host.lowercase() in LOCAL_HOSTS) null else host
    }.getOrNull()

    private val LOCAL_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost", "0.0.0.0", "::1")

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

    /**
     * Public market-data client for exchange REST/WebSocket traffic.
     * It intentionally does NOT install [AuthInterceptor], preventing FoxTrader
     * bearer tokens from being sent to third-party market-data hosts.
     */
    @Provides
    @Singleton
    @PublicMarketDataClient
    fun providePublicMarketDataClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
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
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
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

    // ========================================================================
    // BYBIT PUBLIC API (separate base URL, no auth interceptor needed)
    // ========================================================================

    @Provides
    @Singleton
    @BybitRetrofit
    fun provideBybitRetrofit(json: Json): Retrofit {
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
            .baseUrl(BYBIT_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideBybitApi(@BybitRetrofit retrofit: Retrofit): BybitApi =
        retrofit.create(BybitApi::class.java)

    // ========================================================================
    // OKX PUBLIC API (separate base URL, no auth interceptor needed)
    // ========================================================================

    @Provides
    @Singleton
    @OkxRetrofit
    fun provideOkxRetrofit(json: Json): Retrofit {
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
            .baseUrl(OKX_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideOkxApi(@OkxRetrofit retrofit: Retrofit): OkxApi =
        retrofit.create(OkxApi::class.java)

    // ========================================================================
    // KUCOIN PUBLIC API (separate base URL, no auth interceptor needed)
    // ========================================================================

    @Provides
    @Singleton
    @KuCoinRetrofit
    fun provideKuCoinRetrofit(json: Json): Retrofit {
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
            .baseUrl(KUCOIN_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideKuCoinApi(@KuCoinRetrofit retrofit: Retrofit): KuCoinApi =
        retrofit.create(KuCoinApi::class.java)

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
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
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

    // ========================================================================
    // TWELVE DATA PUBLIC API (forex, stocks, indices, crypto — single key)
    // ========================================================================

    @Provides
    @Singleton
    @TwelveDataRetrofit
    fun provideTwelveDataRetrofit(json: Json): Retrofit {
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
            .baseUrl(TWELVE_DATA_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideTwelveDataApi(@TwelveDataRetrofit retrofit: Retrofit): TwelveDataApi =
        retrofit.create(TwelveDataApi::class.java)

    // ========================================================================
    // POLYGON.IO PUBLIC API (stocks, forex, indices, and crypto aggregates)
    // ========================================================================

    /**
     * Polygon keys are query parameters, so this client intentionally has no
     * logging interceptor — even debug BASIC logging would expose the key in
     * the request URL. The data source still receives the key explicitly at
     * the repository boundary and never stores it in the network layer.
     */
    @Provides
    @Singleton
    @PolygonRetrofit
    fun providePolygonRetrofit(json: Json): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(POLYGON_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun providePolygonApi(@PolygonRetrofit retrofit: Retrofit): PolygonApi =
        retrofit.create(PolygonApi::class.java)

    /**
     * Polygon WebSocket traffic carries the API key in an auth message. Keep
     * this client separate from the general public client so no interceptor can
     * log that frame or accidentally attach FoxTrader bearer credentials.
     */
    @Provides
    @Singleton
    @PolygonMarketDataClient
    fun providePolygonMarketDataClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}
