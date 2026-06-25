package com.guardvoice.call

import com.guardvoice.data.CallVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamAnalyzerTest {
    @Test
    fun `marks normal conversation as safe`() {
        val result = ScamAnalyzer.analyze("Hi, I am calling to confirm our lunch at noon.")

        assertEquals(CallVerdict.Safe, result.verdict)
        assertEquals(0, result.riskScore)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `marks high pressure credential request as scam`() {
        val result = ScamAnalyzer.analyze(
            "This is urgent. Verify your account and give me the one time password right now."
        )

        assertEquals(CallVerdict.Scam, result.verdict)
        assertTrue(result.riskScore >= 60)
        assertTrue(result.reasons.any { reason -> reason.contains("credentials", ignoreCase = true) })
    }
}
