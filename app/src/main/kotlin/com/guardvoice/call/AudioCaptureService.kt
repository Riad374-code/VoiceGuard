package com.guardvoice.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.guardvoice.MainActivity
import com.guardvoice.R
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.stream.GeminiLiveStreamClient

class AudioCaptureService : Service() {
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val callStateMonitor by lazy {
        CallStateMonitor(this) {
            stopCapture()
            stopSelf()
        }
    }
    private val captureLock = Any()
    private var activeRecorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var wasSpeakerphoneOn = false
    private var activeSessionId = ""
    // Hardware audio effect — AGC only, enabled once per call.
    // NO AcousticEchoCanceler and NO NoiseSuppressor, deliberately:
    // the far-end voice coming out of the speaker IS our signal (speaker-call
    // capture). AEC's entire job is subtracting speaker output from the mic —
    // it was erasing the opposite side's voice even at full volume. NS eats
    // faint bleed as "background noise" for the same reason.
    private var gainControl: AutomaticGainControl? = null
    private val progressLock = Any()
    private var pendingAudioBytes = 0L
    private var pendingAudioChunks = 0
    private var lowVolumeChunkCount = 0
    private var didSendLowVolumeAlert = false
    // ---- Mic-source resilience (Redmi/MIUI mutes VOICE_COMMUNICATION in calls) ----
    // If the active source delivers no usable voice for ~15s, rotate to the next
    // source in the chain. A quiet room looks the same, so rotations are capped
    // per call — harmless there, potentially rescuing on muted-by-OS devices.
    private var activeAudioSource = MediaRecorder.AudioSource.MIC
    private var silenceChunkCount = 0
    private var sourceRotations = 0

    @Volatile
    private var isCaptureRunning = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        callStateMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(
                phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty(),
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            )
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        callStateMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(phoneNumber: String, sessionId: String) {
        // Call-waiting / second SIM while running: stop the old session first so
        // the new call's audio isn't attributed to the stale session id.
        // (Outside captureLock — stopCapture joins the capture thread.)
        if (isCaptureRunning && sessionId != activeSessionId) {
            stopCapture()
        }
        activeSessionId = sessionId
        if (!hasAudioPermission()) {
            CallSessionRepository.markFailed(
                this,
                activeSessionId,
                "Microphone permission is missing."
            )
            publishState(CaptureState.Failed)
            stopSelf()
            return
        }

        try {
            synchronized(captureLock) {
                if (isCaptureRunning) {
                    return
                }
                startForegroundCapture(phoneNumber)
                activateSpeakerMode()
                val recorder = buildRecorderWithFallback() ?: run {
                    restoreAudioMode()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    CallSessionRepository.markFailed(
                        this,
                        activeSessionId,
                        "Audio recorder could not be initialized."
                    )
                    publishState(CaptureState.Failed)
                    stopSelf()
                    return
                }
                activeRecorder = recorder
                // ---- Audio cleaning: hardware AGC only (see field note) ----
                // AEC/NS are off: they erase the speaker-side voice we capture.
                enableAudioEffects(recorder.audioSessionId)
                isCaptureRunning = true
                // Fresh per-call state — the service instance survives across calls,
                // so stale volume counters must not leak into the next call.
                lowVolumeChunkCount = 0
                didSendLowVolumeAlert = false
                silenceChunkCount = 0
                sourceRotations = 0
                activeSampleRateHz = SAMPLE_RATE_HZ
                micDiagRms = 0.0
                micDiagMaxRms = 0.0
                micDiagRecentMaxRms = 0.0
                CallAudioStream.init(this, activeSessionId)
                // Start streaming to Deepgram+Groq proxy — 5-sec windows with 1-sec overlap.
                // Voice is Buffered to 5s windows (only 1s overlap kept in RAM, raw PCM discarded post-send).
                // Backend DeepgramGroqProxy streams transcription + Groq gpt-oss scoring per window,
                // active cumulative score kept via AccumulativeScoringEngine and persisted to history.
                try { GeminiLiveStreamClient.start(this, activeSessionId) } catch (e: Exception) { Log.w(TAG, "Deepgram/Groq stream start failed, will use local fallback", e) }
                captureThread = Thread({ captureLoop(recorder) }, "GuardVoiceAudioCapture").apply {
                    start()
                }
                CallSessionRepository.markListening(this, activeSessionId)
                publishState(CaptureState.Listening)
            }
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Audio capture startup failed.", exception)
            synchronized(captureLock) {
                isCaptureRunning = false
                activeRecorder?.releaseSafely()
                activeRecorder = null
                captureThread = null
            }
            restoreAudioMode()
            stopForeground(STOP_FOREGROUND_REMOVE)
            CallSessionRepository.markFailed(
                this,
                activeSessionId,
                "Audio capture startup failed."
            )
            publishState(CaptureState.Failed)
            stopSelf()
        }
    }

    private fun stopCapture() {
        val threadToJoin: Thread?
        val recorderToRelease: AudioRecord?
        synchronized(captureLock) {
            if (!isCaptureRunning && activeRecorder == null) {
                return
            }
            isCaptureRunning = false
            threadToJoin = captureThread
            recorderToRelease = activeRecorder
            captureThread = null
            activeRecorder = null
        }

        if (Thread.currentThread() != threadToJoin) {
            threadToJoin?.join(JOIN_TIMEOUT_MS)
        }
        flushAudioProgress()
        recorderToRelease?.releaseSafely()
        releaseAudioEffects()
        CallAudioStream.reset()
        try { GeminiLiveStreamClient.stop() } catch (_: Exception) {}
        restoreAudioMode()
        CallSessionRepository.markCompleted(this, activeSessionId)
        publishState(CaptureState.Stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun captureLoop(initialRecorder: AudioRecord) {
        var recorder = initialRecorder
        var didFail = false
        try {
            recorder.startRecording()
            val buffer = ByteArray(AUDIO_BUFFER_BYTES)
            while (isCaptureRunning) {
                val bytesRead = recorder.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    // ---- Software PCM cleaning (lightweight, if hardware AEC/NS not enough) ----
                    // Removes DC offset + applies soft limiter to avoid clipping; recommended for
                    // dynamic call audio where earpiece/speaker routing adds hum.
                    val cleaned = cleanPcm16Mono(buffer, bytesRead)
                    val chunk = cleaned
                    // Offline fallback (5s batches, no voice saved) — only active when WSS down
                    CallAudioStream.accept(activeSessionId, chunk)
                    // Primary pipeline: 5-sec WSS windows with 1-sec overlap to backend Deepgram+Groq
                    // GeminiLiveStreamClient discards window bytes after send, retains only 32k overlap in RAM.
                    // Scores per window update active cumulative + history DB.
                    try { GeminiLiveStreamClient.sendPcmChunk(chunk) } catch (_: Exception) {}
                    recordAudioProgress(bytesRead)
                    // Use cleaned chunk for volume check — reflects what Gemini actually receives.
                    val rms = evaluateVolumeLevel(chunk, chunk.size)
                    micDiagRms = rms
                    if (rms > micDiagMaxRms) micDiagMaxRms = rms
                    if (rms > micDiagRecentMaxRms) micDiagRecentMaxRms = rms
                    // Quiet-stream watchdog: a muted-by-OS mic (Redmi in-call)
                    // reads silence forever. Rotate the audio source to recover.
                    if (rms < SILENCE_RMS_THRESHOLD) {
                        silenceChunkCount++
                        if (silenceChunkCount >= SILENCE_CHUNKS_BEFORE_ROTATE &&
                            sourceRotations < MAX_SOURCE_ROTATIONS && isCaptureRunning
                        ) {
                            silenceChunkCount = 0
                            rotateAudioSource(recorder)?.let { rotated ->
                                recorder = rotated
                            }
                        }
                    } else {
                        silenceChunkCount = 0
                    }
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read failed with code $bytesRead.")
                    didFail = true
                    break
                } else {
                    // bytesRead == 0: HAL delivered nothing — sleep instead of
                    // busy-spinning and burning battery.
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (exception: IllegalStateException) {
            Log.e(TAG, "Audio capture could not start.", exception)
            didFail = true
        } catch (e: RuntimeException) {
            // read()/accept() can throw IllegalArgumentException / SecurityException
            // (mic revoked mid-call on MIUI) on hostile HALs. Fail loudly with a
            // Failed broadcast instead of dying silent with "Listening" stuck on.
            Log.e(TAG, "Audio capture loop failed.", e)
            didFail = true
        } finally {
            if (didFail && isCaptureRunning) {
                cleanupFailedCapture(recorder)
            }
        }
    }

    /**
     * Hot-swap to the next audio source in the chain when the current one
     * delivers only digital silence (muted by OS). Builds the replacement
     * BEFORE touching the live recorder so a failed probe keeps audio flowing.
     */
    private fun rotateAudioSource(old: AudioRecord): AudioRecord? {
        val chain = AUDIO_SOURCE_CHAIN
        val nextSource = chain[(chain.indexOf(activeAudioSource) + 1) % chain.size]
        val replacement = try {
            // Probe at the working rate first — if startup fell back to 44.1/8k,
            // a 16 kHz probe here would fail and burn a rotation for nothing.
            buildRecorder(nextSource, activeSampleRateHz)
        } catch (e: Exception) {
            Log.w(TAG, "Source rotation probe failed for $nextSource", e)
            null
        } ?: return null
        try {
            replacement.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Rotated recorder could not start (source=$nextSource)", e)
            replacement.releaseSafely()
            return null
        }
        synchronized(captureLock) {
            if (activeRecorder == old) {
                activeRecorder = replacement
            } else {
                // stopCapture() won the race (call ended mid-rotation) — release
                // the probe instead of orphaning it with the mic held open.
                replacement.releaseSafely()
                return null
            }
        }
        // Stop/release OUTSIDE the lock: AudioRecord.stop() can block, and
        // stopCapture() must never stall behind a rotation during hangup.
        try { old.stop() } catch (_: IllegalStateException) {}
        try { old.release() } catch (_: Exception) {}
        releaseAudioEffects()
        enableAudioEffects(replacement.audioSessionId)
        activeAudioSource = nextSource
        sourceRotations++
        // Keep the non-16k rate suffix (set at startup) — without it a
        // rotated label hides the rate Deepgram windows are labeled with.
        val rateSuffix = if (activeSampleRateHz != 16_000) "@${activeSampleRateHz / 1000}k" else ""
        micDiagLabel = "${sourceLabel(nextSource)}#$sourceRotations$rateSuffix"
        Log.i(TAG, "Rotated audio source to $nextSource (rotation #$sourceRotations)")
        return replacement
    }

    private fun sourceLabel(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICECOMM"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICEREC"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROC"
        MediaRecorder.AudioSource.CAMCORDER -> "CAM"
        else -> "SRC$source"
    }

    private fun evaluateVolumeLevel(buffer: ByteArray, bytesRead: Int): Double {
        val rms = rmsOfPcm16(buffer, bytesRead)
        if (didSendLowVolumeAlert) return rms
        if (rms < LOW_VOLUME_RMS_THRESHOLD) {
            lowVolumeChunkCount++
            if (lowVolumeChunkCount >= LOW_VOLUME_CHUNK_THRESHOLD && !didSendLowVolumeAlert) {
                didSendLowVolumeAlert = true
                val ctx = applicationContext
                val msg = ctx.getString(R.string.overlay_low_volume) + " " + micDiag()
                ctx.sendBroadcast(
                    Intent(ACTION_AUDIO_HEALTH_ALERT)
                        .setPackage(ctx.packageName)
                        .putExtra(EXTRA_HEALTH_ALERT_TYPE, HEALTH_ALERT_LOW_VOLUME)
                        .putExtra(EXTRA_HEALTH_ALERT_MESSAGE, msg)
                )
                // Overlay hint only — a heads-up notification for quiet audio
                // spams the user during normal pauses in conversation.
            }
        } else {
            lowVolumeChunkCount = 0
        }
        return rms
    }

    private fun rmsOfPcm16(buffer: ByteArray, bytesRead: Int): Double {
        if (bytesRead < 2) return 0.0
        var sumSq = 0L
        // Bound at bytesRead - 1: buffer[i + 1] must stay in range on odd sizes.
        for (i in 0 until bytesRead - 1 step 2) {
            val sample = ((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i].toInt() and 0xFF)
            val normalized = sample.toShort().toInt()
            sumSq += normalized * normalized
        }
        return kotlin.math.sqrt(sumSq.toDouble() / (bytesRead / 2))
    }

    private fun cleanupFailedCapture(recorder: AudioRecord) {
        synchronized(captureLock) {
            isCaptureRunning = false
            if (activeRecorder == recorder) {
                activeRecorder = null
            }
            captureThread = null
        }
        recorder.releaseSafely()
        releaseAudioEffects()
        flushAudioProgress()
        restoreAudioMode()
        stopForeground(STOP_FOREGROUND_REMOVE)
        CallSessionRepository.markFailed(
            this,
            activeSessionId,
            "Audio stream stopped because microphone reading failed."
        )
        publishState(CaptureState.Failed)
        stopSelf()
    }

    private fun recordAudioProgress(bytesRead: Int) {
        val shouldFlush = synchronized(progressLock) {
            pendingAudioBytes += bytesRead.toLong()
            pendingAudioChunks += 1
            pendingAudioBytes >= PROGRESS_FLUSH_BYTES
        }
        if (shouldFlush) {
            flushAudioProgress()
        }
    }

    private fun flushAudioProgress() {
        val progress = synchronized(progressLock) {
            if (pendingAudioBytes <= 0L || pendingAudioChunks <= 0) {
                return
            }
            val byteCount = pendingAudioBytes
            val chunkCount = pendingAudioChunks
            pendingAudioBytes = 0L
            pendingAudioChunks = 0
            AudioProgress(byteCount = byteCount, chunkCount = chunkCount)
        }
        CallSessionRepository.recordAudioProgress(
            context = this,
            sessionId = activeSessionId,
            byteCount = progress.byteCount,
            chunkCount = progress.chunkCount
        )
    }

    /**
     * Tries each (rate, source) until one initializes. 16 kHz is preferred
     * (downstream windows assume it), but some devices/HALs lack 16 kHz on
     * every source — 44.1/8 kHz with an honest WAV header still transcribes.
     */
    private fun buildRecorderWithFallback(): AudioRecord? {
        for (rate in SAMPLE_RATE_CHAIN) {
            for (source in AUDIO_SOURCE_CHAIN) {
                val recorder = try {
                    buildRecorder(source, rate)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord probe crashed for source=$source rate=$rate", e)
                    null
                }
                if (recorder != null) {
                    activeAudioSource = source
                    activeSampleRateHz = rate
                    micDiagLabel = sourceLabel(source) + if (rate != 16_000) "@${rate / 1000}k" else ""
                    Log.i(TAG, "AudioRecord initialized with source=$source rate=$rate")
                    return recorder
                }
                Log.w(TAG, "AudioRecord source=$source rate=$rate unavailable, trying next.")
            }
        }
        return null
    }

    private fun buildRecorder(audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate: Int = SAMPLE_RATE_HZ): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return null
        }
        val bufferSize = maxOf(minBufferSize, AUDIO_BUFFER_BYTES)
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        return try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (exception: RuntimeException) {
            Log.e(TAG, "AudioRecord initialization failed (source=$audioSource).", exception)
            null
        }
    }

    // ---- Audio cleaning helpers (recommended) ----
    // AGC only (see field note: AEC/NS would erase the speaker-side voice).
    private fun enableAudioEffects(audioSessionId: Int) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioSessionId)?.also { if (!it.enabled) it.enabled = true }
                Log.i(TAG, "AutomaticGainControl enabled=${gainControl?.enabled}")
            }
        } catch (e: Exception) { Log.w(TAG, "AGC enable failed", e) }
    }

    private fun releaseAudioEffects() {
        try { gainControl?.release() } catch (_: Exception) {}
        gainControl = null
    }

    /**
     * Lightweight software cleaning for 16-bit PCM mono LE @16kHz.
     * - Removes DC offset (high-pass) — fixes hum from speaker routing.
     * - Soft noise gate on near-silence frames (keeps STT from hallucinating).
     * Recommended: runs after hardware AEC/NS, before sending sec-by-sec to Gemini.
     * Cost: O(n) over ~3200 bytes (~1.6k samples) per 100ms, negligible vs WS.
     */
    private fun cleanPcm16Mono(src: ByteArray, bytesRead: Int): ByteArray {
        if (bytesRead < 2) return src.copyOf(bytesRead)
        val samples = bytesRead / 2
        // Compute mean (DC offset) over this chunk
        var sum = 0L
        for (i in 0 until samples) {
            val lo = src[i * 2].toInt() and 0xFF
            val hi = src[i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            sum += s.toShort().toInt()
        }
        val dc = (sum / samples).toInt()
        val out = ByteArray(bytesRead)
        // Alpha ~ 0.995 for DC blocker between chunks would be better; per-chunk mean removal is cheap + stable.
        for (i in 0 until samples) {
            val lo = src[i * 2].toInt() and 0xFF
            val hi = src[i * 2 + 1].toInt()
            var s = (((hi shl 8) or lo).toShort().toInt() - dc)
            // Soft limiter: clamp to 90% to leave headroom after AGC
            if (s > 29500) s = 29500
            if (s < -29500) s = -29500
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun activateSpeakerMode() {
        previousAudioMode = audioManager.mode
        wasSpeakerphoneOn = audioManager.isSpeakerphoneOn
        // During a real cellular call the system owns the voice path (MODE_IN_CALL).
        // Forcing MODE_IN_COMMUNICATION there mutes third-party capture on
        // MIUI/HyperOS (Redmi) — so only turn the speaker on, never touch the mode.
        if (previousAudioMode == AudioManager.MODE_IN_CALL) {
            audioManager.isSpeakerphoneOn = true
            Log.i(TAG, "Cellular call active; speaker forced, telephony audio mode untouched.")
            return
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
                return
            }
        }

        audioManager.isSpeakerphoneOn = true
    }

    private fun restoreAudioMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }

            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = wasSpeakerphoneOn
            audioManager.mode = previousAudioMode
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Audio mode restore failed.", exception)
        }
    }

    private fun startForegroundCapture(phoneNumber: String) {
        val notification = buildNotification(
            title = getString(R.string.capture_notification_title),
            text = getString(R.string.capture_notification_text, displayNumber(phoneNumber))
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CAPTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            return
        }
        startForeground(CAPTURE_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.call_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun publishState(state: CaptureState) {
        publishCaptureState(this, state)
    }

    private fun displayNumber(phoneNumber: String): String =
        phoneNumber.ifBlank { getString(R.string.overlay_title) }

    private fun AudioRecord.releaseSafely() {
        try {
            stop()
        } catch (_: IllegalStateException) {
            // Recorder may already be stopped if initialization failed.
        } finally {
            release()
        }
    }

    enum class CaptureState {
        Listening,
        Stopped,
        Failed
    }

    private data class AudioProgress(
        val byteCount: Long,
        val chunkCount: Int
    )

    companion object {
        const val ACTION_CAPTURE_STATE_CHANGED =
            "com.guardvoice.action.CAPTURE_STATE_CHANGED"
        const val EXTRA_CAPTURE_STATE = "extra_capture_state"
        const val ACTION_VERDICT_CHANGED =
            "com.guardvoice.action.VERDICT_CHANGED"
        const val EXTRA_RISK_LEVEL = "extra_risk_level"
        const val EXTRA_RISK_SCORE = "extra_risk_score"
        const val EXTRA_TRANSCRIPT = "extra_transcript"
        const val EXTRA_TRANSCRIPT_DELTA = "extra_transcript_delta"
        const val EXTRA_REASONS = "extra_reasons"
        const val EXTRA_KEYWORDS = "extra_keywords"
        const val EXTRA_ELAPSED_SEC = "extra_elapsed_sec"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val ACTION_TRANSCRIPT_CHANGED = "com.guardvoice.action.TRANSCRIPT_CHANGED"
        const val ACTION_STREAM_STATUS_CHANGED = "com.guardvoice.action.STREAM_STATUS_CHANGED"
        const val EXTRA_STREAM_STATUS = "extra_stream_status"
        const val ACTION_AUDIO_HEALTH_ALERT =
            "com.guardvoice.action.AUDIO_HEALTH_ALERT"
        const val EXTRA_HEALTH_ALERT_TYPE = "extra_health_alert_type"
        const val EXTRA_HEALTH_ALERT_MESSAGE = "extra_health_alert_message"
        const val HEALTH_ALERT_NO_VOICE = "no_voice"
        const val HEALTH_ALERT_LOW_VOLUME = "low_volume"
        const val HEALTH_ALERT_STT_ERROR = "stt_error"
        // Field diagnostics: current mic source label + last measured RMS level.
        // Appended to audio hints so testers can report them (no logcat needed).
        @Volatile var micDiagLabel: String = "?"
        @Volatile var micDiagRms: Double = 0.0
        // Loudest chunk this call — distinguishes "mic hears nothing, ever"
        // (max=0 through loud speech = OS/hardware mute) from "quiet right now".
        @Volatile var micDiagMaxRms: Double = 0.0
        // Loudest chunk since the last no-voice hint was emitted. Lets the
        // hint say "heard sound but couldn't recognize it" (STT/network side)
        // vs "microphone unavailable" (capture side) instead of one blanket
        // message that misleads in both directions.
        @Volatile var micDiagRecentMaxRms: Double = 0.0

        fun micDiag(): String = "($micDiagLabel lvl=${micDiagRms.toInt()} max=${micDiagMaxRms.toInt()})"

        /** Returns the recent max and resets it — one verdict per hint period. */
        fun consumeRecentMaxRms(): Double {
            val v = micDiagRecentMaxRms
            micDiagRecentMaxRms = 0.0
            return v
        }
        private const val LOW_VOLUME_RMS_THRESHOLD = 30.0
        // 50 x 100ms chunks ≈ 5s of continuous near-silence. 1s (10 chunks)
        // fired during normal conversation pauses and spammed notifications.
        private const val LOW_VOLUME_CHUNK_THRESHOLD = 50
        // Quiet-stream watchdog: RMS < 30 for ~15s straight means the mic hears
        // no usable voice (muted by OS, or hardware AEC residue). Rotate the
        // audio source to try to recover. Capped per call — harmless in a
        // genuinely quiet room, potentially rescuing on hostile devices.
        // (Threshold intentionally equals the low-volume level: faint residue
        // that Deepgram can't transcribe is as useless as zeros.)
        private const val SILENCE_RMS_THRESHOLD = 30.0
        private const val SILENCE_CHUNKS_BEFORE_ROTATE = 150
        private const val MAX_SOURCE_ROTATIONS = 3
        private val AUDIO_SOURCE_CHAIN = intArrayOf(
            // MIC first: raw room audio with no built-in voice processing.
            // VOICE_COMMUNICATION runs hardware AEC/NS that erases speaker bleed;
            // it stays as fallback, not default. UNPROCESSED is rawest but
            // unsupported on many devices (probe skips it automatically).
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.CAMCORDER
        )
        // 16 kHz first (downstream 5s-window math assumes it); 44.1/8 kHz only
        // as last resorts — window timing drifts but audio flows and the WAV
        // header carries the true rate so Deepgram still transcribes.
        private val SAMPLE_RATE_CHAIN = intArrayOf(16_000, 44_100, 8_000)
        // Actual rate of the live recorder; STT uses it for the WAV header.
        @Volatile var activeSampleRateHz: Int = 16_000
        private const val ACTION_START = "com.guardvoice.action.START_CAPTURE"
        private const val ACTION_STOP = "com.guardvoice.action.STOP_CAPTURE"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "guardvoice_call_monitoring"
        private const val CAPTURE_NOTIFICATION_ID = 2002
        private const val NOTIFICATION_REQUEST_CODE = 44
        private const val SAMPLE_RATE_HZ = 16_000
        private const val AUDIO_BUFFER_BYTES = 3_200
        private const val PROGRESS_FLUSH_BYTES = 32_000L
        private const val JOIN_TIMEOUT_MS = 500L

        fun start(context: Context, phoneNumber: String, sessionId: String) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startService for STOP failed (app in background), stopping directly", e)
                try { context.stopService(intent) } catch (_: Exception) {}
            } catch (e: RuntimeException) {
                Log.w(TAG, "stop() failed", e)
            }
        }

        fun publishCaptureState(context: Context, state: CaptureState) {
            context.sendBroadcast(
                Intent(ACTION_CAPTURE_STATE_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_CAPTURE_STATE, state.name)
            )
        }
    }
}
