package com.voiceguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroqResponseParserTest {
    private fun envelope(content: String) =
        """{"id":"x","choices":[{"message":{"role":"assistant","content":$content}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""

    @Test fun cleanJson_parsed() {
        val inner = """{\"risk\": 85, \"verdict\": \"SCAM\", \"reasons\": [\"OTP request\"], \"tactics\": [\"urgency\"], \"advice\": \"Hang up.\"}"""
        val v = GroqResponseParser.parseResponse(envelope("\"$inner\""))
        assertNotNull(v)
        assertEquals(85, v!!.risk)
        assertEquals(Verdict.SCAM, v.verdict)
        assertEquals(listOf("OTP request"), v.reasons)
    }

    @Test fun codeFences_stripped() {
        val raw = "```json\n{\"risk\": 5, \"verdict\": \"SAFE\", \"reasons\": [], \"tactics\": [], \"advice\": \"ok\"}\n```"
        val v = GroqResponseParser.parseResponse(raw)
        assertNotNull(v)
        assertEquals(Verdict.SAFE, v!!.verdict)
        assertEquals(5, v.risk)
    }

    @Test fun riskOnly_infersVerdict() {
        val v = GroqResponseParser.parseResponse("""{"risk": 42}""")
        assertNotNull(v)
        assertEquals(Verdict.SUSPICIOUS, v!!.verdict)
    }

    @Test fun garbage_returnsNull() {
        assertNull(GroqResponseParser.parseResponse("not json at all"))
        assertNull(GroqResponseParser.parseResponse(""))
    }

    @Test fun riskClamped() {
        val v = GroqResponseParser.parseResponse("""{"risk": 500, "verdict": "SCAM"}""")
        assertNotNull(v)
        assertEquals(100, v!!.risk)
    }
}
