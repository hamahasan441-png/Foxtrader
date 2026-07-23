package com.foxtrader.app.data.repository

import com.foxtrader.app.data.auth.TokenManager
import com.foxtrader.app.data.remote.api.SyncApi
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.LoginRequest
import com.foxtrader.app.domain.model.RegisterRequest
import com.foxtrader.app.domain.model.UserProfile
import com.foxtrader.app.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth repository implementation.
 *
 * Bridges the backend [SyncApi] auth endpoints to the domain contract, and
 * persists JWT tokens via [TokenManager] (EncryptedSharedPreferences).
 *
 * On login/register success:
 *  1. Save the token pair (TokenManager updates authState → AUTHENTICATED).
 *  2. Return the user profile.
 *
 * All network calls run on the IO dispatcher. Errors are wrapped in [Result].
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val syncApi: SyncApi,
    private val tokenManager: TokenManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AuthRepository {

    override val authState: StateFlow<AuthState> = tokenManager.authState

    override fun isLoggedIn(): Boolean = tokenManager.isLoggedIn()

    override suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ): Result<UserProfile> = withContext(io) {
        runCatching {
            tokenManager.setAuthState(AuthState.AUTHENTICATING)
            val response = syncApi.register(RegisterRequest(email, password, displayName))
            tokenManager.saveTokens(response.tokens)
            response.user
        }.onFailure {
            tokenManager.setAuthState(AuthState.UNAUTHENTICATED)
        }
    }

    override suspend fun login(email: String, password: String): Result<UserProfile> =
        withContext(io) {
            runCatching {
                tokenManager.setAuthState(AuthState.AUTHENTICATING)
                val response = syncApi.login(LoginRequest(email, password))
                tokenManager.saveTokens(response.tokens)
                response.user
            }.onFailure {
                tokenManager.setAuthState(AuthState.UNAUTHENTICATED)
            }
        }

    override suspend fun logout() = withContext(io) {
        val accessToken = tokenManager.getAccessToken()
        // Best-effort server-side invalidation; clear local tokens regardless.
        if (accessToken != null) {
            runCatching { syncApi.logout("Bearer $accessToken") }
        }
        tokenManager.clearTokens()
    }
}
