package com.voiceguard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.security.keystore.KeyProperties
import android.security.keystore.KeyGenParameterSpec
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import timber.log.Timber
import com.voiceguard.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("voiceguard_settings")

data class AppSettings(
    val deepgramModel: String = "",
    val deepgramLang: String = "multi",
    val autoDetect: Boolean = true,
    val diarize: Boolean = false,
    val groqModel: String = "llama-3.3-70b-versatile",
    val uiLang: String = "en",
    val sensitivity: Float = 0.5f,
    val minWords: Int = 4,
    val debounceSec: Int = 8,
    val fileFastMode: Boolean = true,
    val vadForFiles: Boolean = false,
    val demoOffline: Boolean = false,
    val voipEnabled: Boolean = false,
    val consentAccepted: Boolean = false,
    val darkTheme: Boolean = true
)

/**
 * Non-secret settings in DataStore; API-key overrides in EncryptedSharedPreferences
 * (plain SharedPreferences fallback if the keystore is unavailable). Effective key =
 * override if non-blank, else the BuildConfig value baked from local.properties.
 */
class SettingsRepository(private val context: Context) {
    private object K {
        val DG_MODEL = stringPreferencesKey("dg_model")
        val DG_LANG = stringPreferencesKey("dg_lang")
        val AUTO_DETECT = booleanPreferencesKey("auto_detect")
        val DIARIZE = booleanPreferencesKey("diarize")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val UI_LANG = stringPreferencesKey("ui_lang")
        val SENS = floatPreferencesKey("sensitivity")
        val MIN_WORDS = intPreferencesKey("min_words")
        val DEBOUNCE = intPreferencesKey("debounce")
        val FILE_FAST = booleanPreferencesKey("file_fast")
        val VAD_FILES = booleanPreferencesKey("vad_files")
        val DEMO_OFFLINE = booleanPreferencesKey("demo_offline")
        val VOIP = booleanPreferencesKey("voip")
        val CONSENT = booleanPreferencesKey("consent")
        val DARK = booleanPreferencesKey("dark")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            deepgramModel = p[K.DG_MODEL] ?: "",
            deepgramLang = p[K.DG_LANG] ?: "multi",
            autoDetect = p[K.AUTO_DETECT] ?: true,
            diarize = p[K.DIARIZE] ?: false,
            groqModel = p[K.GROQ_MODEL] ?: "llama-3.3-70b-versatile",
            uiLang = p[K.UI_LANG] ?: "en",
            sensitivity = p[K.SENS] ?: 0.5f,
            minWords = p[K.MIN_WORDS] ?: 4,
            debounceSec = p[K.DEBOUNCE] ?: 8,
            fileFastMode = p[K.FILE_FAST] ?: true,
            vadForFiles = p[K.VAD_FILES] ?: false,
            demoOffline = p[K.DEMO_OFFLINE] ?: false,
            voipEnabled = p[K.VOIP] ?: false,
            consentAccepted = p[K.CONSENT] ?: false,
            darkTheme = p[K.DARK] ?: true
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val cur = current()
        val next = transform(cur)
        context.dataStore.edit { p ->
            p[K.DG_MODEL] = next.deepgramModel
            p[K.DG_LANG] = next.deepgramLang
            p[K.AUTO_DETECT] = next.autoDetect
            p[K.DIARIZE] = next.diarize
            p[K.GROQ_MODEL] = next.groqModel
            p[K.UI_LANG] = next.uiLang
            p[K.SENS] = next.sensitivity
            p[K.MIN_WORDS] = next.minWords
            p[K.DEBOUNCE] = next.debounceSec
            p[K.FILE_FAST] = next.fileFastMode
            p[K.VAD_FILES] = next.vadForFiles
            p[K.DEMO_OFFLINE] = next.demoOffline
            p[K.VOIP] = next.voipEnabled
            p[K.CONSENT] = next.consentAccepted
            p[K.DARK] = next.darkTheme
        }
    }

    private fun secretPrefs(): SharedPreferences {
        return try {
            // security-crypto 1.0.0 (stable): MasterKeys + alias string.
            val alias = MasterKeys.getOrCreate(
                KeyGenParameterSpec.Builder(
                    "_voiceguard_master_key_",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            EncryptedSharedPreferences.create(
                "voiceguard_secrets", alias, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Timber.w(t, "EncryptedSharedPreferences unavailable, using plain prefs (documented fallback)")
            context.getSharedPreferences("voiceguard_secrets_plain", Context.MODE_PRIVATE)
        }
    }

    fun getKeyOverride(which: String): String =
        try { secretPrefs().getString(which, "") ?: "" } catch (_: Exception) { "" }

    fun setKeyOverride(which: String, value: String) {
        try { secretPrefs().edit().putString(which, value).apply() } catch (t: Throwable) {
            Timber.w(t, "Cannot store key override")
        }
    }

    fun effectiveDeepgramKey(): String {
        val o = getKeyOverride("deepgram_key")
        return o.ifBlank { BuildConfig.DEEPGRAM_API_KEY }
    }

    fun effectiveGroqKey(): String {
        val o = getKeyOverride("groq_key")
        return o.ifBlank { BuildConfig.GROQ_API_KEY }
    }

    fun effectiveDeepgramModel(fallback: String): String {
        val o = getKeyOverride("deepgram_model")
        if (o.isNotBlank()) return o
        return fallback.ifBlank { "nova-2" }
    }

    companion object {
        // Timestamps for diagnostics; not secret.
        val LAST_SELF_TEST = longPreferencesKey("last_self_test")
        suspend fun stampSelfTest(context: Context, at: Long) {
            context.dataStore.edit { it[LAST_SELF_TEST] = at }
        }
        val SUPPORTED_UI_LANGS = listOf("en", "az", "tr", "ru")
        val SUPPORTED_STT_LANGS = listOf("multi", "en", "az", "tr", "ru", "hi", "ur", "bn", "ar", "fa", "es", "fr", "de")
        val SUPPORTED_DG_MODELS = listOf("nova-2", "nova-3")
        val SUPPORTED_GROQ_MODELS = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "qwen/qwen3-32b"
        )
    }
}

