package com.foxtrader.app.data.auth

import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.RefreshRequest
import com.foxtrader.app.data.remote.api.SyncApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * OkHttp interceptor that attaches the Bearer access token to authenticated
 * requests and transparently handles token refresh on 401.
 *
 * Behavior:
 * 1. If the request targets an auth endpoint (login/register/refresh), skip.
 * 2. Attach `Authorization: Bearer <accessToken>` header.
 * 3. If the server responds with 401 (expired/invalid access token):
 *    a. Attempt a refresh using the refresh token.
 *    b. On success: save new tokens, retry the original request once.
 *    c. On failure: mark session expired, propagate a *fresh* 401.
 *
 * SECURITY: This interceptor never logs token values. It uses [Provider] for
 * [SyncApi] to break the Dagger cycle (OkHttp -> Retrofit -> OkHttp).
 *
 * NOTE on response lifecycle: the original 401 `Response` is closed before the
 * refresh attempt (so its resources are released promptly), but callers must
 * never receive that closed object. Every code path that runs after the close
 * returns a freshly-built `Response` carrying a readable "session expired" body
 * instead — otherwise the app could hand back an already-closed body whose
 * `read()` throws `IllegalStateException: closed`.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val syncApiProvider: Provider<SyncApi>,
) : Interceptor {

    /**
     * Holds the in-flight (or just-completed) token refresh for the current 401
     * burst. Shared by concurrent interceptor threads so that only one refresh
     * request is ever issued at a time.
     */
    private val inflightRefresh = AtomicReference<Deferred<Boolean>?>(null)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Skip auth endpoints — they don't need a Bearer token.
        val path = request.url.encodedPath
        if (AUTH_PATHS.any { path.contains(it) }) {
            return chain.proceed(request)
        }

        // Attach access token if available.
        val accessToken = tokenManager.getAccessToken()
        val authenticatedRequest = if (accessToken != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            request
        }

        val response = chain.proceed(authenticatedRequest)

        // Handle 401 — attempt transparent refresh.
        if (response.code == 401 && accessToken != null) {
            // Build the clean session-expired response BEFORE closing the
            // original, so the failure branches below can return a readable body.
            // A fresh ResponseBody is created per call — okhttp ResponseBody is
            // single-use (string() consumes and closes it), so a shared singleton
            // would throw "closed" on the second session-expiry.
            val sessionExpired = response.newBuilder()
                .request(request)
                .code(401)
                .message("Session expired")
                .body(sessionExpiredBody())
                .build()
            response.close()

            val refreshed = attemptRefresh()
            if (refreshed) {
                // Retry with new access token.
                val newToken = tokenManager.getAccessToken()
                if (newToken != null) {
                    val retryRequest = request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    return chain.proceed(retryRequest)
                }
                // A "successful" refresh that left no token is still a failure —
                // surface a readable session-expired rather than the closed 401.
                return sessionExpired
            } else {
                // Refresh failed — session expired.
                tokenManager.setAuthState(AuthState.SESSION_EXPIRED)
                return sessionExpired
            }
        }

        return response
    }

    /**
     * Single-flight token refresh. When several in-flight requests 401 at the
     * same time (a very common pattern after an access token expires), each
     * would naively trigger its own refresh request. This guards the refresh so
     * only one network refresh runs per burst and every concurrent caller shares
     * its outcome — preventing refresh-token churn and redundant calls.
     *
     * Runs synchronously (blocking) because OkHttp interceptors are synchronous.
     */
    private fun attemptRefresh(): Boolean {
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        if (tokenManager.isRefreshTokenExpired()) {
            tokenManager.clearTokens()
            return false
        }

        return runBlocking {
            // Fast path: reuse a refresh that is already in flight (or just
            // completed) for this 401 burst instead of issuing another call.
            inflightRefresh.get()?.let { return@runBlocking it.await() }

            val deferred = async { doRefresh(refreshToken) }
            if (!inflightRefresh.compareAndSet(null, deferred)) {
                // Lost the race — another caller won; wait for its result.
                return@runBlocking inflightRefresh.get()?.await() ?: false
            }
            deferred.invokeOnCompletion { inflightRefresh.compareAndSet(deferred, null) }
            deferred.await()
        }
    }

    private suspend fun doRefresh(refreshToken: String): Boolean = try {
        tokenManager.setAuthState(AuthState.REFRESHING)
        val api = syncApiProvider.get()
        val response = api.refresh(RefreshRequest(refreshToken))
        tokenManager.saveTokens(response.tokens)
        true
    } catch (_: Exception) {
        tokenManager.clearTokens()
        false
    }

    private companion object {
        val AUTH_PATHS = listOf("/auth/login", "/auth/register", "/auth/refresh")

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * Returns a fresh single-use [ResponseBody] for the session-expired 401.
         * A new instance per call is required: okhttp [ResponseBody] is consumed
         * (and closed) by [ResponseBody.string], so a shared instance would be
         * unreadable on the second expiry.
         */
        private fun sessionExpiredBody(): ResponseBody =
            """{"error":"Session expired"}""".toResponseBody(JSON_MEDIA_TYPE)
    }
}
