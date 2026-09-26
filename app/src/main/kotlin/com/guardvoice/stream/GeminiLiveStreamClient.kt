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
 * GeminiLiveStreamClient (now Deepgram+Groq 5s-overlap)
 * ------------------------------------------------------
 * Android-side WSS streaming client that:
 *  - Connects to backend WS proxy at /ws/audio-stream (Deepgram+Groq pipeline)
 *  - Captures 16kHz PCM mono and buffers to 5-sec windows with 1-sec overlap
 *    (window 160_000 B, overlap 32_000 B, step 128_000 B => ~4 sec new audio + 1 sec overlap)
 *    This guarantees flow: next window shares 1 sec with previous for STT continuity.
 *  - Sends each 5-sec window as Base64 audio_chunk over WSS (sequence monotonic)
 *  - Receives per-window transcription + Groq instant analysis (risk 0-100)
 *  - Falls back to local AccumulativeScoringEngine + ScamAnalyzer when offline
 *
 * Previous sec-by-sec (1s) Gemini logic replaced per user requirement.
 * Server (DeepgramGroqProxy) expects 5s windows with optional overlap; it also handles
 * server-side windowing if client sends smaller chunks. Client now explicitly windows.
 *
 * Audio cleaning (AEC/NS/AGC + DC removal) is done in AudioCaptureService before handoff.
 */
object GeminiLiveStreamClient {
    private const val TAG = "VoiceGuardStream"
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

    // ---- 5-sec window + 1-sec overlap ----
    // 16kHz * 2 bytes * 5 sec = 160_000 bytes per window
    // Overlap 1 sec = 32_000 bytes, step = 128_000 bytes (~4 sec new audio)
    private const val WINDOW_BYTES = 160_000
    private const val OVERLAP_BYTES = 32_000
    private const val STEP_BYTES = WINDOW_BYTES - OVERLAP_BYTES // 128k
    private const val WINDOW_DURATION_MS = 5_000
    private const val BYTES_PER_SEC = 32_000

    // While offline buffer up to 30s (960k) to survive network blips (tunnel/elevator)
    private const val MAX_BUFFERED_WHILE_OFFLINE = 960_000
    private val pcmLock = Any()
    private val pendingPcmStream = java.io.ByteArrayOutputStream(200_000)

    // Fallback scoring engine — runs locally when WS offline
    // 30-sec engine keeps backend-parity long-term cumulative (monotonic max).
    // For offline immediate 5-sec scoring we also run ScamAnalyzer per-batch directly
    // in feedFallbackTranscript() below, so UI updates every 5 sec even without WS.
    private val fallbackEngine = AccumulativeScoringEngine(windowDurationSec = 30)
    private var fallbackTranscriptAccum: StringBuilder = StringBuilder()
    private var didStartFallback = false

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
        // Flush any trailing PCM as final window(s) with overlap handling.
        // Keep WS alive briefly so final Deepgram+Groq analysis can return before close.
        // This guarantees post-hangup history contains last 5s window's score + number.
        val flushSid = sessionId
        flushPendingPcmAsFinal()
        // Delay close 3.5s to drain server processing (Deepgram ~800ms + Groq ~600ms) without blocking call-end UX.
        // Overlay/history will update via ACTION_VERDICT_CHANGED even after AudioCaptureService stopped.
        executor.schedule({
            try { ws?.close(1000, "call ended") } catch (_: Exception) {}
            ws = null
            isConnected.set(false)
            isConnecting.set(false)
            // Keep sessionId/fallback data for a moment more so late handleAnalysis still persists to DB
            executor.schedule({
                if (sessionId == flushSid) {
                    sessionId = ""
                    fallbackTranscriptAccum.clear()
                    synchronized(pcmLock) { pendingPcmStream.reset() }
                }
            }, 2000, TimeUnit.MILLISECONDS)
        }, 3500, TimeUnit.MILLISECONDS)
        // Immediately mark pendingPcm reset outside flush? No, flush already cleared it.
        // isConnected stays true for 3.5s so broadcasts still work.
    }

    /**
     * Called from AudioCaptureService.captureLoop for each 3200-byte (100ms) cleaned chunk.
     * We coalesce to 5-sec windows with 1-sec overlap before sending.
     */
    fun sendPcmChunk(pcm: ByteArray) {
        val sid = sessionId
        if (sid.isBlank()) return
        val windowsToSend = mutableListOf<ByteArray>()
        synchronized(pcmLock) {
            pendingPcmStream.write(pcm)
            // Bound memory while offline: keep most recent ~30s, discard oldest
            if (!isConnected.get() && pendingPcmStream.size() > MAX_BUFFERED_WHILE_OFFLINE) {
                val current = pendingPcmStream.toByteArray()
                pendingPcmStream.reset()
                val keepFrom = (current.size - MAX_BUFFERED_WHILE_OFFLINE).coerceAtLeast(0)
                pendingPcmStream.write(current, keepFrom, current.size - keepFrom)
            }
            // While we have a full window, emit with overlap retention
            // Only emit while connected; while offline we keep buffering (flushed on reconnect)
            while (isConnected.get() && pendingPcmStream.size() >= WINDOW_BYTES) {
                val all = pendingPcmStream.toByteArray()
                val window = all.copyOfRange(0, WINDOW_BYTES)
                windowsToSend.add(window)
                // Retain overlap tail + remaining bytes for next window
                // Keep last OVERLAP_BYTES of current window plus bytes beyond window
                val remaining = if (all.size > WINDOW_BYTES) all.copyOfRange(WINDOW_BYTES, all.size) else ByteArray(0)
                val overlapTail = window.copyOfRange(WINDOW_BYTES - OVERLAP_BYTES, WINDOW_BYTES)
                pendingPcmStream.reset()
                pendingPcmStream.write(overlapTail)
                if (remaining.isNotEmpty()) pendingPcmStream.write(remaining)
                // Safety: if overlap >= window, avoid infinite loop
                if (OVERLAP_BYTES >= WINDOW_BYTES) break
            }
        }
        // Send all windows outside lock (network I/O)
        for (win in windowsToSend) {
            sendWindow(sid, win, durationMs = WINDOW_DURATION_MS)
        }
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
        // If we have <2 sec, still send as partial (Deepgram handles short clips, but <0.3s skipped server-side)
        // For backlog up to 30s, we emit overlapping windows sequentially; partial tail also sent.
        // We generate overlapping windows from tail similarly to live path, but ignoring isConnected guard
        // so final seconds aren't lost on call end.
        var offset = 0
        var isFirst = true
        val pendingWindows = mutableListOf<Pair<ByteArray, Int>>()
        // Reconstruct overlapping windows from tail using step 128k
        // Need to handle that tail may already contain overlap from previous windows.
        // Simpler: if tail >= WINDOW_BYTES, slide with STEP_BYTES, else send as is if >=2 sec.
        if (tail.size >= WINDOW_BYTES) {
            while (offset + WINDOW_BYTES <= tail.size) {
                val window = tail.copyOfRange(offset, offset + WINDOW_BYTES)
                pendingWindows.add(Pair(window, WINDOW_DURATION_MS))
                offset += STEP_BYTES
                // For final partial beyond last full window, handle separately
            }
            val remaining = tail.size - offset
            if (remaining >= BYTES_PER_SEC * 2) { // >=2 sec meaningful
                val lastPartial = tail.copyOfRange(offset, tail.size)
                val dur = (lastPartial.size * 1000L / BYTES_PER_SEC).toInt().coerceIn(2000, WINDOW_DURATION_MS)
                pendingWindows.add(Pair(lastPartial, dur))
            } else if (remaining > 0 && isFirst && pendingWindows.isEmpty()) {
                // If only one partial and no full window, send it
                val lastPartial = tail.copyOfRange(offset, tail.size)
                if (lastPartial.size >= 9600) { // >=0.3s
                    val dur = (lastPartial.size * 1000L / BYTES_PER_SEC).toInt().coerceIn(300, WINDOW_DURATION_MS)
                    pendingWindows.add(Pair(lastPartial, dur))
                }
            }
        } else if (tail.size >= BYTES_PER_SEC * 2) {
            val dur = (tail.size * 1000L / BYTES_PER_SEC).toInt().coerceIn(2000, WINDOW_DURATION_MS)
            pendingWindows.add(Pair(tail, dur))
        }
        // Send all except if not connected, queue for reconnect? But stop() means call ended, so best effort
        for ((w, d) in pendingWindows) {
            // Only send if WS still connected; otherwise this flush is after stop, try once
            if (isConnected.get() && ws != null) {
                sendWindow(sid, w, durationMs = d)
            } else {
                // While offline at stop, we still attempt send if ws still open; else drop (call ended)
                if (ws != null) sendWindow(sid, w, durationMs = d)
            }
            if (!isConnected.get()) break
        }
    }

    private fun sendWindow(sid: String, pcm: ByteArray, durationMs: Int) {
        val seq = sequence.getAndIncrement()
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("type", "audio_chunk")
            put("sessionId", sid)
            put("sequence", seq)
            put("pcmBase64", b64)
            put("durationMs", durationMs)
            put("timestamp", System.currentTimeMillis())
        }
        if (isConnected.get() && ws != null) {
            try {
                ws?.send(json.toString())
                Log.d(TAG, "Sent window seq=$seq ${pcm.size}B ${durationMs}ms")
            } catch (e: Exception) {
                Log.w(TAG, "WS send failed, queueing fallback", e)
                scheduleReconnect()
            }
        }
    }

    private fun connect() {
        if (isConnecting.getAndSet(true)) return
        val ctx = appContext
        val url = resolveWsUrl()
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
        Log.i(TAG, "Connecting to $url (session $sessionId) — 5s window 1s overlap")
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS open ${response.code}")
                isConnected.set(true)
                isConnecting.set(false)
                reconnectAttempts = 0
                val start = JSONObject().apply {
                    put("type", "start_session")
                    put("sessionId", sessionId)
                    put("platform", "android")
                    put("sampleRate", 16000)
                }
                webSocket.send(start.toString())
                publishStatus(StreamStatus.Connected)
                // Flush buffered while offline? For overlap model, pendingPcmStream already contains tail with overlap
                // Emit any full windows now
                val windowsToSend = mutableListOf<ByteArray>()
                synchronized(pcmLock) {
                    while (pendingPcmStream.size() >= WINDOW_BYTES) {
                        val all = pendingPcmStream.toByteArray()
                        val window = all.copyOfRange(0, WINDOW_BYTES)
                        windowsToSend.add(window)
                        val remaining = if (all.size > WINDOW_BYTES) all.copyOfRange(WINDOW_BYTES, all.size) else ByteArray(0)
                        val overlapTail = window.copyOfRange(WINDOW_BYTES - OVERLAP_BYTES, WINDOW_BYTES)
                        pendingPcmStream.reset()
                        pendingPcmStream.write(overlapTail)
                        if (remaining.isNotEmpty()) pendingPcmStream.write(remaining)
                    }
                }
                for (w in windowsToSend) sendWindow(sessionId, w, WINDOW_DURATION_MS)
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
                    if (obj.optString("status") == "gemini_connected" || obj.optString("status") == "connected") publishStatus(StreamStatus.GeminiConnected)
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

        // Cumulative verdict derived from cumulative score so active meter never flickers
        // down when a safe 5s window arrives after a scam spike.
        val cumVerdict = when {
            cumScore >= 60 -> CallVerdict.Scam
            cumScore >= 25 -> CallVerdict.Suspicious
            cumScore > 0 -> CallVerdict.Suspicious
            transcriptWin.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= 6 -> CallVerdict.Safe
            else -> CallVerdict.Pending
        }
        val displayVerdict = if (cumVerdict == CallVerdict.Scam) cumVerdict else verdict // prefer cumulative scam, else window
        val summary = if (reasons.isNotEmpty()) reasons.joinToString(". ") else when (displayVerdict) {
            CallVerdict.Scam -> "Scam patterns detected (window ${obj.optInt("windowIndex", 0) + 1}, active ${cumScore})"
            CallVerdict.Suspicious -> "Suspicious — active ${cumScore} (window ${winScore})"
            CallVerdict.Safe -> "Conversation appears safe so far. Active ${cumScore}"
            else -> "Analyzing conversation... ${cumScore}"
        }

        // Active score = cumulative (keeps history of 5s chunks), granular window score kept in detections
        CallSessionRepository.saveAnalysis(
            context = ctx,
            sessionId = sessionId,
            verdict = cumVerdict,
            riskScore = cumScore,
            transcriptPreview = transcriptWin.ifBlank { obj.optString("transcriptAccumulated", "") }.ifBlank { fallbackTranscriptAccum.toString() },
            summary = summary,
            reasons = reasons
        )
        try {
            // Per-5s granular history (window score) — lets history show each chunk's spike
            GuardVoiceRepository.getInstance(ctx).insertDetection(
                sessionId = sessionId,
                transcript = transcriptWin.ifBlank { fallbackTranscriptAccum.toString() },
                verdict = verdict,
                riskScore = winScore,
                reasons = reasons,
                chunkIndex = obj.optInt("windowIndex", 0)
            )
            // Only double-insert cumulative when it diverges significantly (keeps “active” progression too)
            if (cumScore != winScore && cumScore > winScore + 5) {
                GuardVoiceRepository.getInstance(ctx).insertDetection(
                    sessionId = sessionId,
                    transcript = "Active cumulative update",
                    verdict = cumVerdict,
                    riskScore = cumScore,
                    reasons = reasons,
                    chunkIndex = obj.optInt("windowIndex", 0) + 10000
                )
            }
        } catch (e: Exception) { Log.w(TAG, "DB persist failed", e) }

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

    private fun startFallbackTicker() {
        tickTask?.cancel(false)
        tickTask = executor.scheduleAtFixedRate({
            if (isConnected.get()) return@scheduleAtFixedRate
            val snap = fallbackEngine.tick()
            if (snap != null && snap.transcriptWindow.isNotBlank()) {
                handleLocalWindow(snap)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    fun feedFallbackTranscript(transcript: String) {
        if (transcript.isBlank()) return
        fallbackTranscriptAccum.append(if (fallbackTranscriptAccum.isEmpty()) transcript else " $transcript")
        didStartFallback = true
        val snap = fallbackEngine.appendTranscript(transcript)
        if (snap != null) handleLocalWindow(snap)
        // Immediate per-5s local scoring so offline UI updates every chunk, not every 30s
        // (fallbackEngine only finalizes every 30s; this keeps 5s granularity per requirement)
        if (snap == null) handleImmediateLocalTranscript(transcript)
    }

    private fun handleLocalWindow(snap: AccumulativeScoringEngine.WindowSnapshot) {
        val ctx = appContext ?: return
        val cumScore = fallbackEngine.getCumulativeScore()
        val allReasons = fallbackEngine.getCumulativeReasons()
        val summary = if (snap.reasons.isNotEmpty()) snap.reasons.joinToString(". ") else "Local analysis (offline window ${snap.windowIndex + 1})"
        CallSessionRepository.saveAnalysis(ctx, sessionId, snap.verdict, cumScore, snap.transcriptWindow, summary, allReasons)
        try {
            GuardVoiceRepository.getInstance(ctx).insertDetection(
                sessionId = sessionId,
                transcript = snap.transcriptWindow,
                verdict = snap.verdict,
                riskScore = cumScore,
                reasons = allReasons
            )
        } catch (e: Exception) { Log.w(TAG, "DB persist local window failed", e) }
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

    /** Immediate 5s local analysis (offline) — keeps active score live every chunk, not only every 30s window. */
    private fun handleImmediateLocalTranscript(delta: String) {
        if (isConnected.get()) return // online: WS is source of truth, don't duplicate
        val ctx = appContext ?: return
        val immediate = ScamAnalyzer.analyze(delta)
        // Update cumulative as monotonic max (mirrors AccumulativeScoringEngine but per-5s)
        val prevCum = fallbackEngine.getCumulativeScore()
        // We piggyback on engine's cumulativeReasons via manual track, but also emit immediate reasons
        // For cumulative we keep max; ScamAnalyzer's 5s score drives live meter.
        val effectiveCum = maxOf(prevCum, immediate.riskScore)
        // Manually bump engine's cumulative if immediate exceeds it by poking via private state?
        // Instead we track effectiveCum for UI and persist; engine's 30s window will reconcile later.
        val summary = if (immediate.reasons.isNotEmpty()) immediate.reasons.joinToString(". ") else when (immediate.verdict) {
            CallVerdict.Scam -> "Scam pattern in last 5s"
            CallVerdict.Suspicious -> "Suspicious in last 5s"
            CallVerdict.Safe -> "Conversation appears safe (last 5s)"
            else -> "Analyzing last 5s..."
        }
        // Persist current active score — riskScore is effective cumulative, transcript is delta (5s window)
        CallSessionRepository.saveAnalysis(ctx, sessionId, immediate.verdict, effectiveCum, delta, summary, immediate.reasons)
        try {
            GuardVoiceRepository.getInstance(ctx).insertDetection(
                sessionId = sessionId,
                transcript = delta,
                verdict = immediate.verdict,
                riskScore = effectiveCum,
                reasons = immediate.reasons
            )
        } catch (e: Exception) { Log.w(TAG, "DB persist immediate local failed", e) }
        ctx.sendBroadcast(
            Intent(AudioCaptureService.ACTION_VERDICT_CHANGED)
                .setPackage(ctx.packageName)
                .putExtra(AudioCaptureService.EXTRA_RISK_LEVEL, immediate.verdict.name)
                .putExtra(AudioCaptureService.EXTRA_RISK_SCORE, effectiveCum)
                .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT, delta)
                .putExtra(AudioCaptureService.EXTRA_REASONS, immediate.reasons.toTypedArray())
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
        val fromBuild = try { BuildConfig.BACKEND_WS_URL.trim() } catch (_: Exception) { "" }
        if (fromBuild.isNotBlank()) return fromBuild
        return "ws://10.0.2.2:4000/ws/audio-stream"
    }
}
