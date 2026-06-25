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
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.db.GuardVoiceRepository

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
    private val verdictReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val riskLevel = intent.getStringExtra(AudioCaptureService.EXTRA_RISK_LEVEL)
            val riskScore = intent.getIntExtra(AudioCaptureService.EXTRA_RISK_SCORE, 0)
            val transcript = intent.getStringExtra(AudioCaptureService.EXTRA_TRANSCRIPT).orEmpty()
            val reasons = intent.getStringArrayExtra(AudioCaptureService.EXTRA_REASONS)
                ?.toList().orEmpty()
            updateVerdictDisplay(riskLevel, riskScore, transcript, reasons)
        }
    }
    private var overlayView: View? = null
    private var isCaptureRequested = false
    private var isCaptureStateReceiverRegistered = false
    private var isVerdictReceiverRegistered = false
    private var activeSessionId = ""

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        registerCaptureStateReceiver()
        registerVerdictReceiver()
        callStateMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW) {
            activeSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        }

        try {
            startForegroundOverlay()
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not start call overlay foreground service.", exception)
            if (activeSessionId.isNotBlank()) {
                val reason = "Android blocked the call popup foreground service."
                CallSessionRepository.markFailed(this, activeSessionId, reason)
                CallFallbackNotifier.showPopupUnavailable(this, reason)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SHOW) {
            showOverlay(
                phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty(),
                sessionId = activeSessionId
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!isCaptureRequested) {
            CallSessionRepository.markDeclinedIfWaiting(this, activeSessionId)
        }
        removeOverlay()
        unregisterCaptureStateReceiver()
        unregisterVerdictReceiver()
        callStateMonitor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(phoneNumber: String, sessionId: String) {
        activeSessionId = sessionId
        if (!Settings.canDrawOverlays(this)) {
            CallSessionRepository.markFailed(
                this,
                activeSessionId,
                "Display-over-apps permission is missing, so the call popup could not be shown."
            )
            CallFallbackNotifier.showPopupUnavailable(
                this,
                "Display-over-apps permission is missing, so the call popup could not be shown."
            )
            stopSelf()
            return
        }

        removeOverlay()
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_call, null)
        view.findViewById<TextView>(R.id.tv_number).text =
            getString(R.string.overlay_consent_title)
        view.findViewById<TextView>(R.id.tv_verdict).text =
            getString(R.string.overlay_waiting_for_consent)
        view.findViewById<Button>(R.id.btn_yes).setOnClickListener {
            startListening(phoneNumber, view)
        }
        view.findViewById<Button>(R.id.btn_no).setOnClickListener {
            if (isCaptureRequested) {
                GuardVoiceRepository.getInstance(this@CallOverlayService).insertDecision(
                    sessionId = activeSessionId,
                    phoneNumber = phoneNumber,
                    decision = "Stop",
                    reason = "User stopped capture"
                )
                stopAudioCapture()
            } else {
                CallSessionRepository.markDeclinedIfWaiting(this, activeSessionId)
                GuardVoiceRepository.getInstance(this@CallOverlayService).insertDecision(
                    sessionId = activeSessionId,
                    phoneNumber = phoneNumber,
                    decision = "Decline",
                    reason = "User declined consent"
                )
            }
            stopSelf()
        }

        try {
            windowManager.addView(view, overlayParams())
            overlayView = view
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not show call overlay.", exception)
            CallSessionRepository.markFailed(
                this,
                activeSessionId,
                "The call popup could not be attached to the screen."
            )
            CallFallbackNotifier.showPopupUnavailable(
                this,
                "The device blocked the call popup window."
            )
            stopSelf()
        }
    }

    private fun startListening(phoneNumber: String, view: View) {
        isCaptureRequested = true
        try {
            GuardVoiceRepository.getInstance(this).insertDecision(
                sessionId = activeSessionId,
                phoneNumber = phoneNumber,
                decision = "Allow",
                reason = "User allowed call capture"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist decision to SQLite", e)
        }
        view.findViewById<TextView>(R.id.tv_verdict).text =
            getString(R.string.overlay_starting_capture)
        view.findViewById<Button>(R.id.btn_yes).apply {
            text = getString(R.string.overlay_listening)
            isEnabled = false
        }
        view.findViewById<Button>(R.id.btn_no).text = getString(R.string.overlay_stop)
        try {
            CallCaptureHandoffActivity.start(this, phoneNumber, activeSessionId)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not start audio capture service.", exception)
            CallSessionRepository.markFailed(
                this,
                activeSessionId,
                "Microphone tracking could not start from the call popup."
            )
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

    private fun updateVerdictDisplay(
        riskLevel: String?,
        riskScore: Int,
        transcript: String,
        reasons: List<String>
    ) {
        val view = overlayView ?: return
        val verdictView = view.findViewById<TextView>(R.id.tv_verdict)
        val transcriptView = view.findViewById<TextView>(R.id.tv_transcript)
        val reasonsView = view.findViewById<TextView>(R.id.tv_verdict_details)

        val normalizedVerdictDisplay = displayForRiskLevel(riskLevel)
        verdictView.text = normalizedVerdictDisplay.first
        verdictView.setTextColor(normalizedVerdictDisplay.second)

        if (transcript.isNotBlank()) {
            transcriptView.text = transcript
            transcriptView.visibility = View.VISIBLE
        }

        if (reasons.isNotEmpty()) {
            reasonsView.visibility = View.VISIBLE
            reasonsView.text = reasons.joinToString(" / ")
        }
    }

    private fun displayForRiskLevel(riskLevel: String?): Pair<String, Int> =
        when (riskLevel) {
            "Safe" -> Pair("Safe call", 0xFF2E7D32.toInt())
            "Suspicious" -> Pair("Suspicious", 0xFFF57F17.toInt())
            "Scam" -> Pair("Scam detected!", 0xFFC62828.toInt())
            else -> Pair("Analyzing...", 0xFF1565C0.toInt())
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

    private fun registerVerdictReceiver() {
        val filter = IntentFilter(AudioCaptureService.ACTION_VERDICT_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(verdictReceiver, filter, RECEIVER_NOT_EXPORTED)
            isVerdictReceiverRegistered = true
            return
        }
        @Suppress("DEPRECATION")
        registerReceiver(verdictReceiver, filter)
        isVerdictReceiverRegistered = true
    }

    private fun unregisterVerdictReceiver() {
        if (!isVerdictReceiverRegistered) {
            return
        }
        try {
            unregisterReceiver(verdictReceiver)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Verdict receiver was already unregistered.", exception)
        } finally {
            isVerdictReceiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "CallOverlayService"
        private const val ACTION_SHOW = "com.guardvoice.action.SHOW_CALL_OVERLAY"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val NOTIFICATION_CHANNEL_ID = "guardvoice_call_monitoring"
        private const val OVERLAY_NOTIFICATION_ID = 2001
        private const val NOTIFICATION_REQUEST_CODE = 43
        private const val OVERLAY_TOP_OFFSET_PX = 96

        fun show(context: Context, phoneNumber: String, sessionId: String) {
            val intent = Intent(context, CallOverlayService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallOverlayService::class.java))
        }
    }
}
