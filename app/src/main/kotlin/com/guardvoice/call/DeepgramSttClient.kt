package com.guardvoice.call

import android.util.Log
import com.guardvoice.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepgramSttClient
 * -----------------
 * On-device speech-to-text via the Deepgram hosted API (mirrors backend DeepgramClient.ts).
 * Transcribes 16kHz PCM16 mono by wrapping it as WAV and POSTing to /v1/listen.
 *
 * Auth:  Authorization: Token <DEEPGRAM_API_KEY>
 * Models tried in order: BuildConfig.DEEPGRAM_MODEL -> nova-2 -> base.
 */
object DeepgramSttClient {
    private const val BASE_URL = "https://api.deepgram.com/v1/listen"
    private const val TAG = "DeepgramSttClient"

    /** Clips shorter than ~0.3s are skipped to avoid hallucinated transcripts. */
    private const val MIN_PCM_BYTES = 9600

    fun isConfigured(): Boolean = resolveApiKey().isNotBlank()

    fun transcribe(pcmData: ByteArray): String? {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Deepgram API key is not configured; skipping transcription.")
            return null
        }
        if (pcmData.size < MIN_PCM_BYTES) return null

        val wavData = pcmToWav(pcmData)
        val primary = BuildConfig.DEEPGRAM_MODEL.ifBlank { "nova-3" }
        val models = listOf(primary, "nova-2", "base").distinct()
        var lastError = ""
        for (model in models) {
            try {
                return transcribeWithModel(wavData, model, apiKey)
            } catch (e: SecurityException) {
                // 401/403 — key is wrong, retrying other models is pointless.
                Log.w(TAG, "Deepgram auth failed; not retrying.")
                return null
            } catch (e: Exception) {
                lastError = e.message.orEmpty()
                Log.w(TAG, "Deepgram model $model failed ($lastError); trying fallback.")
            }
        }
        Log.w(TAG, "All Deepgram models failed. Last error: $lastError")
        return null
    }

    private fun resolveApiKey(): String {
        val direct = try { BuildConfig.DEEPGRAM_API_KEY.trim() } catch (_: Exception) { "" }
        if (direct.isNotBlank()) return direct
        return try { BuildConfig.DEEPGRAM_SST.trim() } catch (_: Exception) { "" }
    }

    private fun transcribeWithModel(wavData: ByteArray, model: String, apiKey: String): String? {
        val url = URL("$BASE_URL?model=$model&smart_format=true&punctuate=true&diarize=false")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Token $apiKey")
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setFixedLengthStreamingMode(wavData.size)

            connection.outputStream.use { it.write(wavData) }

            val code = connection.responseCode
            if (code == 401 || code == 403) {
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.w(TAG, "Deepgram auth error $code: $body")
                throw SecurityException("Deepgram $code")
            }
            if (code != 200) {
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("Deepgram $code: $body")
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val transcript = parseTranscript(response)
            return transcript.ifBlank { null }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTranscript(response: String): String {
        val alt = JSONObject(response)
            .optJSONObject("results")
            ?.optJSONArray("channels")
            ?.optJSONObject(0)
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
        return alt?.optString("transcript", "").orEmpty().trim()
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val wav = ByteArray(44 + dataSize)

        "RIFF".forEachIndexed { i, c -> wav[i] = c.code.toByte() }
        writeInt32(wav, 4, 36 + dataSize)
        "WAVE".forEachIndexed { i, c -> wav[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> wav[12 + i] = c.code.toByte() }
        writeInt32(wav, 16, 16)
        writeInt16(wav, 20, 1)
        writeInt16(wav, 22, 1)
        writeInt32(wav, 24, 16_000)
        writeInt32(wav, 28, 32_000)
        writeInt16(wav, 32, 2)
        writeInt16(wav, 34, 16)
        "data".forEachIndexed { i, c -> wav[36 + i] = c.code.toByte() }
        writeInt32(wav, 40, dataSize)
        System.arraycopy(pcm, 0, wav, 44, dataSize)

        return wav
    }

    private fun writeInt16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
