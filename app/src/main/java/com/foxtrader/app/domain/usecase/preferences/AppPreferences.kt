package com.foxtrader.app.domain.usecase.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.foxtrader.app.domain.model.DataProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fox_settings")

/**
 * App-wide preferences — persisted via Jetpack DataStore.
 * Holds cross-feature settings; exposed as StateFlows for reactive UI.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val securePrefs: SharedPreferences by lazy { createSecurePrefs() }

    private val _dataProvider = MutableStateFlow(DataProvider.SAMPLE)
    val dataProvider: StateFlow<DataProvider> = _dataProvider.asStateFlow()

    private val _darkMode = MutableStateFlow(true)
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(false)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _apiKeys = MutableStateFlow<Map<DataProvider, String>>(emptyMap())
    val apiKeys: StateFlow<Map<DataProvider, String>> = _apiKeys.asStateFlow()

    init {
        // Load persisted values into StateFlows on init.
        scope.launch {
            context.dataStore.data.collect { prefs ->
                _darkMode.value = prefs[KEY_DARK_MODE] ?: true
                _appLockEnabled.value = prefs[KEY_APP_LOCK] ?: false
                _dataProvider.value = prefs[KEY_PROVIDER]?.let { name ->
                    runCatching { DataProvider.valueOf(name) }.getOrDefault(DataProvider.SAMPLE)
                } ?: DataProvider.SAMPLE
                val storedApiKeys = loadPersistedApiKeys()
                val legacyAlphaKey = prefs[KEY_ALPHA_VANTAGE_API_KEY].orEmpty()
                if (
                    legacyAlphaKey.isNotBlank() &&
                    storedApiKeys[DataProvider.ALPHA_VANTAGE].isNullOrBlank()
                ) {
                    setApiKey(DataProvider.ALPHA_VANTAGE, legacyAlphaKey)
                    context.dataStore.edit { it.remove(KEY_ALPHA_VANTAGE_API_KEY) }
                } else {
                    _apiKeys.value = storedApiKeys
                }
            }
        }
    }

    fun setDataProvider(provider: DataProvider) {
        _dataProvider.value = provider
        scope.launch { context.dataStore.edit { it[KEY_PROVIDER] = provider.name } }
    }

    fun setDarkMode(enabled: Boolean) {
        _darkMode.value = enabled
        scope.launch { context.dataStore.edit { it[KEY_DARK_MODE] = enabled } }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        _appLockEnabled.value = enabled
        scope.launch { context.dataStore.edit { it[KEY_APP_LOCK] = enabled } }
    }

    fun setApiKey(provider: DataProvider, key: String) {
        if (!provider.requiresApiKey) return

        val normalizedKey = key.trim()
        val updatedKeys = _apiKeys.value.toMutableMap()

        if (normalizedKey.isBlank()) {
            updatedKeys.remove(provider)
            securePrefs.edit().remove(apiKeyPreferenceName(provider)).apply()
        } else {
            updatedKeys[provider] = normalizedKey
            securePrefs.edit().putString(apiKeyPreferenceName(provider), normalizedKey).apply()
        }

        _apiKeys.value = updatedKeys.toMap()
    }

    fun getApiKey(provider: DataProvider): String? = _apiKeys.value[provider]

    fun canGoLive(): Boolean {
        val p = _dataProvider.value
        if (!p.supportsLive) return false
        if (p.requiresApiKey && _apiKeys.value[p].isNullOrBlank()) return false
        return true
    }

    private fun loadPersistedApiKeys(): Map<DataProvider, String> =
        DataProvider.entries
            .filter { it.requiresApiKey }
            .mapNotNull { provider ->
                securePrefs.getString(apiKeyPreferenceName(provider), null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { provider to it }
            }
            .toMap()

    private fun createSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun apiKeyPreferenceName(provider: DataProvider): String =
        "provider_api_key_${provider.name.lowercase(Locale.ROOT)}"

    private companion object {
        const val SECURE_PREFS_FILE_NAME = "fox_provider_keys"
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        val KEY_PROVIDER = stringPreferencesKey("data_provider")
        val KEY_ALPHA_VANTAGE_API_KEY = stringPreferencesKey("alpha_vantage_api_key")
    }
}
