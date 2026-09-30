package com.voiceguard.stt

import timber.log.Timber
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class DeepgramConnState { IDLE, CONNECTING, OPEN, RECONNECTING, AUTH_ERROR, FAILED, CLOSED }

/**
 * OkHttp WebSocket client for Deepgram streaming STT (`linear16`, 16 kHz mono).
 * Emits [TranscriptEvent]s; tracks counters for Diagnostics. Auto-reconnects
 * with exponential backoff (1/2/4… max 15 s); 401/403 becomes [DeepgramConnState.AUTH_ERROR]
 * with no retry loop.
 */
class DeepgramStreamingClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    private val _events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TranscriptEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(DeepgramConnState.IDLE)
    val state: StateFlow<DeepgramConnState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var sessionOpen = false
    @Volatile private var authFailed = false
    private var cfg: Config? = null
    private val reconnectAttempt = AtomicLong(0)

    // Diagnostics counters.
    private val _audioBytesSent = AtomicLong(0)
    private val _messagesReceived = AtomicLong(0)
    private val _emptyResults = AtomicLong(0)
    private val _wordsReceived = AtomicLong(0)
    @Volatile private var _lastRequestId = ""
    @Volatile private var _lastError: String? = null
    @Volatile private var connectMs = 0L

    data class Config(
        val apiKey: String,
        val model: String,
        val language: String,
        val autoDetect: Boolean,
        val diarize: Boolean,
        val source: AudioSource
    )

    data class Counters(
        val audioBytesSent: Long,
        val secondsSent: Double,
        val messagesReceived: Long,
        val emptyResults: Long,
        val wordsReceived: Long,
        val requestId: String,
        val lastError: String?
    )

    fun counters(): Counters = Counters(
        _audioBytesSent.get(),
        _audioBytesSent.get() / 32000.0,
        _messagesReceived.get(),
        _emptyResults.get(),
        _wordsReceived.get(),
        _lastRequestId,
        _lastError
    )

    fun connect(config: Config) {
        cfg = config
        authFailed = false
        reconnectAttempt.set(0)
        sessionOpen = true
        openSocket()
    }

    private fun openSocket() {
        val c = cfg ?: return
        _state.value = if (reconnectAttempt.get() == 0L) DeepgramConnState.CONNECTING else DeepgramConnState.RECONNECTING
        connectMs = System.currentTimeMillis()
        val langParam = if (c.autoDetect) "en" else c.language
        val url = buildString {
            append("wss://api.deepgram.com/v1/listen?model=").append(c.model)
            append("&language=").append(langParam)
            if (c.autoDetect) append("&detect_language=true")
            append("&encoding=linear16&sample_rate=16000&channels=1")
            append("&interim_results=true&smart_format=true&punctuate=true")
            append("&endpointing=300&utterance_end_ms=1000&vad_events=true")
            if (c.diarize) append("&diarize=true")
        }
        val req = Request.Builder().url(url).header("Authorization", "Token ${c.apiKey}").build()
        Timber.tag(TAG).i("Connecting model=%s lang=%s", c.model, langParam)
        socket = http.newWebSocket(req, Listener())
    }

    fun sendAudio(bytes: ByteArray) {
        if (!sessionOpen) return
        val ok = try {
            socket?.send(bytes.toByteString()) == true
        } catch (_: Exception) {
            false
        }
        if (ok) _audioBytesSent.addAndGet(bytes.size.toLong())
    }

    fun sendKeepAlive() {
        try { socket?.send("{\"type\":\"KeepAlive\"}") } catch (_: Exception) {}
    }

    fun close() {
        sessionOpen = false
        try { socket?.send("{\"type\":\"CloseStream\"}") } catch (_: Exception) {}
        try { socket?.close(1000, "done") } catch (_: Exception) {}
        socket = null
        _state.value = DeepgramConnState.CLOSED
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt.set(0)
            _state.value = DeepgramConnState.OPEN
            _lastError = null
            Timber.tag(TAG).i("Socket open in %dms", System.currentTimeMillis() - connectMs)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _messagesReceived.incrementAndGet()
            val parsed = try {
                DeepgramMessageParser.parse(text)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Parse fail")
                null
            } ?: return
            if (parsed.requestId.isNotEmpty()) _lastRequestId = parsed.requestId
            if (parsed.text.isEmpty()) {
                if (!parsed.utteranceEnd) _emptyResults.incrementAndGet()
                if (parsed.utteranceEnd) {
                    _events.tryEmit(TranscriptEvent("", true, 0f, System.currentTimeMillis(), cfg?.source ?: AudioSource.LIVE))
                }
                return
            }
            _wordsReceived.addAndGet(parsed.text.split("\\s+".toRegex()).size.toLong())
            _events.tryEmit(
                TranscriptEvent(
                    parsed.text, parsed.isFinal, parsed.confidence,
                    System.currentTimeMillis(), cfg?.source ?: AudioSource.LIVE
                )
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code ?: -1
            Timber.tag(TAG).w(t, "Socket failure code=%d", code)
            if (code == 401 || code == 403) {
                authFailed = true
                sessionOpen = false
                _lastError = "Deepgram rejected the API key ($code). Check the key in Settings."
                _state.value = DeepgramConnState.AUTH_ERROR
                return
            }
            _lastError = "Deepgram connection lost: ${t.message ?: t.javaClass.simpleName}"
            if (!sessionOpen || authFailed) {
                _state.value = DeepgramConnState.FAILED
                return
            }
            val attempt = reconnectAttempt.incrementAndGet()
            val delayMs = minOf(1000L shl minOf(attempt.toInt(), 4), 15_000L)
            _state.value = DeepgramConnState.RECONNECTING
            Thread {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
                if (sessionOpen && !authFailed) openSocket()
            }.start()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (sessionOpen && !authFailed && _state.value != DeepgramConnState.CLOSED) {
                _state.value = DeepgramConnState.FAILED
            }
        }
    }

    companion object {
        private const val TAG = "Deepgram"
    }
}

