package com.voiceguard.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramMessageParserTest {
    private val finalMsg = """
        {"type":"Results","channel_index":[0,1],
         "channel":{"alternatives":[{"transcript":"hello world","confidence":0.98}]},
         "is_final":true,"speech_final":true,
         "metadata":{"request_id":"req-123"}}
    """.trimIndent()

    @Test fun finalResult_parsed() {
        val p = DeepgramMessageParser.parse(finalMsg)
        assertNotNull(p)
        assertEquals("hello world", p!!.text)
        assertTrue(p.isFinal)
        assertTrue(p.speechFinal)
        assertEquals("req-123", p.requestId)
    }

    @Test fun emptyResults_countedNotCrashed() {
        val empty = """{"type":"Results","channel":{"alternatives":[{"transcript":"","confidence":0.0}]},"is_final":false,"metadata":{"request_id":"r"}}"""
        val p = DeepgramMessageParser.parse(empty)
        assertNotNull(p)
        assertEquals("", p!!.text)
        assertFalse(p.isFinal)
    }

    @Test fun utteranceEnd_surfaced() {
        val u = """{"type":"UtteranceEnd","channel":[],"last_word_end":1.2}"""
        val p = DeepgramMessageParser.parse(u)
        assertNotNull(p)
        assertTrue(p!!.utteranceEnd)
    }

    @Test fun metadata_ignored() {
        assertEquals(null, DeepgramMessageParser.parse("""{"type":"Metadata","transaction_key":"x","request_id":"r"}""")?.text?.takeIf { it.isNotEmpty() })
    }
}
