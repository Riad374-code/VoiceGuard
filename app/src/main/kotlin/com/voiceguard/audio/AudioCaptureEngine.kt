package com.voiceguard.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mode A microphone capture: 16 kHz mono PCM16, 100 ms chunks on Dispatchers.IO.
 *
 * Source priority: VOICE_RECOGNITION -> MIC -> UNPROCESSED (API 29+), trying the
 * next if init fails. VOICE_COMMUNICATION is never used: its AEC/NS chain can
 * erase the second phone's speaker audio. AEC and NS are explicitly disabled;
 * AGC is enabled when available. No audio focus is requested (record only).
 */
class AudioCaptureEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var loopJob: Job? = null
    private var audioManager: AudioManager? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    private val _chunks = MutableSharedFlow<PcmChunk>(extraBufferCapacity = 64)
    val chunks: SharedFlow<PcmChunk> = _chunks.asSharedFlow()

    private val _silencedByOs = MutableStateFlow(false)
    val silencedByOs: StateFlow<Boolean> = _silencedByOs.asStateFlow()

    private val _actualSource = MutableStateFlow("none")
    val actualSource: StateFlow<String> = _actualSource.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isRunning: Boolean get() = loopJob?.isActive == true

    /**
     * Starts capture. Returns the source name used, or null when capture could
     * not start (permission missing or every source failed).
     */
    suspend fun start(): String? {
        if (isRunning) return _actualSource.value
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _error.value = "RECORD_AUDIO permission not granted"
            return null
        }
        registerRecordingCallback()
        val tried = mutableListOf<String>()
        for ((source, name) in candidateSources()) {
            tried.add(name)
            val rec = tryCreate(source)
            if (rec != null) {
                record = rec
                _actualSource.value = name
                tuneEffects(rec.audioSessionId)
                Timber.tag(TAG).i("Capture started: source=%s rate=%d state=%s", name, SAMPLE_RATE, stateName(rec.state))
                startLoop(rec)
                return name
            }
        }
        _error.value = "No microphone source could start (tried ${tried.joinToString()})"
        unregisterRecordingCallback()
        return null
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        unregisterRecordingCallback()
        _silencedByOs.value = false
        Timber.tag(TAG).i("Capture stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun candidateSources(): List<Pair<Int, String>> {
        val list = mutableListOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list.add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
        }
        return list
    }

    private fun tryCreate(source: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return null
            val bufSize = maxOf(minBuf * 2, CHUNK_BYTES)
            val rec = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                try { rec.release() } catch (_: Exception) {}
                return null
            }
            try {
                rec.startRecording()
            } catch (_: Exception) {
                try { rec.release() } catch (_: Exception) {}
                return null
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
                return null
            }
            rec
        } catch (_: Exception) {
            null
        }
    }

    /** Disable AEC/NS (they eat speaker audio), enable AGC when present. */
    private fun tuneEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let {
                    try { if (it.enabled) it.enabled = false } catch (_: Exception) {}
                    try { it.release() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    try { if (it.enabled) it.enabled = false } catch (_: Exception) {}
                    try { it.release() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let {
                    try { if (!it.enabled) it.enabled = true } catch (_: Exception) {}
                    try { it.release() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startLoop(rec: AudioRecord) {
        loopJob = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            var restarts = 0
            while (isActive) {
                val read = try {
                    rec.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    AudioRecord.ERROR
                }
                when {
                    read > 0 -> {
                        restarts = 0
                        _chunks.emit(PcmChunk(buf.copyOf(read), System.currentTimeMillis()))
                    }
                    read == 0 -> kotlinx.coroutines.delay(10)
                    else -> {
                        // ERROR / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                        restarts++
                        Timber.tag(TAG).w("AudioRecord read error=%d attempt=%d", read, restarts)
                        if (restarts > 3) {
                            _error.value = "Microphone read failed (code $read)"
                            break
                        }
                        kotlinx.coroutines.delay(250L * restarts)
                    }
                }
            }
        }
    }

    private fun registerRecordingCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager = am
            val fw = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<android.media.AudioRecordingConfiguration>) {
                    var silenced = false
                    for (c in configs) {
                        try {
                            if (c.isClientSilenced) silenced = true
                        } catch (_: Exception) {
                        }
                    }
                    _silencedByOs.value = silenced
                    if (silenced) Timber.tag(TAG).w("OS reports client silenced")
                }
            }
            recordingCallback = fw
            am.registerAudioRecordingCallback(fw, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "RecordingCallback unavailable")
        }
    }

    private fun unregisterRecordingCallback() {
        try {
            recordingCallback?.let { audioManager?.unregisterAudioRecordingCallback(it) }
        } catch (_: Exception) {
        }
        recordingCallback = null
        audioManager = null
    }

    private fun stateName(state: Int): String = if (state == AudioRecord.STATE_INITIALIZED) "init" else "uninit"

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
        /** 100 ms @ 16 kHz mono 16-bit. */
        const val CHUNK_BYTES = 3200
    }
}

