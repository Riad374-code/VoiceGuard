package com.guardvoice.call

import android.content.Context
import android.content.Intent
import android.util.Log
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.data.CallVerdict
import com.guardvoice.db.GuardVoiceRepository
import com.guardvoice.R
import com.guardvoice.stream.GeminiLiveStreamClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object CallAudioStream {
    // 5-sec window at 16kHz PCM16 mono = 32000 *5 = 160_000 bytes. Matches online WSS windowing.
    // When streaming is offline we batch to this size so local STT + scoring aligns with 5s chunks.
    // Only 1-sec overlap is handled client-side in GeminiLiveStreamClient; this batch is non-overlapping offline fallback.
    private const val BUFFER_THRESHOLD = 160_000
    private const val TAG = "CallAudioStream"
    // ~6 x 5-sec windows (≈30s of empty transcripts) before hinting. Short
    // silences at call start are normal — 3 windows fired false alarms while
    // the other side was still greeting.
    private const val MAX_EMPTY_CONSECUTIVE = 6
    // Hard cap (~30s) so a stalled network can't queue unbounded PCM while a window
    // is being transcribed+scored on the single executor. Oldest audio is dropped first.
    private const val MAX_BUFFERED_BYTES = 960_000
    // Recent-max RMS at/above this in a no-voice window means the mic demonstrably
    // delivered audible sound — well above the 30.0 silence floor, below the
    // thousands of loud speech. Below it: capture-side silence/mute.
    private const val HEARD_SOUND_RMS = 300.0

    private val lock = Any()
    private val audioBuffer = mutableListOf<ByteArray>()
    private var bufferSize = 0
    private var activeSessionId = ""
    private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    private var consecutiveEmptyCount = 0
    private var didSendSttErrorHint = false

    fun init(context: Context, sessionId: String) {
        synchronized(lock) {
            appContext = context.applicationContext
            activeSessionId = sessionId
            audioBuffer.clear()
            bufferSize = 0
            consecutiveEmptyCount = 0
            didSendSttErrorHint = false
            // A stuck flag from a previous call would drop every window forever.
            isProcessing.set(false)
        }
    }

    fun accept(sessionId: String, chunk: ByteArray) {
        // When live backend streaming is active, transcription+scoring happens server-side.
        // Skipping the on-device Deepgram batch path avoids duplicate verdicts and "no voice" spam.
        if (GeminiLiveStreamClient.isStreamingConnected()) return
        // Without a Deepgram key there's no on-device STT — avoid queuing useless PCM that would
        // just emit "no voice" warnings.
        if (!DeepgramSttClient.isConfigured()) return

        val data: ByteArray?
        val dispatchSid: String
        synchronized(lock) {
            if (sessionId != activeSessionId || appContext == null) return
            audioBuffer.add(chunk)
            bufferSize += chunk.size
            // Bound memory while a window is stuck in network I/O: drop oldest first.
            while (bufferSize > MAX_BUFFERED_BYTES && audioBuffer.isNotEmpty()) {
                val dropped = audioBuffer.removeAt(0)
                bufferSize -= dropped.size
            }
            data = if (bufferSize >= BUFFER_THRESHOLD && !isProcessing.get()) {
                isProcessing.set(true)
                extractBuffer()
            } else {
                null
            }
            // Snapshot the session at dispatch time: by the time the single
            // executor runs this window, stop()->init(new call) may have landed.
            // Attributing the old call's audio to the new session corrupts history.
            dispatchSid = activeSessionId
        }
        if (data != null) {
            submitAudio(data, dispatchSid)
        }
    }

    private fun submitAudio(data: ByteArray, sid: String) {
        try {
            executor.submit { processAudio(data, sid) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Executor saturated/shut down mid-teardown: drop this window and
            // release the gate instead of killing the mic loop or wedging it.
            Log.w(TAG, "STT executor saturated, dropping window", e)
            isProcessing.set(false)
        }
    }

    fun reset() {
        synchronized(lock) {
            activeSessionId = ""
            audioBuffer.clear()
            bufferSize = 0
            consecutiveEmptyCount = 0
            isProcessing.set(false)
        }
    }

    private fun extractBuffer(): ByteArray {
        val size = audioBuffer.sumOf { it.size }
        val data = ByteArray(size)
        var offset = 0
        for (chunk in audioBuffer) {
            System.arraycopy(chunk, 0, data, offset, chunk.size)
            offset += chunk.size
        }
        audioBuffer.clear()
        bufferSize = 0
        return data
    }

    private fun processAudio(pcmData: ByteArray, dispatchSid: String) {
        try {
            val context = synchronized(lock) { appContext } ?: return
            if (dispatchSid.isBlank()) return

            val transcription = try {
                DeepgramSttClient.transcribe(pcmData, AudioCaptureService.activeSampleRateHz)
            } catch (e: SecurityException) {
                // Bad API key — never "silence". Tell the user once per call.
                Log.w(TAG, "Deepgram auth failed; check API key.", e)
                sendSttErrorHint(context)
                return
            } catch (e: java.io.IOException) {
                // Offline / Deepgram down — never "silence". Tell the user once.
                Log.w(TAG, "Deepgram unreachable; mic may be fine.", e)
                sendSttErrorHint(context)
                return
            }
            if (transcription.isNullOrBlank()) {
                // Only count/alert when on-device STT is actually configured — otherwise "no voice"
                // is just "no key configured" noise.
                if (DeepgramSttClient.isConfigured()) {
                    consecutiveEmptyCount++
                    if (consecutiveEmptyCount >= MAX_EMPTY_CONSECUTIVE) {
                        sendNoVoiceAlert(context)
                        consecutiveEmptyCount = 0
                    }
                }
                return
            }

            consecutiveEmptyCount = 0
            // Session rotated while this window was in network I/O (call ended /
            // new call started): drop instead of polluting the new session.
            val currentSid = synchronized(lock) { activeSessionId }
            if (currentSid != dispatchSid) {
                Log.i(TAG, "Dropping window from finished session $dispatchSid")
                return
            }
            // Offline 5s scoring is now handled centrally by GeminiLiveStreamClient's fallback engine
            // (cumulative active score + per-5s granular history). Keeps 5-by-5 spec and prevents duplicate
            // verdicts. We just feed the transcript there and let it persist/broadcast.
            // Audio bytes are NOT saved to disk — only transcript + scores are persisted.
            try { GeminiLiveStreamClient.feedFallbackTranscript(transcription) } catch (_: Exception) {}
            // NOTE: no direct ScamAnalyzer.save here — fallback engine is single source of truth for offline
            // This ensures active score monotonicity (per-5s chunks increase/decrease cumulative) and history stores each window.
        } catch (e: Exception) {
            Log.e(TAG, "Audio processing failed", e)
        } finally {
            synchronized(lock) {
                if (bufferSize >= BUFFER_THRESHOLD && activeSessionId.isNotBlank()) {
                    val data = extractBuffer()
                    val sid = activeSessionId
                    submitAudio(data, sid)
                } else {
                    isProcessing.set(false)
                }
            }
        }
    }

    private fun publishVerdict(
        context: Context,
        verdict: String,
        riskScore: Int,
        transcript: String,
        reasons: List<String>
    ) {
        context.sendBroadcast(
            Intent(AudioCaptureService.ACTION_VERDICT_CHANGED)
                .setPackage(context.packageName)
                .putExtra(AudioCaptureService.EXTRA_RISK_LEVEL, verdict)
                .putExtra(AudioCaptureService.EXTRA_RISK_SCORE, riskScore)
                .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT, transcript)
                .putExtra(AudioCaptureService.EXTRA_REASONS, reasons.toTypedArray())
        )
    }

    private fun sendSttErrorHint(context: Context) {
        synchronized(lock) {
            if (didSendSttErrorHint) return
            didSendSttErrorHint = true
        }
        // Reset the silence counter: error windows carry no information about
        // whether the mic hears voice, so they must not feed the no-voice alert.
        consecutiveEmptyCount = 0
        val msg = context.getString(R.string.overlay_stt_error)
        context.sendBroadcast(
            Intent(AudioCaptureService.ACTION_AUDIO_HEALTH_ALERT)
                .setPackage(context.packageName)
                .putExtra(AudioCaptureService.EXTRA_HEALTH_ALERT_TYPE, AudioCaptureService.HEALTH_ALERT_STT_ERROR)
                .putExtra(AudioCaptureService.EXTRA_HEALTH_ALERT_MESSAGE, msg)
        )
    }

    private fun sendNoVoiceAlert(context: Context) {
        // Loud audio arrived in this window but Deepgram found no speech:
        // the mic works, recognition failed (mumbling, non-speech noise, or
        // network/language side). Say so — "microphone unavailable" would be
        // a lie the user can disprove by shouting.
        val recentMax = AudioCaptureService.consumeRecentMaxRms()
        val baseRes = if (recentMax >= HEARD_SOUND_RMS) {
            R.string.overlay_heard_unrecognized
        } else {
            R.string.overlay_no_voice
        }
        val msg = context.getString(baseRes) + " " + AudioCaptureService.micDiag()
        context.sendBroadcast(
            Intent(AudioCaptureService.ACTION_AUDIO_HEALTH_ALERT)
                .setPackage(context.packageName)
                .putExtra(AudioCaptureService.EXTRA_HEALTH_ALERT_TYPE, AudioCaptureService.HEALTH_ALERT_NO_VOICE)
                .putExtra(AudioCaptureService.EXTRA_HEALTH_ALERT_MESSAGE, msg)
        )
        // No system notification here on purpose: the overlay hint is enough.
        // Posting a heads-up notification for "silence so far" spams the user
        // during normal call greetings.
    }
}
