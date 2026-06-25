package com.guardvoice.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.guardvoice.MainActivity
import com.guardvoice.R

class CallOverlayService : Service() {
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val callStateMonitor by lazy {
        CallStateMonitor(this) {
            stopSelf()
        }
    }
    private val captureStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateName = intent.getStringExtra(AudioCaptureService.EXTRA_CAPTURE_STATE)
            val state = AudioCaptureService.CaptureState.entries
                .firstOrNull { it.name == stateName }
            updateCaptureState(state)
        }
    }
    private var overlayView: View? = null
    private var isCaptureRequested = false
    private var isCaptureStateReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        registerCaptureStateReceiver()
        callStateMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundOverlay()
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not start call overlay foreground service.", exception)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SHOW) {
            showOverlay(intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        unregisterCaptureStateReceiver()
        callStateMonitor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(phoneNumber: String) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        removeOverlay()
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_call, null)
        val displayNumber = phoneNumber.ifBlank { getString(R.string.overlay_title) }
        view.findViewById<TextView>(R.id.tv_number).text = displayNumber
        view.findViewById<TextView>(R.id.tv_verdict).text =
            getString(R.string.overlay_waiting_for_consent)
        view.findViewById<Button>(R.id.btn_yes).setOnClickListener {
            startListening(phoneNumber, view)
        }
        view.findViewById<Button>(R.id.btn_no).setOnClickListener {
            if (isCaptureRequested) {
                stopAudioCapture()
            }
            stopSelf()
        }

        try {
            windowManager.addView(view, overlayParams())
            overlayView = view
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not show call overlay.", exception)
            stopSelf()
        }
    }

    private fun startListening(phoneNumber: String, view: View) {
        isCaptureRequested = true
        view.findViewById<TextView>(R.id.tv_verdict).text =
            getString(R.string.overlay_starting_capture)
        view.findViewById<Button>(R.id.btn_yes).apply {
            text = getString(R.string.overlay_listening)
            isEnabled = false
        }
        view.findViewById<Button>(R.id.btn_no).text = getString(R.string.overlay_stop)
        try {
            AudioCaptureService.start(this, phoneNumber)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not start audio capture service.", exception)
            updateCaptureState(AudioCaptureService.CaptureState.Failed)
        }
    }

    private fun updateCaptureState(state: AudioCaptureService.CaptureState?) {
        val view = overlayView ?: return
        val verdict = view.findViewById<TextView>(R.id.tv_verdict)
        val allowButton = view.findViewById<Button>(R.id.btn_yes)

        when (state) {
            AudioCaptureService.CaptureState.Listening -> {
                verdict.text = getString(R.string.overlay_capture_active)
            }
            AudioCaptureService.CaptureState.Failed -> {
                verdict.text = getString(R.string.overlay_capture_failed)
                allowButton.text = getString(R.string.overlay_allow)
                allowButton.isEnabled = true
                isCaptureRequested = false
            }
            AudioCaptureService.CaptureState.Stopped -> {
                if (isCaptureRequested) {
                    stopSelf()
                }
            }
            null -> Unit
        }
    }

    private fun stopAudioCapture() {
        try {
            AudioCaptureService.stop(this)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not stop audio capture service.", exception)
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Call overlay was already detached.", exception)
            }
        }
        overlayView = null
    }

    private fun overlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = OVERLAY_TOP_OFFSET_PX
        }

    private fun startForegroundOverlay() {
        val notification = buildNotification(
            title = getString(R.string.overlay_notification_title),
            text = getString(R.string.overlay_notification_text)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OVERLAY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
            return
        }
        startForeground(OVERLAY_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.call_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerCaptureStateReceiver() {
        val filter = IntentFilter(AudioCaptureService.ACTION_CAPTURE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureStateReceiver, filter, RECEIVER_NOT_EXPORTED)
            isCaptureStateReceiverRegistered = true
            return
        }
        @Suppress("DEPRECATION")
        registerReceiver(captureStateReceiver, filter)
        isCaptureStateReceiverRegistered = true
    }

    private fun unregisterCaptureStateReceiver() {
        if (!isCaptureStateReceiverRegistered) {
            return
        }
        try {
            unregisterReceiver(captureStateReceiver)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Capture state receiver was already unregistered.", exception)
        } finally {
            isCaptureStateReceiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "CallOverlayService"
        private const val ACTION_SHOW = "com.guardvoice.action.SHOW_CALL_OVERLAY"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val NOTIFICATION_CHANNEL_ID = "guardvoice_call_monitoring"
        private const val OVERLAY_NOTIFICATION_ID = 2001
        private const val NOTIFICATION_REQUEST_CODE = 43
        private const val OVERLAY_TOP_OFFSET_PX = 96

        fun show(context: Context, phoneNumber: String) {
            val intent = Intent(context, CallOverlayService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
