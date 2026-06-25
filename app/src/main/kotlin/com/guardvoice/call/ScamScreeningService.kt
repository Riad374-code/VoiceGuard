package com.guardvoice.call

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.guardvoice.data.CallSessionRepository

class ScamScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        try {
            showIncomingCallOverlay(callDetails)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Call screening decision failed; allowing call.", exception)
        } finally {
            respondToCall(callDetails, allowedCallResponse())
        }
    }

    private fun showIncomingCallOverlay(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart.orEmpty()
        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        if (shouldReportIncomingCall(isIncoming)) {
            val sessionId = CallSessionRepository.recordDetected(this, phoneNumber)
            CallOverlayService.show(this, phoneNumber, sessionId)
        }
    }

    private fun allowedCallResponse(): CallResponse =
        CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

    companion object {
        private const val TAG = "ScamScreeningService"
    }
}
