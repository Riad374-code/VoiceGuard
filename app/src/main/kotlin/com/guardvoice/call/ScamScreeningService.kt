package com.guardvoice.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.guardvoice.data.ContactsHelper

class ScamScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        try {
            reportUnsavedIncomingCall(callDetails)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Call screening decision failed; allowing call.", exception)
        } finally {
            respondToCall(callDetails, allowedCallResponse())
        }
    }

    private fun reportUnsavedIncomingCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart
        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val isSavedContact = phoneNumber?.let(ContactsHelper(this)::isSavedNumber) ?: false

        if (shouldReportUnsavedIncomingCall(isIncoming, phoneNumber, isSavedContact)) {
            sendBroadcast(
                Intent(ACTION_UNSAVED_CALL_DETECTED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            )
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
        const val ACTION_UNSAVED_CALL_DETECTED =
            "com.guardvoice.action.UNSAVED_CALL_DETECTED"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
    }
}
