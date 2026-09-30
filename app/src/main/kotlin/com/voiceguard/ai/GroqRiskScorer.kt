package com.voiceguard.ai

import timber.log.Timber
import com.voiceguard.stt.AudioSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ResponseFormat(val type: String = "json_object")

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    val max_tokens: Int = 300,
    val response_format: ResponseFormat = ResponseFormat()
)

/**
 * Groq chat-completions risk scorer. Serialized requests (mutex, at most one in
 * flight); when a newer transcript arrives mid-flight the older result is
 * dropped in favor of a re-score (latest-wins) by the orchestrator passing the
 * newest window each time.
 */
class GroqRiskScorer(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _requestsSent = AtomicLong(0)
    @Volatile private var _lastLatencyMs = 0L
    @Volatile private var _tokensIn = 0L
    @Volatile private var _tokensOut = 0L
    @Volatile private var _lastError: String? = null

    data class Counters(
        val requestsSent: Long,
        val lastLatencyMs: Long,
        val tokensIn: Long,
        val tokensOut: Long,
        val lastError: String?
    )

    fun counters() = Counters(_requestsSent.get(), _lastLatencyMs, _tokensIn, _tokensOut, _lastError)

    suspend fun score(
        apiKey: String,
        model: String,
        uiLang: String,
        transcript: String,
        previousRisk: Int?,
        source: AudioSource
    ): RiskVerdict? = mutex.withLock {
        if (apiKey.isBlank()) {
            _lastError = "Groq API key is empty. Add it in Settings."
            return null
        }
        val payload = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", PromptBuilder.system(uiLang)),
                ChatMessage("user", PromptBuilder.user(transcript, previousRisk))
            )
        )
        val body = json.encodeToString(payload)
            .toRequestBody("application/json".toMediaType())
        var attempt = 0
        var lastErr: String? = null
        while (attempt < 3) {
            attempt++
            val started = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    _lastLatencyMs = System.currentTimeMillis() - started
                    val text = resp.body?.string() ?: ""
                    when {
                        resp.isSuccessful -> {
                            _requestsSent.incrementAndGet()
                            harvestUsage(text)
                            _lastError = null
                            val verdict = GroqResponseParser.parseResponse(text, source)
                            if (verdict == null) {
                                Timber.tag(TAG).w("Unparseable Groq reply: %s", text.take(200))
                            }
                            return verdict
                        }
                        resp.code == 429 -> {
                            val wait = resp.header("Retry-After")?.toLongOrNull() ?: (2L * attempt)
                            lastErr = "Groq rate-limited (429); retrying in ${wait}s"
                            Timber.tag(TAG).w(lastErr!!)
                            delay(wait * 1000)
                        }
                        resp.code == 401 || resp.code == 403 -> {
                            lastErr = "Groq rejected the API key (${resp.code}). Check the key in Settings."
                            _lastError = lastErr
                            return null
                        }
                        resp.code >= 500 && attempt < 3 -> {
                            lastErr = "Groq server error ${resp.code}; retrying"
                            delay(1500L * attempt)
                        }
                        else -> {
                            lastErr = "Groq error ${resp.code}: ${text.take(160)}"
                            _lastError = lastErr
                            return null
                        }
                    }
                }
            } catch (t: Throwable) {
                lastErr = "Groq network error: ${t.message ?: t.javaClass.simpleName}"
                Timber.tag(TAG).w(t, "Groq call failed")
                if (attempt < 3) delay(1500L * attempt)
            }
        }
        _lastError = lastErr
        return null
    }

    private fun harvestUsage(body: String) {
        // Lightweight usage extraction without extra dependencies.
        fun num(key: String): Long? {
            val idx = body.indexOf("\"$key\"")
            if (idx < 0) return null
            var i = idx + key.length + 2
            while (i < body.length && (body[i] == ' ' || body[i] == ':')) i++
            var j = i
            while (j < body.length && (body[j].isDigit())) j++
            return body.substring(i, j).toLongOrNull()
        }
        num("prompt_tokens")?.let { _tokensIn += it }
        num("completion_tokens")?.let { _tokensOut += it }
    }

    companion object {
        private const val TAG = "Groq"
    }
}

