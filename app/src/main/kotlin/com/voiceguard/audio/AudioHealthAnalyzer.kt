package com.voiceguard.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

enum class HealthClass {
    OK_SPEECH_LIKELY,
    QUIET_ROOM,
    DIGITAL_SILENCE,
    NOISE_ONLY,
    CLIPPING
}

data class AudioHealth(
    val maxAbs: Int,
    val rmsDb: Double,
    val nonZeroRatio: Double,
    val zeroCrossingRate: Double,
    val speechBandRatio: Double,
    val classification: HealthClass,
    val silencedByOs: Boolean
)

/**
 * Pure-Kotlin 1-second-window audio diagnostics (JVM-testable, no Android APIs).
 *
 * Speech-band heuristic: one-pole low-pass at ~3.4 kHz minus one-pole low-pass
 * at ~300 Hz approximates 300–3400 Hz band energy; the ratio of band energy to
 * total energy separates speech (harmonic, band-concentrated) from broadband
 * noise (hiss, wind, handling).
 */
object AudioHealthAnalyzer {
    private const val SAMPLE_RATE = 16000.0
    // One-pole smoothing coefficients for the two cutoff frequencies.
    private val aHigh = 1.0 - exp(-2.0 * Math.PI * 3400.0 / SAMPLE_RATE)
    private val aLow = 1.0 - exp(-2.0 * Math.PI * 300.0 / SAMPLE_RATE)

    fun analyze(samples: ShortArray, silencedByOs: Boolean = false): AudioHealth {
        if (samples.isEmpty()) {
            return AudioHealth(0, Double.NEGATIVE_INFINITY, 0.0, 0.0, 0.0, HealthClass.DIGITAL_SILENCE, silencedByOs)
        }
        var maxAbs = 0
        var nonZero = 0
        var clipped = 0
        var crossings = 0
        var sumSq = 0.0
        var bandSq = 0.0
        var lpHigh1 = 0.0
        var lpHigh2 = 0.0
        var lpLow = 0.0
        var prev = 0
        for (i in samples.indices) {
            val s = samples[i].toInt()
            val a = abs(s)
            if (a > maxAbs) maxAbs = a
            if (s != 0) nonZero++
            if (a >= 32767) clipped++
            if (i > 0 && (s >= 0) != (prev >= 0)) crossings++
            prev = s
            val x = s / 32768.0
            sumSq += x * x
            // Two cascaded one-pole stages (~12 dB/oct) so tones above the
            // 3.4 kHz cutoff are firmly rejected from the speech band.
            lpHigh1 += aHigh * (x - lpHigh1)
            lpHigh2 += aHigh * (lpHigh1 - lpHigh2)
            lpLow += aLow * (x - lpLow)
            val band = lpHigh2 - lpLow
            bandSq += band * band
        }
        val n = samples.size.toDouble()
        val rms = kotlin.math.sqrt(sumSq / n)
        val rmsDb = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
        val nonZeroRatio = nonZero / n
        val zcr = crossings / n
        val speechBandRatio = if (sumSq <= 0.0) 0.0 else (bandSq / sumSq).coerceIn(0.0, 1.0)

        val classification = when {
            maxAbs == 0 || nonZeroRatio < 0.001 -> HealthClass.DIGITAL_SILENCE
            silencedByOs && rmsDb < -50.0 -> HealthClass.DIGITAL_SILENCE
            clipped / n > 0.01 -> HealthClass.CLIPPING
            rmsDb > -30.0 && speechBandRatio < 0.25 -> HealthClass.NOISE_ONLY
            rmsDb > -45.0 && speechBandRatio >= 0.25 -> HealthClass.OK_SPEECH_LIKELY
            else -> HealthClass.QUIET_ROOM
        }
        return AudioHealth(maxAbs, rmsDb, nonZeroRatio, zcr, speechBandRatio, classification, silencedByOs)
    }

    /** Frame RMS in dBFS for level meters; -inf when silent. */
    fun frameRmsDb(bytes: ByteArray): Double {
        if (bytes.size < 2) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)) / 32768.0
            sum += s * s
            count++
            i += 2
        }
        if (count == 0) return Double.NEGATIVE_INFINITY
        val rms = kotlin.math.sqrt(sum / count)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }

    fun bytesToSamples(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        var i = 0
        var j = 0
        while (i + 1 < bytes.size) {
            out[j++] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
            i += 2
        }
        return out
    }

    @Suppress("unused")
    fun dbToLevel(rmsDb: Double): Float = when {
        rmsDb.isInfinite() || rmsDb.isNaN() -> 0f
        else -> ((rmsDb + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }
}

