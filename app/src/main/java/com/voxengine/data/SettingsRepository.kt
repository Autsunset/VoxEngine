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
    val darkMode: Flow<Boolean> = context.dataStore.data.map { it[KEY_DARK_MODE] ?: false }
    val currentEngine: Flow<String> = context.dataStore.data.map { it[KEY_CURRENT_ENGINE] ?: "mimo" }
    val userAgent: Flow<String> = context.dataStore.data.map { it[KEY_USER_AGENT] ?: "openclaw/unknown" }
    val parallelSynthesis: Flow<Boolean> = context.dataStore.data.map { it[KEY_PARALLEL_SYNTHESIS] ?: false }
    val ttsConcurrency: Flow<Int> = context.dataStore.data.map { (it[KEY_TTS_CONCURRENCY] ?: 3).coerceIn(1, 8) }
    val readerParagraphGapMs: Flow<Int> = context.dataStore.data.map { it[KEY_READER_PARAGRAPH_GAP_MS] ?: 700 }
    val readerSleepMinutes: Flow<Int> = context.dataStore.data.map { it[KEY_READER_SLEEP_MINUTES] ?: 0 }
    val readerStopAfterChapters: Flow<Int> = context.dataStore.data.map { it[KEY_READER_STOP_AFTER_CHAPTERS] ?: 0 }
    val readerConservativeRequestIntervalMs: Flow<Int> = context.dataStore.data.map { it[KEY_READER_CONSERVATIVE_REQUEST_INTERVAL_MS] ?: 5000 }
    val readerRetryCount: Flow<Int> = context.dataStore.data.map { it[KEY_READER_RETRY_COUNT] ?: 3 }
    val readerRetryBaseDelayMs: Flow<Int> = context.dataStore.data.map { it[KEY_READER_RETRY_BASE_DELAY_MS] ?: 2000 }

    suspend fun updateBaseUrl(url: String) { context.dataStore.edit { it[KEY_BASE_URL] = url } }
    suspend fun updateApiKey(key: String) { context.dataStore.edit { it[KEY_API_KEY] = key } }
    suspend fun updateDefaultVoice(voice: String) { context.dataStore.edit { it[KEY_DEFAULT_VOICE] = voice } }
    suspend fun updateDefaultStyle(style: String) { context.dataStore.edit { it[KEY_DEFAULT_STYLE] = style } }
    suspend fun updateSpeed(speed: Float) { context.dataStore.edit { it[KEY_SPEED] = speed } }
    suspend fun updateDarkMode(enabled: Boolean) { context.dataStore.edit { it[KEY_DARK_MODE] = enabled } }
    suspend fun updateCurrentEngine(engineId: String) { context.dataStore.edit { it[KEY_CURRENT_ENGINE] = engineId } }
    suspend fun updateUserAgent(ua: String) { context.dataStore.edit { it[KEY_USER_AGENT] = ua } }
    suspend fun updateParallelSynthesis(enabled: Boolean) { context.dataStore.edit { it[KEY_PARALLEL_SYNTHESIS] = enabled } }
    suspend fun updateTtsConcurrency(count: Int) { context.dataStore.edit { it[KEY_TTS_CONCURRENCY] = count.coerceIn(1, 8) } }
    suspend fun updateReaderParagraphGapMs(gapMs: Int) { context.dataStore.edit { it[KEY_READER_PARAGRAPH_GAP_MS] = gapMs } }
    suspend fun updateReaderSleepMinutes(minutes: Int) { context.dataStore.edit { it[KEY_READER_SLEEP_MINUTES] = minutes } }
    suspend fun updateReaderStopAfterChapters(chapters: Int) { context.dataStore.edit { it[KEY_READER_STOP_AFTER_CHAPTERS] = chapters } }
    suspend fun updateReaderConservativeRequestIntervalMs(intervalMs: Int) { context.dataStore.edit { it[KEY_READER_CONSERVATIVE_REQUEST_INTERVAL_MS] = intervalMs } }
    suspend fun updateReaderRetryCount(count: Int) { context.dataStore.edit { it[KEY_READER_RETRY_COUNT] = count } }
    suspend fun updateReaderRetryBaseDelayMs(delayMs: Int) { context.dataStore.edit { it[KEY_READER_RETRY_BASE_DELAY_MS] = delayMs } }

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
        private val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        private val KEY_CURRENT_ENGINE = stringPreferencesKey("current_engine")
        private val KEY_USER_AGENT = stringPreferencesKey("user_agent")
        private val KEY_PARALLEL_SYNTHESIS = booleanPreferencesKey("parallel_synthesis")
        private val KEY_TTS_CONCURRENCY = intPreferencesKey("tts_concurrency")
        private val KEY_READER_PARAGRAPH_GAP_MS = intPreferencesKey("reader_paragraph_gap_ms")
        private val KEY_READER_SLEEP_MINUTES = intPreferencesKey("reader_sleep_minutes")
        private val KEY_READER_STOP_AFTER_CHAPTERS = intPreferencesKey("reader_stop_after_chapters")
        private val KEY_READER_CONSERVATIVE_REQUEST_INTERVAL_MS = intPreferencesKey("reader_conservative_request_interval_ms")
        private val KEY_READER_RETRY_COUNT = intPreferencesKey("reader_retry_count")
        private val KEY_READER_RETRY_BASE_DELAY_MS = intPreferencesKey("reader_retry_base_delay_ms")
    }
}
