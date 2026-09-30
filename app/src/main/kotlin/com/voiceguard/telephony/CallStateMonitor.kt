package com.voiceguard.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Exposes whether the device is currently in a cellular call. When true and the
 * user picked Listener Mode, the UI blocks starting and explains the OS mic
 * limit (the mic returns zeros while a GSM/VoLTE call is active on this phone).
 */
class CallStateMonitor(private val context: Context) {
    private val _isInCall = MutableStateFlow(false)
    val isInCall: StateFlow<Boolean> = _isInCall.asStateFlow()

    private var telephony: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
    private var listener: PhoneStateListener? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasPermission()) {
            Timber.tag(TAG).w("READ_PHONE_STATE missing; call detection off")
            return
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephony = tm
        refresh(tm)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        _isInCall.value = state != TelephonyManager.CALL_STATE_IDLE
                    }
                }
                callback = cb
                tm.registerTelephonyCallback(Executors.newSingleThreadExecutor(), cb)
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("deprecated")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        _isInCall.value = state != TelephonyManager.CALL_STATE_IDLE
                    }
                }
                listener = l
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Call monitor failed")
        }
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback?.let { telephony?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                listener?.let { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
        }
        callback = null
        listener = null
        telephony = null
    }

    private fun refresh(tm: TelephonyManager) {
        try {
            _isInCall.value = tm.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "CallMonitor"
    }
}

