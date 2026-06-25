@file:Suppress("DEPRECATION")

package com.guardvoice.call

import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.Context

internal class CallStateMonitor(
    private val context: Context,
    private val onCallIdle: () -> Unit
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    fun start() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startTelephonyCallback()
            } else {
                startPhoneStateListener()
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Phone state permission missing; call-end cleanup disabled.", exception)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Call state monitor could not start.", exception)
        }
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let(telephonyManager::unregisterTelephonyCallback)
                telephonyCallback = null
                return
            }

            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Call state monitor could not stop.", exception)
        }
    }

    private fun startTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    onCallIdle()
                }
            }
        }
        telephonyManager.registerTelephonyCallback(
            ContextCompat.getMainExecutor(context),
            callback
        )
        telephonyCallback = callback
    }

    @Suppress("DEPRECATION")
    private fun startPhoneStateListener() {
        val listener = object : PhoneStateListener() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    onCallIdle()
                }
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        phoneStateListener = listener
    }

    private companion object {
        private const val TAG = "CallStateMonitor"
    }
}
