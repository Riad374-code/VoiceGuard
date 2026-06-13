package com.guardvoice.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreeningDecisionTest {
    @Test
    fun `reports an incoming unsaved number`() {
        assertTrue(
            shouldReportUnsavedIncomingCall(
                isIncoming = true,
                phoneNumber = "+15551234567",
                isSavedContact = false
            )
        )
    }

    @Test
    fun `ignores saved outgoing and missing numbers`() {
        assertFalse(
            shouldReportUnsavedIncomingCall(
                isIncoming = true,
                phoneNumber = "+15551234567",
                isSavedContact = true
            )
        )
        assertFalse(
            shouldReportUnsavedIncomingCall(
                isIncoming = false,
                phoneNumber = "+15551234567",
                isSavedContact = false
            )
        )
        assertFalse(
            shouldReportUnsavedIncomingCall(
                isIncoming = true,
                phoneNumber = null,
                isSavedContact = false
            )
        )
    }
}
