package com.guardvoice.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        when (incomingPhoneStateActionFor(intent.getStringExtra(TelephonyManager.EXTRA_STATE))) {
            IncomingPhoneStateAction.ShowPopup -> showIncomingCallPopup(context, intent)
            IncomingPhoneStateAction.ClearPopup -> clearCallPopup(context)
            IncomingPhoneStateAction.Ignore -> Unit
        }
    }

    private fun showIncomingCallPopup(context: Context, intent: Intent) {
        try {
            val phoneNumber = intent.getStringExtra(EXTRA_INCOMING_NUMBER).orEmpty()
            IncomingCallOverlayCoordinator.showIncomingCall(context, phoneNumber)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not show popup for incoming phone-state broadcast.", exception)
        }
    }

    private fun clearCallPopup(context: Context) {
        IncomingCallOverlayCoordinator.clear()
        CallOverlayService.stop(context)
    }

    private companion object {
        private const val TAG = "IncomingCallReceiver"
        private const val EXTRA_INCOMING_NUMBER = "incoming_number"
    }
}
