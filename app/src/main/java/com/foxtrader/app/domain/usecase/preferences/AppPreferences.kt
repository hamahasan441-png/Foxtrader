package com.foxtrader.app.domain.usecase.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.foxtrader.app.domain.model.DataProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    private val _dataProvider = MutableStateFlow(DataProvider.SAMPLE)
    val dataProvider: StateFlow<DataProvider> = _dataProvider.asStateFlow()

    private val _darkMode = MutableStateFlow(true)
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(false)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val apiKeys = mutableMapOf<DataProvider, String>()
    private val _alphaVantageApiKey = MutableStateFlow("")
    val alphaVantageApiKey: StateFlow<String> = _alphaVantageApiKey.asStateFlow()

    init {
        // Load persisted values into StateFlows on init.
        scope.launch {
            context.dataStore.data.collect { prefs ->
                _darkMode.value = prefs[KEY_DARK_MODE] ?: true
                _appLockEnabled.value = prefs[KEY_APP_LOCK] ?: false
                _dataProvider.value = prefs[KEY_PROVIDER]?.let { name ->
                    runCatching { DataProvider.valueOf(name) }.getOrDefault(DataProvider.SAMPLE)
                } ?: DataProvider.SAMPLE
                _alphaVantageApiKey.value = prefs[KEY_ALPHA_VANTAGE_API_KEY].orEmpty()
                if (_alphaVantageApiKey.value.isNotBlank()) {
                    apiKeys[DataProvider.ALPHA_VANTAGE] = _alphaVantageApiKey.value
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
        apiKeys[provider] = key
        if (provider == DataProvider.ALPHA_VANTAGE) {
            _alphaVantageApiKey.value = key
            scope.launch { context.dataStore.edit { it[KEY_ALPHA_VANTAGE_API_KEY] = key } }
        }
    }

    fun getApiKey(provider: DataProvider): String? = apiKeys[provider]

    fun canGoLive(): Boolean {
        val p = _dataProvider.value
        if (!p.supportsLive) return false
        if (p.requiresApiKey && apiKeys[p].isNullOrBlank()) return false
        return true
    }

    private companion object {
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        val KEY_PROVIDER = stringPreferencesKey("data_provider")
        val KEY_ALPHA_VANTAGE_API_KEY = stringPreferencesKey("alpha_vantage_api_key")
    }
}
