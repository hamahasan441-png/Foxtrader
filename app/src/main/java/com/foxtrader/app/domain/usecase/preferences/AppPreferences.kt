package com.foxtrader.app.domain.usecase.preferences

import com.foxtrader.app.domain.model.DataProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide preferences holder (in-memory, singleton).
 *
 * Holds cross-feature settings such as the selected data provider and API keys.
 * A DataStore-backed persistence layer can wrap this later without changing
 * consumers, since everything observes the exposed StateFlows.
 */
@Singleton
class AppPreferences @Inject constructor() {

    private val _dataProvider = MutableStateFlow(DataProvider.SAMPLE)
    val dataProvider: StateFlow<DataProvider> = _dataProvider.asStateFlow()

    private val apiKeys = mutableMapOf<DataProvider, String>()

    fun setDataProvider(provider: DataProvider) {
        _dataProvider.value = provider
    }

    fun setApiKey(provider: DataProvider, key: String) {
        apiKeys[provider] = key
    }

    fun getApiKey(provider: DataProvider): String? = apiKeys[provider]

    /** Whether the currently selected provider is ready to stream live data. */
    fun canGoLive(): Boolean {
        val p = _dataProvider.value
        if (!p.supportsLive) return false
        if (p.requiresApiKey && apiKeys[p].isNullOrBlank()) return false
        return true
    }
}
