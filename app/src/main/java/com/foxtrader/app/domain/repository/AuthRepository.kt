package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for authentication.
 * The domain owns this interface; the data layer implements it using the
 * backend [com.foxtrader.app.data.remote.api.SyncApi] and secure token storage.
 *
 * The app is offline-first: authentication is OPTIONAL and only required to
 * enable cloud sync/backup. All local features work without logging in.
 */
interface AuthRepository {

    /** Observable authentication state for the UI. */
    val authState: StateFlow<AuthState>

    /** Whether the user currently has a valid session. */
    fun isLoggedIn(): Boolean

    /** Register a new account. Returns the created profile on success. */
    suspend fun register(email: String, password: String, displayName: String): Result<UserProfile>

    /** Login with email/password. Returns the profile on success. */
    suspend fun login(email: String, password: String): Result<UserProfile>

    /** Logout — clears local tokens and invalidates the server session. */
    suspend fun logout()
}
