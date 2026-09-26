package com.guardvoice.call

import android.content.Context
import android.content.Intent
import android.util.Log
import com.guardvoice.BuildConfig
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
    private const val MAX_EMPTY_CONSECUTIVE = 3

    private val lock = Any()
    private val audioBuffer = mutableListOf<ByteArray>()
    private var bufferSize = 0
    private var activeSessionId = ""
    private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    private var consecutiveEmptyCount = 0

    fun init(context: Context, sessionId: String) {
        synchronized(lock) {
            appContext = context.applicationContext
            activeSessionId = sessionId
            audioBuffer.clear()
            bufferSize = 0
        }
    }

    fun accept(sessionId: String, chunk: ByteArray) {
        // When live Gemini streaming is active, transcription+scoring happens server-side sec-by-sec.
        // Skipping the legacy Groq batch path avoids duplicate verdicts and "no voice" spam.
        if (GeminiLiveStreamClient.isStreamingConnected()) return
        // Without a Groq key there's no offline STT — avoid queuing useless PCM that would
        // just emit "no voice" warnings.
        if (BuildConfig.GROQ_API_KEY.trim().isBlank()) return

        val data: ByteArray?
        synchronized(lock) {
            if (sessionId != activeSessionId || appContext == null) return
            audioBuffer.add(chunk)
            bufferSize += chunk.size
            data = if (bufferSize >= BUFFER_THRESHOLD && !isProcessing.get()) {
                isProcessing.set(true)
                extractBuffer()
            } else {
                null
            }
        }
        if (data != null) {
            executor.submit { processAudio(data) }
        }
    }

    fun reset() {
        synchronized(lock) {
            activeSessionId = ""
            audioBuffer.clear()
            bufferSize = 0
            consecutiveEmptyCount = 0
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

    private fun processAudio(pcmData: ByteArray) {
        try {
            val context = synchronized(lock) { appContext } ?: return
            val sid = synchronized(lock) { activeSessionId }
            if (sid.isBlank()) return

            val transcription = GroqWhisperClient.transcribe(pcmData)
            if (transcription.isNullOrBlank()) {
                // Only count/alert when offline STT is actually configured — otherwise "no voice"
                // is just "no key configured" noise.
                if (BuildConfig.GROQ_API_KEY.trim().isNotBlank()) {
                    consecutiveEmptyCount++
                    if (consecutiveEmptyCount >= MAX_EMPTY_CONSECUTIVE) {
                        sendNoVoiceAlert(context)
                        consecutiveEmptyCount = 0
                    }
                }
                return
            }

            consecutiveEmptyCount = 0
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
                    executor.submit { processAudio(data) }
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

    private fun sendNoVoiceAlert(context: Context) {
        val msg = context.getString(R.string.overlay_no_voice)
        context.sendBroadcast(
            Intent(AudioCaptureService.ACTION_AUDIO_HEALTH_ALERT)
                .setPackage(context.packageName)
                .putExtra(AudioCaptureService.EXTRA_HEALTH_ALERT_TYPE, AudioCaptureService.HEALTH_ALERT_NO_VOICE)
                .putExtra(AudioCaptureService.EXTRA_HEALTH_ALERT_MESSAGE, msg)
        )
        CallFallbackNotifier.showAudioHealthAlert(context, msg)
    }
}
