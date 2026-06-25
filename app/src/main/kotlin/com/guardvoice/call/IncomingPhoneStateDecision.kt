package com.guardvoice.call

internal enum class IncomingPhoneStateAction {
    ShowPopup,
    ClearPopup,
    Ignore
}

internal fun incomingPhoneStateActionFor(state: String?): IncomingPhoneStateAction =
    when (state) {
        PHONE_STATE_RINGING -> IncomingPhoneStateAction.ShowPopup
        PHONE_STATE_IDLE -> IncomingPhoneStateAction.ClearPopup
        else -> IncomingPhoneStateAction.Ignore
    }

internal const val PHONE_STATE_RINGING = "RINGING"
internal const val PHONE_STATE_IDLE = "IDLE"
