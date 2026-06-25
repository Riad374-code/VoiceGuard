package com.guardvoice.call

import android.content.Context
import android.content.Intent
import android.util.Log
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.data.CallVerdict
import com.guardvoice.db.GuardVoiceRepository
import com.guardvoice.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object CallAudioStream {
    private const val BUFFER_THRESHOLD = 96_000
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
                consecutiveEmptyCount++
                if (consecutiveEmptyCount >= MAX_EMPTY_CONSECUTIVE) {
                    sendNoVoiceAlert(context)
                    consecutiveEmptyCount = 0
                }
                return
            }

            consecutiveEmptyCount = 0

            val result = ScamAnalyzer.analyze(transcription)
            val summary = when (result.verdict) {
                CallVerdict.Safe -> "Conversation appears safe."
                CallVerdict.Suspicious -> "Conversation has suspicious elements."
                CallVerdict.Scam -> "Scam patterns detected!"
                CallVerdict.Pending -> "Analyzing conversation..."
            }.let { base ->
                if (result.reasons.isNotEmpty()) {
                    "$base ${result.reasons.joinToString(". ")}"
                } else base
            }

            CallSessionRepository.saveAnalysis(
                context = context,
                sessionId = sid,
                verdict = result.verdict,
                riskScore = result.riskScore,
                transcriptPreview = transcription,
                summary = summary,
                reasons = result.reasons
            )

            try {
                GuardVoiceRepository.getInstance(context).insertDetection(
                    sessionId = sid,
                    transcript = transcription,
                    verdict = result.verdict,
                    riskScore = result.riskScore,
                    reasons = result.reasons
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist detection to SQLite", e)
            }

            publishVerdict(
                context = context,
                verdict = result.verdict.name,
                riskScore = result.riskScore,
                transcript = transcription,
                reasons = result.reasons
            )
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
