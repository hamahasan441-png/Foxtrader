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
 * server without rebuilding during development. The request path/query are
 * preserved, so the configured value must be an origin (e.g.
 * `https://api.example.com`), not a path-prefixed URL.
 *
 * Fails safe: when the override is blank/unparseable, would downgrade to
 * cleartext, or attempts to change the backend host in a release build, the
 * request proceeds unchanged against the compiled
 * [com.foxtrader.app.di.NetworkModule] base URL. Release host changes require a
 * new signed build, preventing an in-app setting from redirecting bearer tokens
 * away from the certificate-pinned production host.
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

        // Release builds may tune the origin only on the already-compiled host.
        // Never let a mutable preference redirect authenticated traffic (and its
        // bearer token) to an arbitrary HTTPS server outside our pinning scope.
        if (!BuildConfig.DEBUG && (!target.isHttps || target.host != request.url.host)) {
            return chain.proceed(request)
        }

        val rewritten = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}
