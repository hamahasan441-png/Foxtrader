package com.foxtrader.app.data.remote.deriv

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DerivCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = createSecurePrefs(context)

    fun save(appId: String, token: String) {
        val normalizedAppId = appId.trim()
        val normalizedToken = token.trim()
        val credentialsChanged = this.appId() != normalizedAppId || this.token() != normalizedToken
        prefs.edit().apply {
            putString(KEY_APP_ID, normalizedAppId)
            putString(KEY_TOKEN, normalizedToken)
            // Account ids are scoped to the credential set. Never carry an old
            // selection across an App ID/token replacement.
            if (credentialsChanged) remove(KEY_ACCOUNT_ID)
        }.apply()
    }

    fun saveAccountId(accountId: String?) {
        prefs.edit().apply {
            if (accountId.isNullOrBlank()) remove(KEY_ACCOUNT_ID)
            else putString(KEY_ACCOUNT_ID, accountId.trim())
        }.apply()
    }

    fun appId(): String? = prefs.getString(KEY_APP_ID, null)?.trim()?.takeIf { it.isNotBlank() }
    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }
    fun accountId(): String? = prefs.getString(KEY_ACCOUNT_ID, null)?.trim()?.takeIf { it.isNotBlank() }

    fun clear() {
        prefs.edit().remove(KEY_APP_ID).remove(KEY_TOKEN).remove(KEY_ACCOUNT_ID).apply()
    }


    private fun createSecurePrefs(context: Context): SharedPreferences {
        fun create(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Encrypted Deriv credential store unreadable; wiping stale ciphertext", e)
            runCatching { context.deleteSharedPreferences(FILE_NAME) }
            create()
        }
    }

    private companion object {
        const val TAG = "DerivCredentialStore"
        const val FILE_NAME = "fox_deriv_credentials"
        const val KEY_APP_ID = "deriv_app_id"
        const val KEY_TOKEN = "deriv_auth_token"
        const val KEY_ACCOUNT_ID = "deriv_account_id"
    }
}
