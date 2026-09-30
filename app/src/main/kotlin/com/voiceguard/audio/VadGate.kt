package com.voiceguard.audio

import kotlin.math.log10
import kotlin.math.sqrt

data class VadDecision(val forward: Boolean, val speechActive: Boolean)

/**
 * Energy + speech-band VAD with 300 ms pre-roll and 500 ms hangover.
 * Pure Kotlin (JVM-testable). Works on 100 ms frames of 16 kHz mono PCM16,
 * but tolerates any frame length.
 *
 * Adaptive noise floor: tracks the minimum frame RMS over ~3 s and sets the
 * trigger threshold to floor + margin, where margin shrinks as [sensitivity]
 * (0..1) grows. Never forwards [AudioHealthAnalyzer.HealthClass.DIGITAL_SILENCE]
 * frames — the orchestrator enforces that; this gate only sees voiced audio.
 */
class VadGate(sensitivity: Float = 0.5f) {
    private var marginDb: Double = 12.0 - 8.0 * sensitivity.coerceIn(0f, 1f)
    private var noiseFloorDb = -60.0
    private var framesSinceFloorUpdate = 0
    private var hangoverMs = 0
    private val preroll = ArrayDeque<ShortArray>()
    private var activeFrames = 0L
    private var totalFrames = 0L

    fun setSensitivity(sensitivity: Float) {
        marginDb = 12.0 - 8.0 * sensitivity.coerceIn(0f, 1f)
    }

    fun reset() {
        noiseFloorDb = -60.0
        framesSinceFloorUpdate = 0
        hangoverMs = 0
        preroll.clear()
        activeFrames = 0L
        totalFrames = 0L
    }

    /** Fraction of processed frames classified as speech (0..1). */
    fun activeFraction(): Float =
        if (totalFrames == 0L) 0f else (activeFrames.toDouble() / totalFrames).toFloat()

    fun process(frame: ShortArray): VadDecision {
        totalFrames++
        val rmsDb = frameRmsDb(frame)
        // Adapt floor on quiet frames only, roughly every 3 s of 100 ms frames.
        if (rmsDb < noiseFloorDb + 3.0) {
            noiseFloorDb = minOf(noiseFloorDb, rmsDb).coerceAtLeast(-70.0)
            framesSinceFloorUpdate = 0
        } else {
            framesSinceFloorUpdate++
            if (framesSinceFloorUpdate > 300) {
                noiseFloorDb = (noiseFloorDb + 1.0).coerceAtMost(-40.0)
                framesSinceFloorUpdate = 0
            }
        }
        val health = AudioHealthAnalyzer.analyze(frame)
        val energetic = rmsDb > noiseFloorDb + marginDb
        // Speech needs energy plus either band concentration or strong level
        // (loud close speech can saturate the band metric).
        val speechy = energetic && (health.speechBandRatio >= 0.2 || rmsDb > -20.0)

        return if (speechy) {
            hangoverMs = HANGOVER_MS
            activeFrames++
            // Drain pre-roll first so onset is not clipped (flagged via forward;
            // the orchestrator re-emits buffered pre-roll bytes it kept).
            preroll.clear()
            VadDecision(forward = true, speechActive = true)
        } else {
            preroll.addLast(frame.copyOf())
            if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
            if (hangoverMs > 0) {
                hangoverMs -= FRAME_MS
                activeFrames++
                VadDecision(forward = true, speechActive = false)
            } else {
                VadDecision(forward = false, speechActive = false)
            }
        }
    }

    private fun frameRmsDb(frame: ShortArray): Double {
        if (frame.isEmpty()) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (s in frame) {
            val x = s / 32768.0
            sum += x * x
        }
        val rms = sqrt(sum / frame.size)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }

    companion object {
        private const val FRAME_MS = 100
        private const val PREROLL_FRAMES = 3 // 300 ms
        private const val HANGOVER_MS = 500
    }
}

