package com.guardvoice.stream

import android.content.Context
import com.guardvoice.BuildConfig

/**
 * Stores the backend analysis-server URL in SharedPreferences so a distributed APK
 * can be pointed at any backend without rebuilding (BuildConfig value is only a default).
 */
object StreamSettings {
    private const val PREFS = "guardvoice_stream"
    private const val KEY_BACKEND_WS_URL = "backend_ws_url"

    fun getBackendWsUrl(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_WS_URL, null)?.trim().orEmpty()
        if (saved.isNotBlank()) return saved
        return try {
            BuildConfig.BACKEND_WS_URL.trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun saveBackendWsUrl(context: Context, rawUrl: String) {
        val normalized = normalizeWsUrl(rawUrl)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_WS_URL, normalized)
            .apply()
    }

    fun resetToDefault(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BACKEND_WS_URL)
            .apply()
    }

    fun getDefaultWsUrl(): String =
        try {
            BuildConfig.BACKEND_WS_URL.trim()
        } catch (_: Exception) {
            ""
        }

    /** Accepts ws://, wss://, http(s)://, or bare host:port and produces a valid WS URL. */
    fun normalizeWsUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.isBlank()) return ""
        url = when {
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            url.startsWith("https://") -> "wss://${url.removePrefix("https://")}"
            url.startsWith("http://") -> "ws://${url.removePrefix("http://")}"
            else -> "ws://$url"
        }
        while (url.endsWith("/")) {
            url = url.dropLast(1)
        }
        val pathPart = url.substringAfter("://", "")
        val hasPath = pathPart.contains('/')
        if (!hasPath) {
            url = "$url/ws/audio-stream"
        }
        return url
    }
}
