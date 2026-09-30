package com.voiceguard.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptAggregatorTest {
    private fun final(text: String) = TranscriptEvent(text, true, 0.9f, 1L)

    @Test fun belowMinWords_noRequest() {
        val agg = TranscriptAggregator(minWords = 4)
        assertNull(agg.onEvent(final("hello there friend")))
        assertEquals(3, agg.snapshot().wordCount)
    }

    @Test fun finalReachingThreshold_requestsScore() {
        val agg = TranscriptAggregator(minWords = 4)
        val req = agg.onEvent(final("please read the code aloud now"))
        assertNotNull(req)
        assertEquals(6, req!!.newWords)
    }

    @Test fun duplicateFinals_deduped() {
        val agg = TranscriptAggregator(minWords = 4)
        agg.onEvent(final("please read the code aloud now"))
        // Same final repeated (Deepgram resends) must not double-count.
        val before = agg.snapshot().wordCount
        agg.onEvent(final("please read the code aloud now"))
        assertEquals(before, agg.snapshot().wordCount)
    }

    @Test fun interim_updatesSnapshotButScoresNothing() {
        val agg = TranscriptAggregator(minWords = 4)
        assertNull(agg.onEvent(TranscriptEvent("hello", false, 0.5f, 1L)))
        assertEquals("hello", agg.snapshot().interim)
        assertEquals(1, agg.snapshot().wordCount)
    }
}
