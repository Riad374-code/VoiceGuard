package com.guardvoice.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreeningDecisionTest {
    @Test
    fun `reports incoming calls`() {
        assertTrue(
            shouldReportIncomingCall(isIncoming = true)
        )
    }

    @Test
    fun `ignores outgoing calls`() {
        assertFalse(
            shouldReportIncomingCall(isIncoming = false)
        )
    }
}
