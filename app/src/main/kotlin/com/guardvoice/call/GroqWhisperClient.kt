package com.guardvoice.call

import android.util.Log
import com.guardvoice.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GroqWhisperClient {
    private const val API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val DEFAULT_MODEL = "whisper-large-v3-turbo"
    private const val LANGUAGE = "en"
    private const val TAG = "GroqWhisperClient"

    fun transcribe(pcmData: ByteArray): String? {
        val apiKey = BuildConfig.GROQ_API_KEY.trim()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Groq API key is not configured; skipping transcription.")
            return null
        }

        return try {
            val wavData = pcmToWav(pcmData)
            val boundary = "----GuardVoiceBoundary${System.nanoTime()}"
            val body = buildMultipartBody(wavData, boundary)

            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000

            connection.outputStream.use { output ->
                output.write(body)
                output.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseTranscription(response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
                Log.w(TAG, "Groq API error $responseCode: $error")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription request failed", e)
            null
        }
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

    private fun buildMultipartBody(wavData: ByteArray, boundary: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val crlf = "\r\n".toByteArray()
        val boundaryBytes = "--$boundary".toByteArray()
        val model = BuildConfig.GROQ_WHISPER_MODEL.ifBlank { DEFAULT_MODEL }

        bos.write(boundaryBytes); bos.write(crlf)
        bos.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"".toByteArray()); bos.write(crlf)
        bos.write("Content-Type: audio/wav".toByteArray()); bos.write(crlf)
        bos.write(crlf)
        bos.write(wavData)
        bos.write(crlf)

        bos.write(boundaryBytes); bos.write(crlf)
        bos.write("Content-Disposition: form-data; name=\"model\"".toByteArray()); bos.write(crlf)
        bos.write(crlf)
        bos.write(model.toByteArray())
        bos.write(crlf)

        bos.write(boundaryBytes); bos.write(crlf)
        bos.write("Content-Disposition: form-data; name=\"language\"".toByteArray()); bos.write(crlf)
        bos.write(crlf)
        bos.write(LANGUAGE.toByteArray())
        bos.write(crlf)

        bos.write("--$boundary--".toByteArray()); bos.write(crlf)

        return bos.toByteArray()
    }

    private fun parseTranscription(response: String): String {
        return JSONObject(response).optString("text", "").trim()
    }
}
