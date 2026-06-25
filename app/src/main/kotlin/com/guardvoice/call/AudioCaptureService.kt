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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.guardvoice.MainActivity
import com.guardvoice.R
import com.guardvoice.data.CallSessionRepository

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
    private val progressLock = Any()
    private var pendingAudioBytes = 0L
    private var pendingAudioChunks = 0

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
                isCaptureRunning = true
                CallAudioStream.init(this, activeSessionId)
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
        CallAudioStream.reset()
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
                    CallAudioStream.accept(activeSessionId, buffer.copyOf(bytesRead))
                    recordAudioProgress(bytesRead)
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

    private fun cleanupFailedCapture(recorder: AudioRecord) {
        synchronized(captureLock) {
            isCaptureRunning = false
            if (activeRecorder == recorder) {
                activeRecorder = null
            }
            captureThread = null
        }
        recorder.releaseSafely()
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
        const val EXTRA_REASONS = "extra_reasons"
        private const val ACTION_START = "com.guardvoice.action.START_CAPTURE"
        private const val ACTION_STOP = "com.guardvoice.action.STOP_CAPTURE"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val EXTRA_SESSION_ID = "extra_session_id"
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
            context.startService(intent)
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
