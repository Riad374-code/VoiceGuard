package com.voiceguard.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioHealthAnalyzerTest {
    private fun tone(freqHz: Double, seconds: Double, amp: Double = 0.5): ShortArray {
        val n = (16000 * seconds).toInt()
        return ShortArray(n) { i -> (sin(2 * PI * freqHz * i / 16000.0) * 32767 * amp).toInt().toShort() }
    }

    @Test fun zeros_areDigitalSilence() {
        val h = AudioHealthAnalyzer.analyze(ShortArray(16000))
        assertEquals(HealthClass.DIGITAL_SILENCE, h.classification)
        assertEquals(0, h.maxAbs)
    }

    @Test fun speechTone_isSpeechLikely() {
        val h = AudioHealthAnalyzer.analyze(tone(440.0, 1.0))
        assertEquals(HealthClass.OK_SPEECH_LIKELY, h.classification)
    }

    @Test fun highNoise_isNoiseOnly() {
        // 6 kHz tone above the speech band at high level.
        val h = AudioHealthAnalyzer.analyze(tone(6000.0, 1.0, 0.9))
        assertEquals(HealthClass.NOISE_ONLY, h.classification)
    }

    @Test fun fullScale_isClipping() {
        val h = AudioHealthAnalyzer.analyze(ShortArray(16000) { 32767 })
        assertEquals(HealthClass.CLIPPING, h.classification)
    }

    @Test fun faintTone_isQuietRoom() {
        val h = AudioHealthAnalyzer.analyze(tone(440.0, 1.0, 0.001))
        assertEquals(HealthClass.QUIET_ROOM, h.classification)
    }
}
