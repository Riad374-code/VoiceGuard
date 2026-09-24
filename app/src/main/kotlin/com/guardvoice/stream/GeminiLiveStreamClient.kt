package com.guardvoice.stream

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.guardvoice.BuildConfig
import com.guardvoice.call.AudioCaptureService
import com.guardvoice.call.ScamAnalyzer
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.data.CallVerdict
import com.guardvoice.db.GuardVoiceRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * GeminiLiveStreamClient
 * ----------------------
 * Android-side streaming client that:
 *  - Connects to backend WS proxy at /ws/audio-stream (GEMINI proxy)
 *  - Streams 16kHz PCM chunks as Base64 every 100-500ms (from AudioCaptureService)
 *  - Receives continuous transcription + 30s-window analysis with accumulative scores
 *  - Falls back to local AccumulativeScoringEngine + ScamAnalyzer when WS is offline
 *
 * Accumulative sending: we NEVER resend old PCM. Each chunk is sent once with monotonic
 * sequence. Server maintains accumulator. If we reconnect mid-call we resume from new sequence
 * but server-side accumulator persists per sessionId.
 */
object GeminiLiveStreamClient {
    private const val TAG = "GeminiLiveStream"
    private const val RECONNECT_MAX = 10
    private const val RECONNECT_BASE_MS = 1000L
    private const val PING_INTERVAL_SEC = 25L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var ws: WebSocket? = null
    private var sessionId: String = ""
    private var appContext: Context? = null
    private val sequence = AtomicInteger(0)
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // ---- Sec-by-sec coalescing: AudioCaptureService sends 100ms (3200B) reads;
    // we buffer to exactly ~1 sec (32000 B) before WS send. Reduces Gemini
    // realtimeInput calls by 10x while preserving "second by second" contract.
    // Using ByteArrayOutputStream avoids O(n^2) removeAt(0) shifts from MutableList<Byte>.
    private const val SEC_CHUNK_BYTES = 32_000 // 16kHz * 2 bytes * 1.0 sec
    // While offline we keep buffering up to 30s of audio so a mid-call network blip
    // (elevator, tunnel, WiFi->LTE) doesn't lose that speech — flushed on reconnect.
    private const val MAX_BUFFERED_WHILE_OFFLINE = 960_000 // 30 sec cap — prevents OOM if WS offline
    private val pcmLock = Any()
    private val pendingPcmStream = java.io.ByteArrayOutputStream(64_000)

    // Fallback scoring engine — runs locally when WS offline
    private val fallbackEngine = AccumulativeScoringEngine(windowDurationSec = 30)
    private var fallbackTranscriptAccum: StringBuilder = StringBuilder()
    private var didStartFallback = false

    // To avoid double-fallback spam, throttle local window ticks to 1s
    private var tickTask: java.util.concurrent.ScheduledFuture<*>? = null

    fun start(context: Context, sessionId: String) {
        this.appContext = context.applicationContext
        this.sessionId = sessionId
        this.sequence.set(0)
        this.reconnectAttempts = 0
        synchronized(pcmLock) { pendingPcmStream.reset() }
        this.fallbackTranscriptAccum = StringBuilder()
        this.didStartFallback = false
        this.fallbackEngine.start()
        connect()
        startFallbackTicker()
    }

    fun stop() {
        tickTask?.cancel(false)
        tickTask = null
        // Flush any trailing <1 sec PCM as a final chunk so last second isn't lost
        flushPendingPcmAsFinal()
        try { ws?.close(1000, "call ended") } catch (_: Exception) {}
        ws = null
        isConnected.set(false)
        isConnecting.set(false)
        sessionId = ""
        fallbackTranscriptAccum.clear()
        synchronized(pcmLock) { pendingPcmStream.reset() }
    }

    /** Called from AudioCaptureService.captureLoop for each 3200-byte (100ms) cleaned chunk.
     *  We coalesce to ~1 sec frames before sending — ensures true sec-by-sec
     *  delivery to Gemini (prompt sent once at start_session, not per chunk).
     */
    fun sendPcmChunk(pcm: ByteArray) {
        val sid = sessionId
        if (sid.isBlank()) return
        val frameToSend: ByteArray? = synchronized(pcmLock) {
            pendingPcmStream.write(pcm)
            // Bound memory while offline: keep most recent ~30s, discard older audio
            if (!isConnected.get() && pendingPcmStream.size() > MAX_BUFFERED_WHILE_OFFLINE) {
                val current = pendingPcmStream.toByteArray()
                pendingPcmStream.reset()
                val keepFrom = (current.size - MAX_BUFFERED_WHILE_OFFLINE).coerceAtLeast(0)
                pendingPcmStream.write(current, keepFrom, current.size - keepFrom)
            }
            // Only extract full 1-sec frames while connected — while offline audio stays
            // buffered (bounded) and is flushed as one frame on reconnect.
            if (isConnected.get() && pendingPcmStream.size() >= SEC_CHUNK_BYTES) {
                val all = pendingPcmStream.toByteArray()
                val out = all.copyOfRange(0, SEC_CHUNK_BYTES)
                pendingPcmStream.reset()
                if (all.size > SEC_CHUNK_BYTES) {
                    pendingPcmStream.write(all, SEC_CHUNK_BYTES, all.size - SEC_CHUNK_BYTES)
                }
                out
            } else null
        }
        if (frameToSend != null) sendOneSecFrame(sid, frameToSend, durationMs = 1000)
        // Else buffered — will fire once we accumulate 1 sec. Keeps WS rate at 1 msg/sec.
    }

    private fun flushPendingPcmAsFinal() {
        val sid = sessionId
        if (sid.isBlank()) return
        val tail: ByteArray? = synchronized(pcmLock) {
            if (pendingPcmStream.size() == 0) null else {
                val out = pendingPcmStream.toByteArray()
                pendingPcmStream.reset()
                out
            }
        }
        if (tail == null || tail.isEmpty()) return
        // Backlog may be up to 30s (960k). Send as successive 1-sec frames so Gemini
        // receives proper sec-by-sec chunks with monotonic sequence, not one huge blob.
        var offset = 0
        while (offset < tail.size) {
            val end = minOf(offset + SEC_CHUNK_BYTES, tail.size)
            val chunk = tail.copyOfRange(offset, end)
            val dur = (chunk.size * 1000L / 32000L).toInt().coerceIn(100, 1000)
            sendOneSecFrame(sid, chunk, durationMs = dur)
            offset = end
            // If connection dropped mid-flush, stop — remaining bytes stay discarded
            // (next reconnect will flush whatever is newly buffered).
            if (!isConnected.get()) break
        }
    }

    private fun sendOneSecFrame(sid: String, pcm: ByteArray, durationMs: Int) {
        val seq = sequence.getAndIncrement()
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("type", "audio_chunk")
            put("sessionId", sid)
            put("sequence", seq)
            put("pcmBase64", b64)
            put("durationMs", durationMs)
            put("timestamp", System.currentTimeMillis())
            // NOTE: no prompt here — systemInstruction was sent once at start_session
            // and stored server-side in GeminiLiveProxy. Resending prompt each sec
            // would waste tokens and reset Gemini state.
        }
        if (isConnected.get() && ws != null) {
            try {
                ws?.send(json.toString())
            } catch (e: Exception) {
                Log.w(TAG, "WS send failed, queueing fallback", e)
                scheduleReconnect()
            }
        }
    }

    private fun connect() {
        if (isConnecting.getAndSet(true)) return
        val ctx = appContext
        val url = resolveWsUrl() // e.g. wss://backend.example.com/ws/audio-stream
        if (url.isBlank()) {
            Log.w(TAG, "Backend WS URL not configured; running in local-only fallback mode.")
            isConnecting.set(false)
            return
        }
        if (sessionId.isBlank()) {
            isConnecting.set(false)
            return
        }
        val request = Request.Builder().url(url).build()
        Log.i(TAG, "Connecting to $url (session $sessionId)")
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS open ${response.code}")
                isConnected.set(true)
                isConnecting.set(false)
                reconnectAttempts = 0
                // Handshake: start_session
                val start = JSONObject().apply {
                    put("type", "start_session")
                    put("sessionId", sessionId)
                    put("platform", "android")
                    put("sampleRate", 16000)
                }
                webSocket.send(start.toString())
                publishStatus(StreamStatus.Connected)
                // Send any PCM buffered while offline (bounded to ~30s) so a reconnect
                // mid-call doesn't lose speech. Sequence stays monotonic.
                flushPendingPcmAsFinal()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closing $code $reason")
                isConnected.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed $code $reason")
                isConnected.set(false)
                isConnecting.set(false)
                if (code != 1000) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure ${t.message} code=${response?.code}")
                isConnected.set(false)
                isConnecting.set(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= RECONNECT_MAX) {
            Log.w(TAG, "Max reconnect reached; staying in local fallback mode.")
            publishStatus(StreamStatus.Error)
            return
        }
        reconnectAttempts++
        val delay = (RECONNECT_BASE_MS * Math.pow(2.0, (reconnectAttempts - 1).toDouble())).toLong()
            .coerceAtMost(10000L) + (Math.random() * 500).toLong()
        Log.i(TAG, "Reconnect #$reconnectAttempts in ${delay}ms")
        executor.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
        publishStatus(StreamStatus.Reconnecting)
    }

    private fun handleServerMessage(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "transcription" -> handleTranscription(obj)
                "analysis" -> handleAnalysis(obj)
                "status" -> {
                    Log.d(TAG, "Status: ${obj.optString("status")} ${obj.optString("message")}")
                    if (obj.optString("status") == "gemini_connected") publishStatus(StreamStatus.GeminiConnected)
                }
                "error" -> Log.w(TAG, "Server error ${obj.optString("code")}: ${obj.optString("message")}")
                else -> Log.d(TAG, "Unknown msg type: $text")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad server message: $text", e)
        }
    }

    private fun handleTranscription(obj: JSONObject) {
        val delta = obj.optString("textDelta")
        val accum = obj.optString("transcriptAccumulated")
        val ctx = appContext ?: return
        if (accum.isNotBlank()) fallbackTranscriptAccum = StringBuilder(accum)

        // Broadcast transcript delta for overlay UI (new EXTRA)
        ctx.sendBroadcast(
            Intent(AudioCaptureService.ACTION_TRANSCRIPT_CHANGED)
                .setPackage(ctx.packageName)
                .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT, accum.ifBlank { delta })
                .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT_DELTA, delta)
                .putExtra(AudioCaptureService.EXTRA_SESSION_ID, sessionId)
        )
    }

    private fun handleAnalysis(obj: JSONObject) {
        val ctx = appContext ?: return
        val winScore = obj.optInt("windowRiskScore", 0)
        val cumScore = obj.optInt("cumulativeRiskScore", winScore)
        val verdictStr = obj.optString("verdict", "pending")
        val verdict = mapRiskToVerdict(verdictStr)
        val transcriptWin = obj.optString("transcriptWindow", "")
        val reasons = jsonArrayToList(obj.optJSONArray("reasons"))
        val keywords = jsonArrayToList(obj.optJSONArray("keywords"))
        val elapsed = obj.optDouble("elapsedSec", 0.0).toFloat()

        // Persist to CallSessionRepository so Dashboard + Summary can show accumulative progress
        val summary = if (reasons.isNotEmpty()) reasons.joinToString(". ") else when (verdict) {
            CallVerdict.Scam -> "Scam patterns detected (window ${obj.optInt("windowIndex", 0) + 1})"
            CallVerdict.Suspicious -> "Suspicious elements detected in this window."
            CallVerdict.Safe -> "Conversation appears safe so far."
            else -> "Analyzing conversation..."
        }

        CallSessionRepository.saveAnalysis(
            context = ctx,
            sessionId = sessionId,
            verdict = verdict,
            riskScore = cumScore,
            transcriptPreview = transcriptWin.ifBlank { obj.optString("transcriptAccumulated", "") }.ifBlank { fallbackTranscriptAccum.toString() },
            summary = summary,
            reasons = reasons
        )
        // Also persist to SQLite
        try {
            GuardVoiceRepository.getInstance(ctx).insertDetection(
                sessionId = sessionId,
                transcript = transcriptWin.ifBlank { fallbackTranscriptAccum.toString() },
                verdict = verdict,
                riskScore = cumScore,
                reasons = reasons
            )
        } catch (e: Exception) { Log.w(TAG, "DB persist failed", e) }

        // Broadcast verdict (accumulative score) — overlay subscribes
        ctx.sendBroadcast(
            Intent(AudioCaptureService.ACTION_VERDICT_CHANGED)
                .setPackage(ctx.packageName)
                .putExtra(AudioCaptureService.EXTRA_RISK_LEVEL, verdict.name)
                .putExtra(AudioCaptureService.EXTRA_RISK_SCORE, cumScore)
                .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT, transcriptWin)
                .putExtra(AudioCaptureService.EXTRA_REASONS, reasons.toTypedArray())
                .putExtra(AudioCaptureService.EXTRA_KEYWORDS, keywords.toTypedArray())
                .putExtra(AudioCaptureService.EXTRA_ELAPSED_SEC, elapsed)
                .putExtra(AudioCaptureService.EXTRA_SESSION_ID, sessionId)
        )
    }

    // ---- Fallback ticker: when WS offline we still produce local window analyses ----
    private fun startFallbackTicker() {
        tickTask?.cancel(false)
        tickTask = executor.scheduleAtFixedRate({
            if (isConnected.get()) return@scheduleAtFixedRate
            // Only produce local analysis if we have at least some transcript accumulation downstream?
            // For offline demo we synthesize from empty — tick() will finalize empty windows as pending.
            // To make offline useful, we'd need local STT; here we just keep cumulative as pending
            // unless CallAudioStream already produced transcripts that we can feed.
            val snap = fallbackEngine.tick()
            if (snap != null && snap.transcriptWindow.isNotBlank()) {
                handleLocalWindow(snap)
            } else if (snap != null && didStartFallback) {
                // Even empty windows should emit to keep elapsed ticking
                // but we suppress noise for empty call starts
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** Called when CallAudioStream produces a transcription (offline Groq path) so we can score it. */
    fun feedFallbackTranscript(transcript: String) {
        if (transcript.isBlank()) return
        fallbackTranscriptAccum.append(if (fallbackTranscriptAccum.isEmpty()) transcript else " $transcript")
        didStartFallback = true
        val snap = fallbackEngine.appendTranscript(transcript)
        if (snap != null) handleLocalWindow(snap)
    }

    private fun handleLocalWindow(snap: AccumulativeScoringEngine.WindowSnapshot) {
        val ctx = appContext ?: return
        val cumScore = fallbackEngine.getCumulativeScore()
        val allReasons = fallbackEngine.getCumulativeReasons()
        val summary = if (snap.reasons.isNotEmpty()) snap.reasons.joinToString(". ") else "Local analysis (offline)"
        CallSessionRepository.saveAnalysis(ctx, sessionId, snap.verdict, cumScore, snap.transcriptWindow, summary, allReasons)
        ctx.sendBroadcast(
            Intent(AudioCaptureService.ACTION_VERDICT_CHANGED)
                .setPackage(ctx.packageName)
                .putExtra(AudioCaptureService.EXTRA_RISK_LEVEL, snap.verdict.name)
                .putExtra(AudioCaptureService.EXTRA_RISK_SCORE, cumScore)
                .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT, snap.transcriptWindow)
                .putExtra(AudioCaptureService.EXTRA_REASONS, allReasons.toTypedArray())
                .putExtra(AudioCaptureService.EXTRA_SESSION_ID, sessionId)
        )
    }

    private fun mapRiskToVerdict(s: String): CallVerdict = when (s.lowercase()) {
        "safe" -> CallVerdict.Safe
        "suspicious" -> CallVerdict.Suspicious
        "scam" -> CallVerdict.Scam
        else -> CallVerdict.Pending
    }

    private fun jsonArrayToList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf { it.isNotBlank() } }
    }

    private fun publishStatus(status: StreamStatus) {
        val ctx = appContext ?: return
        ctx.sendBroadcast(
            Intent(AudioCaptureService.ACTION_STREAM_STATUS_CHANGED)
                .setPackage(ctx.packageName)
                .putExtra(AudioCaptureService.EXTRA_STREAM_STATUS, status.name)
                .putExtra(AudioCaptureService.EXTRA_SESSION_ID, sessionId)
        )
    }

    fun isStreamingConnected(): Boolean = isConnected.get()

    private fun resolveWsUrl(): String {
        val ctx = appContext
        if (ctx != null) {
            val fromSettings = StreamSettings.getBackendWsUrl(ctx).trim()
            if (fromSettings.isNotBlank()) return fromSettings
        }
        // Fallback to BuildConfig / emulator default (also used before any context set)
        val fromBuild = try { BuildConfig.BACKEND_WS_URL.trim() } catch (_: Exception) { "" }
        if (fromBuild.isNotBlank()) return fromBuild
        return "ws://10.0.2.2:4000/ws/audio-stream"
    }
}
