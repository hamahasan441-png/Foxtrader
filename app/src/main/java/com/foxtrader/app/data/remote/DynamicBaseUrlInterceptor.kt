package com.foxtrader.app.data.remote

import com.foxtrader.app.BuildConfig
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites the scheme/host/port of FoxTrader-backend requests to the URL the
 * user configured in Settings, so the backend can be pointed at a deployed
 * server without rebuilding. The request path/query are preserved, so the
 * configured value must be an origin (e.g. `https://api.example.com`), not a
 * path-prefixed URL.
 *
 * Fails safe: when the override is blank or unparseable — or would downgrade a
 * release build to cleartext — the request proceeds unchanged against the
 * compiled [com.foxtrader.app.di.NetworkModule] base URL. This preserves the
 * module's release-HTTPS guarantee (it never throws inside the interceptor).
 */
@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val appPreferences: AppPreferences,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val configured = appPreferences.backendBaseUrl.value.trim().takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)

        val target = configured.toHttpUrlOrNull() ?: return chain.proceed(request)

        // Never silently downgrade to cleartext in a release build.
        if (!BuildConfig.DEBUG && !target.isHttps) return chain.proceed(request)

        val rewritten = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}
