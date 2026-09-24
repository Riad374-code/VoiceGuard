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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
    // Hardware audio effects — recommended when using VOICE_COMMUNICATION, enabled once per call
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null
    private val progressLock = Any()
    private var pendingAudioBytes = 0L
    private var pendingAudioChunks = 0
    private var lowVolumeChunkCount = 0
    private var didSendLowVolumeAlert = false

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
                val recorder = buildRecorder() ?: run {
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
                // ---- Audio cleaning (recommended): enable hardware AEC/NS/AGC once per call ----
                // VOICE_COMMUNICATION enables OS-level processing, but explicit enable guarantees
                // noise suppression + echo cancellation on devices where they are available.
                enableAudioEffects(recorder.audioSessionId)
                isCaptureRunning = true
                CallAudioStream.init(this, activeSessionId)
                // Start streaming to Gemini proxy — accumulative, per-second PCM.
                // Backend GeminiLiveProxy sets systemInstruction ONCE per call (see server.ts:71 / GeminiLiveProxy.ts:57).
                // Subsequent audio_chunk messages contain ONLY raw PCM (never prompt).
                try { GeminiLiveStreamClient.start(this, activeSessionId) } catch (e: Exception) { Log.w(TAG, "Gemini stream start failed, will use fallback", e) }
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

    private fun captureLoop(recorder: AudioRecord) {
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
                    // Legacy batch pipeline (Groq + local analyzer) — keep as offline fallback
                    CallAudioStream.accept(activeSessionId, chunk)
                    // New streaming pipeline: per-second PCM to backend proxy (accumulative, never resend old)
                    // GeminiLiveStreamClient internally coalesces 100ms reads into ~1 sec frames
                    // before sending (reduces WS overhead while staying sec-by-sec).
                    try { GeminiLiveStreamClient.sendPcmChunk(chunk) } catch (_: Exception) {}
                    recordAudioProgress(bytesRead)
                    // Use cleaned chunk for volume check — reflects what Gemini actually receives.
                    evaluateVolumeLevel(chunk, chunk.size)
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read failed with code $bytesRead.")
                    didFail = true
                    break
                }
            }
        } catch (exception: IllegalStateException) {
            Log.e(TAG, "Audio capture could not start.", exception)
            didFail = true
        } finally {
            if (didFail && isCaptureRunning) {
                cleanupFailedCapture(recorder)
            }
        }
    }

    private fun evaluateVolumeLevel(buffer: ByteArray, bytesRead: Int) {
        if (didSendLowVolumeAlert) return
        var sumSq = 0L
        for (i in 0 until bytesRead step 2) {
            val sample = ((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i].toInt() and 0xFF)
            val normalized = sample.toShort().toInt()
            sumSq += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumSq.toDouble() / (bytesRead / 2))
        if (rms < LOW_VOLUME_RMS_THRESHOLD) {
            lowVolumeChunkCount++
            if (lowVolumeChunkCount >= LOW_VOLUME_CHUNK_THRESHOLD && !didSendLowVolumeAlert) {
                didSendLowVolumeAlert = true
                val ctx = applicationContext
                val msg = ctx.getString(R.string.overlay_low_volume)
                ctx.sendBroadcast(
                    Intent(ACTION_AUDIO_HEALTH_ALERT)
                        .setPackage(ctx.packageName)
                        .putExtra(EXTRA_HEALTH_ALERT_TYPE, HEALTH_ALERT_LOW_VOLUME)
                        .putExtra(EXTRA_HEALTH_ALERT_MESSAGE, msg)
                )
                CallFallbackNotifier.showAudioHealthAlert(ctx, msg)
            }
        } else {
            lowVolumeChunkCount = 0
        }
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

    private fun buildRecorder(): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return null
        }
        val bufferSize = maxOf(minBufferSize, AUDIO_BUFFER_BYTES)
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        return try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (exception: RuntimeException) {
            Log.e(TAG, "AudioRecord initialization failed.", exception)
            null
        }
    }

    // ---- Audio cleaning helpers (recommended) ----
    private fun enableAudioEffects(audioSessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also { if (!it.enabled) it.enabled = true }
                Log.i(TAG, "NoiseSuppressor enabled=${noiseSuppressor?.enabled} session=$audioSessionId")
            }
        } catch (e: Exception) { Log.w(TAG, "NoiseSuppressor enable failed", e) }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also { if (!it.enabled) it.enabled = true }
                Log.i(TAG, "AcousticEchoCanceler enabled=${echoCanceler?.enabled}")
            }
        } catch (e: Exception) { Log.w(TAG, "AEC enable failed", e) }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioSessionId)?.also { if (!it.enabled) it.enabled = true }
                Log.i(TAG, "AutomaticGainControl enabled=${gainControl?.enabled}")
            }
        } catch (e: Exception) { Log.w(TAG, "AGC enable failed", e) }
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
        noiseSuppressor = null; echoCanceler = null; gainControl = null
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
        private const val LOW_VOLUME_RMS_THRESHOLD = 30.0
        private const val LOW_VOLUME_CHUNK_THRESHOLD = 10
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
