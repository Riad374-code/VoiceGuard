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

    /**
     * @param sampleRateHz actual recorder rate (WAV header must be honest —
     * a 44.1 kHz stream labeled 16 kHz transcribes as garbage).
     * @return transcript text, or null when the window genuinely contained no
     * speech (or STT is skipped: no key / clip too short).
     * @throws SecurityException on 401/403 (bad key) and 402 (billing/credit
     * exhausted — retrying is pointless for both).
     * @throws java.io.IOException when all models fail on transport/server
     * errors. Callers must NOT treat this as "silence".
     */
    fun transcribe(pcmData: ByteArray, sampleRateHz: Int = 16_000): String? {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Deepgram API key is not configured; skipping transcription.")
            return null
        }
        if (pcmData.size < MIN_PCM_BYTES) return null

        val wavData = pcmToWav(pcmData, sampleRateHz)
        val primary = BuildConfig.DEEPGRAM_MODEL.ifBlank { "nova-3" }
        val models = listOf(primary, "nova-2", "base").distinct()
        var lastError = ""
        for (model in models) {
            try {
                return transcribeWithModel(wavData, model, apiKey)
            } catch (e: SecurityException) {
                // 401/403/402 — key or billing is wrong, retrying other models
                // is pointless. Rethrow so callers don't mistake this for "silence".
                Log.w(TAG, "Deepgram auth/billing failed; not retrying.")
                throw e
            } catch (e: java.io.IOException) {
                if (e.message?.contains("rate-limited") == true) {
                    // 429: hammering sibling models hits the same exhausted quota.
                    throw e
                }
                lastError = e.message.orEmpty()
                Log.w(TAG, "Deepgram model $model failed ($lastError); trying fallback.")
            } catch (e: Exception) {
                lastError = e.message.orEmpty()
                Log.w(TAG, "Deepgram model $model failed ($lastError); trying fallback.")
            }
        }
        Log.w(TAG, "All Deepgram models failed. Last error: $lastError")
        // Transport/server failure is NOT silence — throw so the caller can
        // show a "check internet" hint instead of blaming the microphone.
        throw java.io.IOException("Deepgram transcription failed: $lastError")
    }

    private fun resolveApiKey(): String {
        val direct = try { BuildConfig.DEEPGRAM_API_KEY.trim() } catch (_: Exception) { "" }
        if (direct.isNotBlank()) return direct
        return try { BuildConfig.DEEPGRAM_SST.trim() } catch (_: Exception) { "" }
    }

    private fun transcribeWithModel(wavData: ByteArray, model: String, apiKey: String): String? {
        // detect_language is critical: default is English-only, which returns
        // blanks/garbage for Azerbaijani/Turkish/Russian calls while still
        // billing minutes — exactly the "usage but no transcript" symptom.
        val url = URL("$BASE_URL?model=$model&smart_format=true&punctuate=true&diarize=false&detect_language=true")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Token $apiKey")
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            // 160 KB upload + inference on slow mobile uplinks routinely exceeds
            // 15s — timed-out windows were lost as "STT error" despite good audio.
            connection.readTimeout = 30_000
            connection.setFixedLengthStreamingMode(wavData.size)

            connection.outputStream.use { it.write(wavData) }

            val code = connection.responseCode
            if (code == 401 || code == 403) {
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.w(TAG, "Deepgram auth error $code: $body")
                throw SecurityException("Deepgram $code")
            }
            if (code == 402) {
                // Free credit exhausted — every window fails until topped up.
                // Surface as auth-class (sticky hint, no pointless model retries).
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.w(TAG, "Deepgram billing exhausted (402): $body")
                throw SecurityException("Deepgram 402 billing exhausted")
            }
            if (code == 429) {
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.w(TAG, "Deepgram rate-limited (429): $body")
                throw java.io.IOException("Deepgram rate-limited (429)")
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

    private fun pcmToWav(pcm: ByteArray, sampleRateHz: Int = 16_000): ByteArray {
        val dataSize = pcm.size
        val wav = ByteArray(44 + dataSize)

        "RIFF".forEachIndexed { i, c -> wav[i] = c.code.toByte() }
        writeInt32(wav, 4, 36 + dataSize)
        "WAVE".forEachIndexed { i, c -> wav[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> wav[12 + i] = c.code.toByte() }
        writeInt32(wav, 16, 16)
        writeInt16(wav, 20, 1)
        writeInt16(wav, 22, 1)
        writeInt32(wav, 24, sampleRateHz)
        writeInt32(wav, 28, sampleRateHz * 2)
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
