package com.voiceguard.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VadGateTest {
    private fun tone(amp: Double = 0.5): ShortArray {
        // 100 ms frame of 440 Hz.
        return ShortArray(1600) { i -> (sin(2 * PI * 440.0 * i / 16000.0) * 32767 * amp).toInt().toShort() }
    }

    @Test fun silence_doesNotForward() {
        val vad = VadGate()
        repeat(10) {
            val d = vad.process(ShortArray(1600))
            assertFalse(d.forward)
            assertFalse(d.speechActive)
        }
    }

    @Test fun speech_forwardsImmediately() {
        val vad = VadGate()
        repeat(5) { vad.process(ShortArray(1600)) } // settle floor
        val d = vad.process(tone())
        assertTrue(d.forward)
        assertTrue(d.speechActive)
    }

    @Test fun hangover_keepsForwardingAfterSpeechEnds() {
        val vad = VadGate()
        repeat(5) { vad.process(ShortArray(1600)) }
        vad.process(tone())
        // 500 ms hangover = 5 frames of silence still forwarded.
        repeat(5) {
            val d = vad.process(ShortArray(1600))
            assertTrue("hangover frame $it should forward", d.forward)
        }
        val d = vad.process(ShortArray(1600))
        assertFalse("after hangover should stop", d.forward)
    }
}
