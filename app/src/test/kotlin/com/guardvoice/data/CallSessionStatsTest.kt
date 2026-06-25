package com.guardvoice.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CallSessionStatsTest {
    @Test
    fun `returns zero duration for empty audio`() {
        assertEquals(0, audioDurationSeconds(0L))
        assertEquals("00:00", audioDurationLabel(0L))
    }

    @Test
    fun `formats pcm duration from streamed bytes`() {
        assertEquals(65, audioDurationSeconds(2_080_000L))
        assertEquals("01:05", audioDurationLabel(2_080_000L))
    }

    @Test
    fun `does not return negative duration for bad byte counts`() {
        assertEquals(0, audioDurationSeconds(-1L))
        assertEquals("00:00", audioDurationLabel(-1L))
    }
}
