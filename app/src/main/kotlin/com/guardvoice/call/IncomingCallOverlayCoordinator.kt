package com.guardvoice.call

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.guardvoice.data.CallSessionRepository

internal object IncomingCallOverlayCoordinator {
    private val lock = Any()
    private var activeCall: ActiveIncomingCall? = null

    fun showIncomingCall(
        context: Context,
        phoneNumber: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val appContext = context.applicationContext
        val normalizedPhoneNumber = phoneNumber.trim()
        val incomingCall = synchronized(lock) {
            val currentCall = activeCall
            if (currentCall != null && nowMillis - currentCall.detectedAtMillis <= DEDUPE_WINDOW_MILLIS) {
                val updatedCall = currentCall.withPhoneNumber(normalizedPhoneNumber)
                activeCall = updatedCall
                updatedCall
            } else {
                val sessionId = CallSessionRepository.recordDetected(
                    appContext,
                    normalizedPhoneNumber,
                    nowMillis
                )
                ActiveIncomingCall(
                    sessionId = sessionId,
                    phoneNumber = normalizedPhoneNumber,
                    detectedAtMillis = nowMillis
                ).also { activeCall = it }
            }
        }

        if (normalizedPhoneNumber.isNotBlank()) {
            CallSessionRepository.updatePhoneNumberIfBlank(
                appContext,
                incomingCall.sessionId,
                normalizedPhoneNumber
            )
        }
        showOverlayOrFallback(appContext, incomingCall)
        return incomingCall.sessionId
    }

    fun clear() {
        synchronized(lock) {
            activeCall = null
        }
    }

    private data class ActiveIncomingCall(
        val sessionId: String,
        val phoneNumber: String,
        val detectedAtMillis: Long
    ) {
        fun withPhoneNumber(phoneNumber: String): ActiveIncomingCall =
            if (this.phoneNumber.isBlank() && phoneNumber.isNotBlank()) {
                copy(phoneNumber = phoneNumber)
            } else {
                this
            }
    }

    private fun showOverlayOrFallback(context: Context, incomingCall: ActiveIncomingCall) {
        if (!Settings.canDrawOverlays(context)) {
            markPopupUnavailable(
                context = context,
                sessionId = incomingCall.sessionId,
                reason = "Display-over-apps permission is missing, so the call popup could not be shown."
            )
            return
        }

        try {
            CallOverlayService.show(context, incomingCall.phoneNumber, incomingCall.sessionId)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Android blocked the call popup service start.", exception)
            markPopupUnavailable(
                context = context,
                sessionId = incomingCall.sessionId,
                reason = "Android or the device battery manager blocked the call popup start."
            )
        }
    }

    private fun markPopupUnavailable(context: Context, sessionId: String, reason: String) {
        CallSessionRepository.markFailed(context, sessionId, reason)
        CallFallbackNotifier.showPopupUnavailable(context, reason)
    }

    private const val TAG = "IncomingCallOverlay"
    private const val DEDUPE_WINDOW_MILLIS = 30_000L
}
