package com.voxengine.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val baseUrl: Flow<String> = context.dataStore.data.map { it[KEY_BASE_URL] ?: "https://api.xiaomimimo.com" }
    val apiKey: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }
    val defaultVoice: Flow<String> = context.dataStore.data.map { it[KEY_DEFAULT_VOICE] ?: "冰糖" }
    val defaultStyle: Flow<String> = context.dataStore.data.map { it[KEY_DEFAULT_STYLE] ?: "无" }
    val speed: Flow<Float> = context.dataStore.data.map { it[KEY_SPEED] ?: 1.0f }
    val bridgeEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BRIDGE_ENABLED] ?: false }
    val bridgePort: Flow<Int> = context.dataStore.data.map { it[KEY_BRIDGE_PORT] ?: 9880 }
    val darkMode: Flow<Boolean> = context.dataStore.data.map { it[KEY_DARK_MODE] ?: false }
    val currentEngine: Flow<String> = context.dataStore.data.map { it[KEY_CURRENT_ENGINE] ?: "mimo" }
    val userAgent: Flow<String> = context.dataStore.data.map { it[KEY_USER_AGENT] ?: "openclaw/unknown" }
    val parallelSynthesis: Flow<Boolean> = context.dataStore.data.map { it[KEY_PARALLEL_SYNTHESIS] ?: false }

    suspend fun updateBaseUrl(url: String) { context.dataStore.edit { it[KEY_BASE_URL] = url } }
    suspend fun updateApiKey(key: String) { context.dataStore.edit { it[KEY_API_KEY] = key } }
    suspend fun updateDefaultVoice(voice: String) { context.dataStore.edit { it[KEY_DEFAULT_VOICE] = voice } }
    suspend fun updateDefaultStyle(style: String) { context.dataStore.edit { it[KEY_DEFAULT_STYLE] = style } }
    suspend fun updateSpeed(speed: Float) { context.dataStore.edit { it[KEY_SPEED] = speed } }
    suspend fun updateBridgeEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_BRIDGE_ENABLED] = enabled } }
    suspend fun updateBridgePort(port: Int) { context.dataStore.edit { it[KEY_BRIDGE_PORT] = port } }
    suspend fun updateDarkMode(enabled: Boolean) { context.dataStore.edit { it[KEY_DARK_MODE] = enabled } }
    suspend fun updateCurrentEngine(engineId: String) { context.dataStore.edit { it[KEY_CURRENT_ENGINE] = engineId } }
    suspend fun updateUserAgent(ua: String) { context.dataStore.edit { it[KEY_USER_AGENT] = ua } }
    suspend fun updateParallelSynthesis(enabled: Boolean) { context.dataStore.edit { it[KEY_PARALLEL_SYNTHESIS] = enabled } }

    fun getEngineConfig(engineId: String, key: String): Flow<String> {
        val configKey = stringPreferencesKey("${engineId}_$key")
        return context.dataStore.data.map { it[configKey] ?: "" }
    }

    suspend fun updateEngineConfig(engineId: String, key: String, value: String) {
        val configKey = stringPreferencesKey("${engineId}_$key")
        context.dataStore.edit { it[configKey] = value }
    }

    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_DEFAULT_VOICE = stringPreferencesKey("default_voice")
        private val KEY_DEFAULT_STYLE = stringPreferencesKey("default_style")
        private val KEY_SPEED = floatPreferencesKey("speed")
        private val KEY_BRIDGE_ENABLED = booleanPreferencesKey("bridge_enabled")
        private val KEY_BRIDGE_PORT = intPreferencesKey("bridge_port")
        private val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        private val KEY_CURRENT_ENGINE = stringPreferencesKey("current_engine")
        private val KEY_USER_AGENT = stringPreferencesKey("user_agent")
        private val KEY_PARALLEL_SYNTHESIS = booleanPreferencesKey("parallel_synthesis")
    }
}
