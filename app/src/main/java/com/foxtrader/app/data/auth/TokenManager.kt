package com.foxtrader.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.AuthTokens
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure JWT token manager.
 *
 * Responsibilities:
 * - Store access + refresh tokens in EncryptedSharedPreferences (AES-256,
 *   backed by Android Keystore).
 * - Expose observable [authState] for the UI (login/logout/session-expired).
 * - Provide [getAccessToken] / [getRefreshToken] for the auth interceptor.
 * - Handle token rotation on refresh.
 *
 * SECURITY:
 * - Tokens NEVER appear in logs, analytics, or crash metadata.
 * - EncryptedSharedPreferences uses MasterKey (AES256-GCM value encryption
 *   + AES256-SIV key encryption), hardware-backed where available.
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    private val _authState = MutableStateFlow(initialState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // ========================================================================
    // TOKEN ACCESS
    // ========================================================================

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getAccessExpiresAt(): Long = prefs.getLong(KEY_ACCESS_EXPIRES, 0L)

    fun isAccessTokenExpired(): Boolean =
        System.currentTimeMillis() >= getAccessExpiresAt()

    fun isRefreshTokenExpired(): Boolean =
        System.currentTimeMillis() >= prefs.getLong(KEY_REFRESH_EXPIRES, 0L)

    fun isLoggedIn(): Boolean = getRefreshToken() != null && !isRefreshTokenExpired()

    // ========================================================================
    // TOKEN STORAGE
    // ========================================================================

    /**
     * Save a new token pair (after login, register, or refresh).
     * Immediately updates [authState] to [AuthState.AUTHENTICATED].
     */
    fun saveTokens(tokens: AuthTokens) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(KEY_ACCESS_EXPIRES, tokens.accessExpiresAt)
            .putLong(KEY_REFRESH_EXPIRES, tokens.refreshExpiresAt)
            .apply()
        _authState.value = AuthState.AUTHENTICATED
    }

    /**
     * Clear all tokens (logout or session expiry).
     */
    fun clearTokens() {
        prefs.edit().clear().apply()
        _authState.value = AuthState.UNAUTHENTICATED
    }

    fun setAuthState(state: AuthState) {
        _authState.value = state
    }

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun initialState(): AuthState =
        if (isLoggedIn()) AuthState.AUTHENTICATED else AuthState.UNAUTHENTICATED

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "EncryptedSharedPreferences $PREFS_FILE_NAME corrupted or unreadable after restore — wiping and recreating empty store",
                e,
            )
            try {
                context.deleteSharedPreferences(PREFS_FILE_NAME)
            } catch (_: Exception) {
                // Best-effort delete; recreate anyway.
            }
            // Recreate fresh (empty) store — MasterKey may need recreation after wipe.
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private companion object {
        const val TAG = "TokenManager"
        const val PREFS_FILE_NAME = "fox_auth_tokens"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_EXPIRES = "access_expires_at"
        const val KEY_REFRESH_EXPIRES = "refresh_expires_at"
    }
}
