package com.foxtrader.app.data.auth

import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.RefreshRequest
import com.foxtrader.app.data.remote.api.SyncApi
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
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
 *    c. On failure: mark session expired, propagate the 401.
 *
 * SECURITY: This interceptor never logs token values. It uses [Provider] for
 * [SyncApi] to break the Dagger cycle (OkHttp -> Retrofit -> OkHttp).
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val syncApiProvider: Provider<SyncApi>,
) : Interceptor {

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
            response.close()
            val refreshed = attemptRefresh()
            if (refreshed) {
                // Retry with new access token.
                val newToken = tokenManager.getAccessToken() ?: return response
                val retryRequest = request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                return chain.proceed(retryRequest)
            } else {
                // Refresh failed — session expired.
                tokenManager.setAuthState(AuthState.SESSION_EXPIRED)
            }
        }

        return response
    }

    /**
     * Attempt to refresh the access token. Returns true on success.
     * Runs synchronously (blocking) because OkHttp interceptors are synchronous.
     */
    private fun attemptRefresh(): Boolean {
        val refreshToken = tokenManager.getRefreshToken() ?: return false
        if (tokenManager.isRefreshTokenExpired()) {
            tokenManager.clearTokens()
            return false
        }

        return runBlocking {
            try {
                tokenManager.setAuthState(AuthState.REFRESHING)
                val api = syncApiProvider.get()
                val response = api.refresh(RefreshRequest(refreshToken))
                tokenManager.saveTokens(response.tokens)
                true
            } catch (_: Exception) {
                tokenManager.clearTokens()
                false
            }
        }
    }

    private companion object {
        val AUTH_PATHS = listOf("/auth/login", "/auth/register", "/auth/refresh")
    }
}
