package com.localgpt.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.localgpt.app.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "litechat_settings")

/** Default system prompt used for both in-app chat and the /v1 endpoint. */
const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful assistant running fully offline on the user's phone. " +
        "Answer concisely and accurately."

data class Settings(
    // Appearance
    val themeMode: String = ChatConstants.THEME_SYSTEM, // "system" | "dark" | "light"
    val themeColor: Long = 0xFF00897BL,
    val pureBlack: Boolean = false,
    // LiteRT engine parameters
    val activeModelId: String = "",
    val customModelPath: String = "",
    val temperature: Float = 0.2f,
    val topK: Int = 40,
    val topP: Float = 0.90f,
    val maxTokens: Int = 512,
    val contextWindowTokens: Int = 2048, // 1024 | 2048 | 4096
    val promptTemplateFormat: String = "auto", // "auto" | "gemma" | "chatml" | "llama3" | "raw"
    val backend: String = "GPU", // "GPU" | "CPU"
    val verboseLogs: Boolean = false,
    val perModelParamsJson: String = "{}",
    // Model source & Remote backend (Ollama / OpenAI API)
    val modelSource: String = ChatConstants.SOURCE_LOCAL, // "local" | "remote"
    val remoteBaseUrl: String = "http://192.168.1.100:11434",
    val remoteApiKey: String = "",
    val remoteModelId: String = "",
    // Model downloads
    val huggingFaceToken: String = "",
    // Embedded OpenAI-compatible server
    val serverPort: Int = 8080,
    val serverBindAll: Boolean = false,
    val serverAuthToken: String = "",
    // Chat behavior & UI
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val customPersonasJson: String = "",
    val enableThinking: Boolean = true,
    val showTokensPerSec: Boolean = true,
    val autoScroll: Boolean = true,
    val sendOnEnter: Boolean = false,
    val keepAwake: Boolean = false,
    // On-device RAG (knowledge documents)
    val ragEnabled: Boolean = true,
    val useChatMemory: Boolean = true,
    // Context & Capability Skills
    val autoCompress: Boolean = true,
    val captureArtifacts: Boolean = true,
    val enableVision: Boolean = true,
    val enableWebSearch: Boolean = true,
)

class SettingsRepository(
    private val context: Context,
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_COLOR = longPreferencesKey("theme_color")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val ACTIVE_MODEL_ID = stringPreferencesKey("litert_active_model_id")
        val CUSTOM_MODEL_PATH = stringPreferencesKey("litert_custom_model_path")
        val TEMPERATURE = floatPreferencesKey("litert_temperature")
        val TOP_K = intPreferencesKey("litert_top_k")
        val TOP_P = floatPreferencesKey("litert_top_p")
        val MAX_TOKENS = intPreferencesKey("litert_max_tokens")
        val CONTEXT_WINDOW_TOKENS = intPreferencesKey("litert_context_window_tokens")
        val PROMPT_TEMPLATE_FORMAT = stringPreferencesKey("litert_prompt_template_format")
        val BACKEND = stringPreferencesKey("litert_backend")
        val VERBOSE_LOGS = booleanPreferencesKey("litert_verbose")
        val PER_MODEL_PARAMS = stringPreferencesKey("litert_per_model_params_json")
        val MODEL_SOURCE = stringPreferencesKey("model_source")
        val REMOTE_BASE_URL = stringPreferencesKey("remote_base_url")
        val REMOTE_MODEL_ID = stringPreferencesKey("remote_model_id")
        val SERVER_PORT = intPreferencesKey("server_port")
        val SERVER_BIND_ALL = booleanPreferencesKey("server_bind_all")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val CUSTOM_PERSONAS = stringPreferencesKey("custom_personas_json")
        val ENABLE_THINKING = booleanPreferencesKey("enable_thinking")
        val SHOW_TPS = booleanPreferencesKey("show_tokens_per_sec")
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
        val SEND_ON_ENTER = booleanPreferencesKey("send_on_enter")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val RAG_ENABLED = booleanPreferencesKey("rag_enabled")
        val USE_CHAT_MEMORY = booleanPreferencesKey("use_chat_memory")
        val AUTO_COMPRESS = booleanPreferencesKey("auto_compress")
        val CAPTURE_ARTIFACTS = booleanPreferencesKey("capture_artifacts")
        val ENABLE_VISION = booleanPreferencesKey("enable_vision")
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")

        // Legacy DataStore keys for sensitive tokens. Tokens used to live here in
        // plaintext; they are migrated to EncryptedSharedPreferences on first read
        // and then removed. Do NOT write to these keys anymore.
        val LEGACY_REMOTE_API_KEY = stringPreferencesKey("remote_api_key")
        val LEGACY_HF_TOKEN = stringPreferencesKey("huggingface_token")
        val LEGACY_SERVER_AUTH_TOKEN = stringPreferencesKey("server_auth_token")
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val SECURE_PREFS_NAME = "litechat_secrets"
        private const val SEC_REMOTE_API_KEY = "remote_api_key"
        private const val SEC_HF_TOKEN = "huggingface_token"
        private const val SEC_SERVER_AUTH_TOKEN = "server_auth_token"
    }

    /**
     * Encrypted storage for sensitive tokens (remote API key, Hugging Face token,
     * embedded-server bearer token). Falls back to plain SharedPreferences only if
     * the Android Keystore is unavailable, so the app keeps working on odd devices.
     */
    private val securePrefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        try {
            // MasterKeys (plural) is the stable API in security-crypto 1.0.0;
            // the singular MasterKey builder only exists in 1.1.0-alpha+.
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                appContext,
                SECURE_PREFS_NAME,
                masterKeyAlias,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            KLog.w(TAG, "EncryptedSharedPreferences unavailable, using plain fallback: ${t.message}")
            appContext.getSharedPreferences("${SECURE_PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    // Bumped every time a token is written so settingsFlow re-emits even though
    // the DataStore itself did not change.
    private val tokenBump = MutableStateFlow(0)
    private val migrationMutex = Mutex()

    @Volatile
    private var tokensMigrated = false

    /**
     * One-time migration: copy any tokens still sitting in plaintext DataStore
     * into the encrypted store, then delete them from DataStore.
     */
    private suspend fun migrateTokensIfNeeded(prefs: Preferences) {
        if (tokensMigrated) return
        migrationMutex.withLock {
            if (tokensMigrated) return
            try {
                val legacy =
                    mapOf(
                        Keys.LEGACY_REMOTE_API_KEY to SEC_REMOTE_API_KEY,
                        Keys.LEGACY_HF_TOKEN to SEC_HF_TOKEN,
                        Keys.LEGACY_SERVER_AUTH_TOKEN to SEC_SERVER_AUTH_TOKEN,
                    )
                var movedAny = false
                val editor = securePrefs.edit()
                for ((oldKey, newKey) in legacy) {
                    val value = prefs[oldKey]
                    if (!value.isNullOrEmpty()) {
                        editor.putString(newKey, value)
                        movedAny = true
                    }
                }
                if (movedAny) {
                    editor.apply()
                    KLog.d(TAG, "Migrated sensitive tokens from DataStore to encrypted storage")
                }
                if (legacy.keys.any { prefs.contains(it) }) {
                    context.dataStore.edit {
                        it.remove(Keys.LEGACY_REMOTE_API_KEY)
                        it.remove(Keys.LEGACY_HF_TOKEN)
                        it.remove(Keys.LEGACY_SERVER_AUTH_TOKEN)
                    }
                }
            } catch (t: Throwable) {
                KLog.e(TAG, "Token migration failed", t)
            } finally {
                tokensMigrated = true
            }
        }
    }

    val settingsFlow: Flow<Settings> =
        combine(context.dataStore.data, tokenBump) { prefs, _ ->
            withContext(Dispatchers.IO) {
                migrateTokensIfNeeded(prefs)
                Settings(
                    themeMode = prefs[Keys.THEME_MODE] ?: ChatConstants.THEME_SYSTEM,
                    themeColor = prefs[Keys.THEME_COLOR] ?: 0xFF00897BL,
                    pureBlack = prefs[Keys.PURE_BLACK] ?: false,
                    activeModelId = prefs[Keys.ACTIVE_MODEL_ID] ?: "",
                    customModelPath = prefs[Keys.CUSTOM_MODEL_PATH] ?: "",
                    temperature = (prefs[Keys.TEMPERATURE] ?: 0.2f).coerceIn(0f, 2f),
                    topK = prefs[Keys.TOP_K] ?: 40,
                    topP = prefs[Keys.TOP_P] ?: 0.90f,
                    maxTokens = prefs[Keys.MAX_TOKENS] ?: 512,
                    contextWindowTokens = prefs[Keys.CONTEXT_WINDOW_TOKENS] ?: 2048,
                    promptTemplateFormat = prefs[Keys.PROMPT_TEMPLATE_FORMAT] ?: "auto",
                    backend = prefs[Keys.BACKEND] ?: "GPU",
                    verboseLogs = prefs[Keys.VERBOSE_LOGS] ?: false,
                    perModelParamsJson = prefs[Keys.PER_MODEL_PARAMS] ?: "{}",
                    modelSource = prefs[Keys.MODEL_SOURCE] ?: ChatConstants.SOURCE_LOCAL,
                    remoteBaseUrl = prefs[Keys.REMOTE_BASE_URL] ?: "http://192.168.1.100:11434",
                    remoteApiKey = securePrefs.getString(SEC_REMOTE_API_KEY, "") ?: "",
                    remoteModelId = prefs[Keys.REMOTE_MODEL_ID] ?: "",
                    huggingFaceToken = securePrefs.getString(SEC_HF_TOKEN, "") ?: "",
                    serverPort = prefs[Keys.SERVER_PORT] ?: 8080,
                    serverBindAll = prefs[Keys.SERVER_BIND_ALL] ?: false,
                    serverAuthToken = securePrefs.getString(SEC_SERVER_AUTH_TOKEN, "") ?: "",
                    systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
                    customPersonasJson = prefs[Keys.CUSTOM_PERSONAS] ?: "",
                    enableThinking = prefs[Keys.ENABLE_THINKING] ?: true,
                    showTokensPerSec = prefs[Keys.SHOW_TPS] ?: true,
                    autoScroll = prefs[Keys.AUTO_SCROLL] ?: true,
                    sendOnEnter = prefs[Keys.SEND_ON_ENTER] ?: false,
                    keepAwake = prefs[Keys.KEEP_AWAKE] ?: false,
                    ragEnabled = prefs[Keys.RAG_ENABLED] ?: true,
                    useChatMemory = prefs[Keys.USE_CHAT_MEMORY] ?: true,
                    autoCompress = prefs[Keys.AUTO_COMPRESS] ?: true,
                    captureArtifacts = prefs[Keys.CAPTURE_ARTIFACTS] ?: true,
                    enableVision = prefs[Keys.ENABLE_VISION] ?: true,
                    enableWebSearch = prefs[Keys.ENABLE_WEB_SEARCH] ?: true,
                )
            }
        }

    private fun putSecureToken(
        key: String,
        value: String,
    ) {
        try {
            securePrefs.edit().putString(key, value).apply()
        } catch (t: Throwable) {
            KLog.e(TAG, "Failed to store token in encrypted storage", t)
        }
        // Re-emit settingsFlow so collectors observe the new token value.
        tokenBump.value = tokenBump.value + 1
    }

    suspend fun setCustomPersonasJson(value: String) = edit { it[Keys.CUSTOM_PERSONAS] = value }

    suspend fun setThemeMode(value: String) = edit { it[Keys.THEME_MODE] = value }

    suspend fun setThemeColor(value: Long) = edit { it[Keys.THEME_COLOR] = value }

    suspend fun setPureBlack(value: Boolean) = edit { it[Keys.PURE_BLACK] = value }

    suspend fun setActiveModelId(value: String) = edit { it[Keys.ACTIVE_MODEL_ID] = value }

    suspend fun setCustomModelPath(value: String) = edit { it[Keys.CUSTOM_MODEL_PATH] = value }

    suspend fun setTemperature(value: Float) = edit { it[Keys.TEMPERATURE] = value.coerceIn(0f, 2f) }

    suspend fun setTopK(value: Int) = edit { it[Keys.TOP_K] = value }

    suspend fun setTopP(value: Float) = edit { it[Keys.TOP_P] = value }

    suspend fun setMaxTokens(value: Int) = edit { it[Keys.MAX_TOKENS] = value }

    suspend fun setContextWindowTokens(value: Int) = edit { it[Keys.CONTEXT_WINDOW_TOKENS] = value }

    suspend fun setPromptTemplateFormat(value: String) = edit { it[Keys.PROMPT_TEMPLATE_FORMAT] = value }

    suspend fun setBackend(value: String) = edit { it[Keys.BACKEND] = value }

    suspend fun setVerboseLogs(value: Boolean) = edit { it[Keys.VERBOSE_LOGS] = value }

    suspend fun setPerModelParamsJson(value: String) = edit { it[Keys.PER_MODEL_PARAMS] = value }

    suspend fun setModelSource(value: String) = edit { it[Keys.MODEL_SOURCE] = value }

    suspend fun setRemoteBaseUrl(value: String) = edit { it[Keys.REMOTE_BASE_URL] = value }

    suspend fun setRemoteApiKey(value: String) = putSecureToken(SEC_REMOTE_API_KEY, value)

    suspend fun setRemoteModelId(value: String) = edit { it[Keys.REMOTE_MODEL_ID] = value }

    suspend fun setHuggingFaceToken(value: String) = putSecureToken(SEC_HF_TOKEN, value)

    suspend fun setServerPort(value: Int) = edit { it[Keys.SERVER_PORT] = value }

    suspend fun setServerBindAll(value: Boolean) = edit { it[Keys.SERVER_BIND_ALL] = value }

    suspend fun setServerAuthToken(value: String) = putSecureToken(SEC_SERVER_AUTH_TOKEN, value)

    suspend fun setSystemPrompt(value: String) = edit { it[Keys.SYSTEM_PROMPT] = value }

    suspend fun setEnableThinking(value: Boolean) = edit { it[Keys.ENABLE_THINKING] = value }

    suspend fun setShowTokensPerSec(value: Boolean) = edit { it[Keys.SHOW_TPS] = value }

    suspend fun setAutoScroll(value: Boolean) = edit { it[Keys.AUTO_SCROLL] = value }

    suspend fun setSendOnEnter(value: Boolean) = edit { it[Keys.SEND_ON_ENTER] = value }

    suspend fun setKeepAwake(value: Boolean) = edit { it[Keys.KEEP_AWAKE] = value }

    suspend fun setRagEnabled(value: Boolean) = edit { it[Keys.RAG_ENABLED] = value }

    suspend fun setUseChatMemory(value: Boolean) = edit { it[Keys.USE_CHAT_MEMORY] = value }

    suspend fun setAutoCompress(value: Boolean) = edit { it[Keys.AUTO_COMPRESS] = value }

    suspend fun setCaptureArtifacts(value: Boolean) = edit { it[Keys.CAPTURE_ARTIFACTS] = value }

    suspend fun setEnableVision(value: Boolean) = edit { it[Keys.ENABLE_VISION] = value }

    suspend fun setEnableWebSearch(value: Boolean) = edit { it[Keys.ENABLE_WEB_SEARCH] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
