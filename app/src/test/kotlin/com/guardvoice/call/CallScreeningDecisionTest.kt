package com.guardvoice.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreeningDecisionTest {
    @Test
    fun `reports an incoming number`() {
        assertTrue(
            shouldReportIncomingCall(
                isIncoming = true,
                phoneNumber = "+15551234567"
            )
        )
    }

    @Test
    fun `ignores outgoing and missing numbers`() {
        assertFalse(
            shouldReportIncomingCall(
                isIncoming = false,
                phoneNumber = "+15551234567"
            )
        )
        assertFalse(
            shouldReportIncomingCall(
                isIncoming = true,
                phoneNumber = null
            )
        )
    }
}
