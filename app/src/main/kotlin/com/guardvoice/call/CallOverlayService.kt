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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
            val transcriptDelta = intent.getStringExtra(AudioCaptureService.EXTRA_TRANSCRIPT_DELTA).orEmpty()
            val reasons = intent.getStringArrayExtra(AudioCaptureService.EXTRA_REASONS)?.toList().orEmpty()
            val keywords = intent.getStringArrayExtra(AudioCaptureService.EXTRA_KEYWORDS)?.toList().orEmpty()
            val elapsedSec = intent.getFloatExtra(AudioCaptureService.EXTRA_ELAPSED_SEC, -1f)
            updateVerdictDisplay(riskLevel, riskScore, transcript.ifBlank { transcriptDelta }, reasons, keywords, elapsedSec)
        }
    }
    private val streamStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(AudioCaptureService.EXTRA_STREAM_STATUS).orEmpty()
            if (status.isBlank()) return
            val view = overlayView ?: return
            val details = view.findViewById<TextView>(R.id.tv_verdict_details)
            when (status) {
                "Reconnecting" -> {
                    details.visibility = View.VISIBLE
                    details.text = "Reconnecting to analysis server…"
                }
                "Error" -> {
                    details.visibility = View.VISIBLE
                    details.text = "Offline — local analysis active"
                }
                "Connected", "GeminiConnected" -> {
                    // Clear transient reconnect banner — verdict display will overwrite with real reasons shortly.
                    // Only clear if we currently show the transient text.
                    val cur = details.text?.toString().orEmpty()
                    if (cur == "Reconnecting to analysis server…" || cur == "Offline — local analysis active") {
                        details.text = ""
                        details.visibility = View.GONE
                    }
                }
            }
        }
    }
    private val transcriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Live incremental transcript — updates overlay transcript line sub-second,
            // even before the next 1-sec analysis packet arrives.
            val delta = intent.getStringExtra(AudioCaptureService.EXTRA_TRANSCRIPT_DELTA).orEmpty()
            val accum = intent.getStringExtra(AudioCaptureService.EXTRA_TRANSCRIPT).orEmpty()
            val text = accum.ifBlank { delta }
            if (text.isBlank()) return
            val view = overlayView ?: return
            clearAudioHealthWarningIfShowing()
            val transcriptView = view.findViewById<TextView>(R.id.tv_transcript)
            transcriptView.text = text
            transcriptView.visibility = View.VISIBLE
        }
    }
    private val audioHealthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(AudioCaptureService.EXTRA_HEALTH_ALERT_MESSAGE).orEmpty()
            updateAudioHealthWarning(message)
        }
    }
    private var overlayView: View? = null
    // Minimized state: the card is detached but kept (with live verdict text),
    // a floating bubble is shown instead. Tracking keeps running throughout.
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isCardAttached = false
    private var handoffFallbackRunnable: Runnable? = null
    private var handoffFallbackView: View? = null
    private var isCaptureRequested = false
    private var isCaptureConfirmed = false
    private var audioWarningActive = false
    private var isCaptureStateReceiverRegistered = false
    private var isVerdictReceiverRegistered = false
    private var isTranscriptReceiverRegistered = false
    private var isAudioHealthReceiverRegistered = false
    private var isStreamStatusReceiverRegistered = false
    private var activeSessionId = ""
    // Imitation mode: scripted demo, no mic. Badge says so; consent is
    // skipped (nothing is recorded); Stop ends the script.
    private var isDemoMode = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        registerCaptureStateReceiver()
        registerVerdictReceiver()
        registerTranscriptReceiver()
        registerAudioHealthReceiver()
        registerStreamStatusReceiver()
        callStateMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Restart after process death with no overlay to show — don't linger
            // as a notification with no popup.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_SHOW || intent.action == ACTION_SHOW_DEMO) {
            activeSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        }
        isDemoMode = intent.action == ACTION_SHOW_DEMO

        try {
            startForegroundOverlay()
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not start call overlay foreground service.", exception)
            if (activeSessionId.isNotBlank()) {
                val reason = "Android blocked the call popup foreground service."
                CallSessionRepository.markFailed(this, activeSessionId, reason)
                CallFallbackNotifier.showPopupUnavailable(
                    this,
                    reason,
                    intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_SHOW || intent.action == ACTION_SHOW_DEMO) {
            showOverlay(
                phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty(),
                sessionId = activeSessionId
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // NOTE: no DemoCallEngine.stop() here. The in-app demo must survive an
        // overlay teardown (blocked popup, rotation, service restart) — only
        // the explicit Stop button ends the script. A finished script ends
        // itself; process death takes the executor with it, so nothing leaks.
        if (!isCaptureRequested) {
            CallSessionRepository.markDeclinedIfWaiting(this, activeSessionId)
        } else {
            // Call ended (or overlay killed) while capture runs — never leave
            // the mic service recording behind the torn-down popup.
            stopAudioCapture()
        }
        handoffFallbackRunnable?.let { handoffFallbackView?.removeCallbacks(it) }
        handoffFallbackRunnable = null
        handoffFallbackView = null
        removeOverlay()
        unregisterCaptureStateReceiver()
        unregisterVerdictReceiver()
        unregisterTranscriptReceiver()
        unregisterAudioHealthReceiver()
        unregisterStreamStatusReceiver()
        callStateMonitor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(phoneNumber: String, sessionId: String) {
        // Idempotency: the manifest receiver and the screening role fire for the
        // same call within ms. A second show for the same session only refreshes
        // the number — it must never destroy the visible popup or reset consent.
        if (overlayView != null && activeSessionId == sessionId) {
            overlayView?.findViewById<TextView>(R.id.tv_number)?.text =
                phoneNumber.ifBlank { getString(R.string.overlay_title) }
            // A re-show while minimized brings the card back (never strands the
            // user with just a bubble when the system re-announces the call).
            if (!isCardAttached) restoreOverlay()
            return
        }
        activeSessionId = sessionId
        isCaptureRequested = false
        isCaptureConfirmed = false
        audioWarningActive = false
        if (!Settings.canDrawOverlays(this)) {
            CallSessionRepository.markFailed(
                this,
                activeSessionId,
                "Display-over-apps permission is missing, so the call popup could not be shown."
            )
            CallFallbackNotifier.showPopupUnavailable(
                this,
                "Display-over-apps permission is missing, so the call popup could not be shown.",
                phoneNumber
            )
            stopSelf()
            return
        }

        removeOverlay()
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_call, null)
        val displayNumber = phoneNumber.ifBlank { getString(R.string.overlay_title) }
        view.findViewById<TextView>(R.id.tv_number).text = displayNumber
        if (isDemoMode) {
            view.findViewById<TextView>(R.id.tv_overlay_label).text =
                getString(R.string.overlay_badge_demo)
        }
        val bodyView = view.findViewById<TextView>(R.id.tv_overlay_body)
        bodyView.text = if (phoneNumber.isNotBlank()) {
            getString(R.string.overlay_body_with_number, phoneNumber)
        } else {
            getString(R.string.overlay_body)
        }
        view.findViewById<TextView>(R.id.tv_verdict).text =
            if (isDemoMode) {
                getString(R.string.overlay_demo_starting)
            } else {
                getString(R.string.overlay_waiting_for_consent)
            }
        if (isDemoMode) {
            // Nothing is recorded — no consent needed, script already playing.
            view.findViewById<Button>(R.id.btn_yes).visibility = View.GONE
            view.findViewById<Button>(R.id.btn_no).text = getString(R.string.overlay_stop)
        }
        view.findViewById<Button>(R.id.btn_yes).setOnClickListener {
            startListening(phoneNumber, view)
        }
        view.findViewById<Button>(R.id.btn_no).setOnClickListener {
            if (isDemoMode) {
                try {
                    DemoCallEngine.stop()
                } catch (_: Exception) {
                }
            }
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

        view.findViewById<Button>(R.id.btn_minimize).setOnClickListener {
            minimizeOverlay()
        }
        // Swipe up on empty card areas also minimizes — lets the user peek at
        // the app underneath. Returns false: never steal button clicks.
        view.setOnTouchListener(CardSwipeListener())

        try {
            windowManager.addView(view, overlayParams())
            overlayView = view
            isCardAttached = true
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not show call overlay.", exception)
            CallSessionRepository.markFailed(
                this,
                activeSessionId,
                "The call popup could not be attached to the screen."
            )
            CallFallbackNotifier.showPopupUnavailable(
                this,
                "The device blocked the call popup window.",
                phoneNumber
            )
            stopSelf()
        }
    }

    private fun startListening(phoneNumber: String, view: View) {
        isCaptureRequested = true
        isCaptureConfirmed = false
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ silently drops background activity starts (no
                // exception) — skip the handoff and capture directly.
                AudioCaptureService.start(this, phoneNumber, activeSessionId)
            } else {
                CallCaptureHandoffActivity.start(this, phoneNumber, activeSessionId)
                // Safety net: on some OEMs the handoff never confirms capture.
                // Fall back to starting the microphone foreground service
                // directly so the popup never gets stuck on "Turning on...".
                val fallback = Runnable {
                    if (isCaptureRequested && !isCaptureConfirmed && overlayView != null) {
                        try {
                            AudioCaptureService.start(this, phoneNumber, activeSessionId)
                        } catch (exception: Exception) {
                            Log.e(TAG, "Direct audio capture fallback failed.", exception)
                            updateCaptureState(AudioCaptureService.CaptureState.Failed)
                        }
                    }
                    handoffFallbackRunnable = null
                }
                handoffFallbackRunnable = fallback
                handoffFallbackView = view
                view.postDelayed(fallback, HANDOFF_FALLBACK_DELAY_MS)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Could not start audio capture service.", exception)
            try {
                AudioCaptureService.start(this, phoneNumber, activeSessionId)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Direct audio capture fallback failed.", fallbackException)
                CallSessionRepository.markFailed(
                    this,
                    activeSessionId,
                    "Microphone tracking could not start from the call popup."
                )
                updateCaptureState(AudioCaptureService.CaptureState.Failed)
            }
        }
    }

    private fun updateCaptureState(state: AudioCaptureService.CaptureState?) {
        val view = overlayView ?: return
        val verdict = view.findViewById<TextView>(R.id.tv_verdict)
        val allowButton = view.findViewById<Button>(R.id.btn_yes)

        when (state) {
            AudioCaptureService.CaptureState.Listening -> {
                isCaptureConfirmed = true
                // Fresh capture start — drop any stale hint from a previous attempt.
                audioWarningActive = false
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
        reasons: List<String>,
        keywords: List<String> = emptyList(),
        elapsedSec: Float = -1f
    ) {
        val view = overlayView ?: return
        val verdictView = view.findViewById<TextView>(R.id.tv_verdict)
        val transcriptView = view.findViewById<TextView>(R.id.tv_transcript)
        val reasonsView = view.findViewById<TextView>(R.id.tv_verdict_details)
        val elapsedTag = if (elapsedSec >= 0) " • ${elapsedSec.toInt()}s" else ""

        val normalizedVerdictDisplay = displayForRiskLevel(riskLevel)
        verdictView.text = "${normalizedVerdictDisplay.first} (${riskScore})${elapsedTag}"
        verdictView.setTextColor(normalizedVerdictDisplay.second)

        if (transcript.isNotBlank()) {
            transcriptView.text = transcript
            transcriptView.visibility = View.VISIBLE
            // Real speech arrived — any earlier "no voice" hint is stale.
            clearAudioHealthWarningIfShowing()
        }

        val detailsParts = mutableListOf<String>()
        if (reasons.isNotEmpty()) detailsParts.add(reasons.joinToString(" / "))
        if (keywords.isNotEmpty()) detailsParts.add("kw: ${keywords.take(6).joinToString(", ")}")
        if (detailsParts.isNotEmpty()) {
            // A scored verdict proves the mic→STT→score path works — any
            // earlier audio hint (no voice / server error) is stale.
            clearAudioHealthWarningIfShowing()
            reasonsView.visibility = View.VISIBLE
            reasonsView.text = detailsParts.joinToString(" — ")
        } else if (reasons.isEmpty() && keywords.isEmpty() && reasonsView.visibility == View.VISIBLE) {
            // Verdict update with no reasons clears prior transient status (e.g. "Reconnecting…")
            val cur = reasonsView.text?.toString().orEmpty()
            if (cur == "Reconnecting to analysis server…" || cur == "Offline — local analysis active" || cur.isBlank()) {
                reasonsView.visibility = View.GONE
                reasonsView.text = ""
            }
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
        isCardAttached = false
        bubbleView?.let { bubble ->
            try {
                windowManager.removeView(bubble)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Call bubble was already detached.", exception)
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    /**
     * Collapse the card to a floating bubble so the user can peek at the app
     * underneath. Capture, verdict updates and the service keep running — the
     * detached card still receives text updates and shows them on restore.
     */
    private fun minimizeOverlay() {
        val card = overlayView ?: return
        if (!isCardAttached) return
        try {
            if (bubbleView == null) {
                val bubble = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
                bubble.isClickable = true
                bubble.setOnTouchListener(BubbleTouchListener())
                bubbleView = bubble
            }
            windowManager.removeView(card)
            isCardAttached = false
            val params = bubbleLayoutParams()
            bubbleParams = params
            windowManager.addView(bubbleView, params)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not minimize call overlay.", exception)
        }
    }

    private fun restoreOverlay() {
        val card = overlayView ?: return
        if (isCardAttached) return
        try {
            bubbleView?.let { bubble ->
                try {
                    windowManager.removeView(bubble)
                } catch (exception: RuntimeException) {
                    Log.w(TAG, "Call bubble was already detached.", exception)
                }
            }
            bubbleView = null
            bubbleParams = null
            windowManager.addView(card, overlayParams())
            isCardAttached = true
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not restore call overlay.", exception)
        }
    }

    /** Swipe up (empty card areas only) minimizes the popup. */
    private inner class CardSwipeListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    if (dy < -swipeUpThresholdPx && kotlin.math.abs(dx) < swipeUpThresholdPx) {
                        minimizeOverlay()
                        return true
                    }
                }
            }
            return false
        }
    }

    /** Drag moves the bubble; a tap restores the full card. */
    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging &&
                        kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlopPx
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (exception: RuntimeException) {
                            Log.w(TAG, "Could not move call bubble.", exception)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                        restoreOverlay()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = OVERLAY_TOP_OFFSET_PX
        }

    private fun bubbleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 400
        }

    private val swipeUpThresholdPx: Int
        get() = (80 * resources.displayMetrics.density).toInt()

    private val touchSlopPx: Int
        get() = ViewConfiguration.get(this).scaledTouchSlop

    private fun startForegroundOverlay() {
        val notification = buildNotification(
            title = getString(R.string.overlay_notification_title),
            text = getString(R.string.overlay_notification_text)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OVERLAY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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

    private fun registerAudioHealthReceiver() {
        val filter = IntentFilter(AudioCaptureService.ACTION_AUDIO_HEALTH_ALERT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(audioHealthReceiver, filter, RECEIVER_NOT_EXPORTED)
            isAudioHealthReceiverRegistered = true
            return
        }
        @Suppress("DEPRECATION")
        registerReceiver(audioHealthReceiver, filter)
        isAudioHealthReceiverRegistered = true
    }

    private fun unregisterAudioHealthReceiver() {
        if (!isAudioHealthReceiverRegistered) {
            return
        }
        try {
            unregisterReceiver(audioHealthReceiver)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Audio health receiver was already unregistered.", exception)
        } finally {
            isAudioHealthReceiverRegistered = false
        }
    }

    private fun registerTranscriptReceiver() {
        val filter = IntentFilter(AudioCaptureService.ACTION_TRANSCRIPT_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transcriptReceiver, filter, RECEIVER_NOT_EXPORTED)
            isTranscriptReceiverRegistered = true
            return
        }
        @Suppress("DEPRECATION")
        registerReceiver(transcriptReceiver, filter)
        isTranscriptReceiverRegistered = true
    }

    private fun unregisterTranscriptReceiver() {
        if (!isTranscriptReceiverRegistered) return
        try { unregisterReceiver(transcriptReceiver) } catch (e: RuntimeException) { Log.w(TAG, "Transcript receiver already unregistered", e) }
        finally { isTranscriptReceiverRegistered = false }
    }

    private fun registerStreamStatusReceiver() {
        val filter = IntentFilter(AudioCaptureService.ACTION_STREAM_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
            isStreamStatusReceiverRegistered = true
            return
        }
        @Suppress("DEPRECATION")
        registerReceiver(streamStatusReceiver, filter)
        isStreamStatusReceiverRegistered = true
    }

    private fun unregisterStreamStatusReceiver() {
        if (!isStreamStatusReceiverRegistered) return
        try { unregisterReceiver(streamStatusReceiver) } catch (e: RuntimeException) { Log.w(TAG, "Stream status receiver already unregistered", e) }
        finally { isStreamStatusReceiverRegistered = false }
    }

    private fun updateAudioHealthWarning(message: String) {
        val view = overlayView ?: return
        // Soft hint only: keep the live verdict headline untouched so a transient
        // silence never overwrites "Listening / Analyzing / Safe / Scam".
        val detailsView = view.findViewById<TextView>(R.id.tv_verdict_details)
        detailsView.visibility = View.VISIBLE
        detailsView.text = message
        audioWarningActive = true
    }

    private fun clearAudioHealthWarningIfShowing() {
        if (!audioWarningActive) return
        val view = overlayView ?: return
        val detailsView = view.findViewById<TextView>(R.id.tv_verdict_details)
        val cur = detailsView.text?.toString().orEmpty()
        // startsWith: hints carry a " (SRC lvl=N)" diagnostic suffix.
        if (cur.startsWith(getString(R.string.overlay_no_voice)) ||
            cur.startsWith(getString(R.string.overlay_heard_unrecognized)) ||
            cur.startsWith(getString(R.string.overlay_low_volume)) ||
            cur.startsWith(getString(R.string.overlay_stt_error))
        ) {
            detailsView.text = ""
            detailsView.visibility = View.GONE
        }
        audioWarningActive = false
    }

    companion object {
        private const val TAG = "CallOverlayService"
        private const val ACTION_SHOW = "com.guardvoice.action.SHOW_CALL_OVERLAY"
        private const val ACTION_SHOW_DEMO = "com.guardvoice.action.SHOW_DEMO_OVERLAY"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val NOTIFICATION_CHANNEL_ID = "guardvoice_call_monitoring"
        private const val OVERLAY_NOTIFICATION_ID = 2001
        private const val NOTIFICATION_REQUEST_CODE = 43
        private const val OVERLAY_TOP_OFFSET_PX = 96
        private const val HANDOFF_FALLBACK_DELAY_MS = 2000L

        fun show(context: Context, phoneNumber: String, sessionId: String) {
            val intent = Intent(context, CallOverlayService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Imitation mode popup — scripted demo, no microphone. */
        fun showDemo(context: Context, phoneNumber: String, sessionId: String) {
            val intent = Intent(context, CallOverlayService::class.java)
                .setAction(ACTION_SHOW_DEMO)
                .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallOverlayService::class.java))
        }
    }
}
