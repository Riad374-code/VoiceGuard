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
    fun `ignores off hook and missing states`() {
        assertEquals(
            IncomingPhoneStateAction.Ignore,
            incomingPhoneStateActionFor("OFFHOOK")
        )
        assertEquals(
            IncomingPhoneStateAction.Ignore,
            incomingPhoneStateActionFor(null)
        )
    }
}
