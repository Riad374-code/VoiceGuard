package com.guardvoice.call

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingPhoneStateDecisionTest {
    @Test
    fun `shows popup when phone starts ringing`() {
        assertEquals(
            IncomingPhoneStateAction.ShowPopup,
            incomingPhoneStateActionFor(PHONE_STATE_RINGING)
        )
    }

    @Test
    fun `clears popup when call becomes idle`() {
        assertEquals(
            IncomingPhoneStateAction.ClearPopup,
            incomingPhoneStateActionFor(PHONE_STATE_IDLE)
        )
    }

    @Test
    fun `shows popup on off hook when ringing was missed`() {
        // OFFHOOK = answered/active call with no tracked RINGING (dead process,
        // fast answer, dual-SIM). The receiver narrows this to incoming numbers.
        assertEquals(
            IncomingPhoneStateAction.ShowPopupIfNoActiveCall,
            incomingPhoneStateActionFor("OFFHOOK")
        )
    }

    @Test
    fun `ignores missing states`() {
        assertEquals(
            IncomingPhoneStateAction.Ignore,
            incomingPhoneStateActionFor(null)
        )
    }
}
