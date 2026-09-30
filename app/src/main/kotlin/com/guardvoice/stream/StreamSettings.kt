package com.guardvoice.stream

import android.content.Context
import android.os.Build
import com.guardvoice.BuildConfig

/**
 * Stores the backend analysis-server URL in SharedPreferences so a distributed APK
 * can be pointed at any backend without rebuilding (BuildConfig value is only a default).
 *
 * Empty means on-device mode (direct Deepgram STT + Groq llama, no server).
 * Emulator-loopback defaults (10.0.2.2/localhost) are treated as empty on real
 * devices so a shared APK never reconnect-spams an unreachable server.
 */
object StreamSettings {
    private const val PREFS = "guardvoice_stream"
    private const val KEY_BACKEND_WS_URL = "backend_ws_url"

    fun getBackendWsUrl(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_WS_URL, null)?.trim().orEmpty()
        if (saved.isNotBlank()) return saved
        return getDefaultWsUrl()
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

    fun getDefaultWsUrl(): String {
        val raw = try {
            BuildConfig.BACKEND_WS_URL.trim()
        } catch (_: Exception) {
            ""
        }
        if (raw.isBlank()) return ""
        // A baked emulator-loopback URL is unreachable on real hardware — treat as
        // "no server" instead of reconnect-looping against it.
        if (!isEmulatorDevice() && isLoopbackUrl(raw)) return ""
        return raw
    }

    fun isEmulatorDevice(): Boolean {
        val fingerprint = try { Build.FINGERPRINT.orEmpty() } catch (_: Exception) { "" }
        if (fingerprint.contains("generic", ignoreCase = true) ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            fingerprint.startsWith("google/sdk_gphone")
        ) return true
        val model = try { Build.MODEL.orEmpty() } catch (_: Exception) { "" }
        if (model.contains("Emulator", ignoreCase = true) ||
            model.contains("google_sdk", ignoreCase = true)
        ) return true
        val product = try { Build.PRODUCT.orEmpty() } catch (_: Exception) { "" }
        return product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true)
    }

    private fun isLoopbackUrl(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore("/").substringBefore(":")
        return host == "10.0.2.2" || host == "10.0.3.2" ||
            host.equals("localhost", ignoreCase = true) || host == "127.0.0.1"
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
