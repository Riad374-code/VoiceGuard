package com.voiceguard.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import timber.log.Timber
import com.voiceguard.R
import com.voiceguard.di.ServiceLocator
import com.voiceguard.pipeline.ListenMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the listening session lifetime for Listener Mode: holds a partial wake
 * lock, shows a persistent notification with live status ("Listening – risk
 * 12%") plus a Stop action, and posts a high-importance heads-up + vibration
 * when risk >= 80. START_NOT_STICKY: Android 14 forbids background microphone
 * restarts, so a killed service stays dead until the user taps Start again.
 * Started ONLY from the Activity's Start button tap.
 */
class ListeningForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAlertAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Timber.tag(TAG).i("Stop action")
                ServiceLocator.orchestrator.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundNow("Starting…")
                acquireWake()
                ServiceLocator.orchestrator.startListener()
                observeState()
                observeHighRisk()
                return START_NOT_STICKY
            }
        }
    }

    private fun observeState() {
        scope.launch {
            ServiceLocator.orchestrator.ui.collect { st ->
                if (st.mode != ListenMode.LISTENER) return@collect
                val text = if (st.risk != null) {
                    "Listening – risk ${st.risk}% (${st.verdict.name})"
                } else {
                    st.statusText.take(64)
                }
                updateNotification(text)
                if (!st.running) {
                    // Session ended on its own (blocked phone, error): shut down.
                    stopSelf()
                }
            }
        }
    }

    private fun observeHighRisk() {
        scope.launch {
            ServiceLocator.orchestrator.highRisk.collect { v ->
                val now = System.currentTimeMillis()
                if (now - lastAlertAt < 30_000) return@collect
                lastAlertAt = now
                postAlert("High scam risk: ${v.risk}%", v.advice.ifBlank { "Treat this call with suspicion." })
                vibrate()
            }
        }
    }

    private fun startForegroundNow(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "Listening", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildListenNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildListenNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildListenNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ListeningForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_LISTEN)
            .setContentTitle("VoiceGuard listening")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun postAlert(title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Risk alerts", NotificationManager.IMPORTANCE_HIGH)
            )
            val openIntent = PendingIntent.getActivity(
                this, 2, packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, CH_ALERT)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ALERT_ID, n)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Alert failed")
        }
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("InvalidWakeLockTag")
    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceGuard:listen").apply {
                acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.voiceguard.START_LISTEN"
        const val ACTION_STOP = "com.voiceguard.STOP"
        private const val TAG = "ListenService"
        private const val CH_LISTEN = "voiceguard_listen"
        private const val CH_ALERT = "voiceguard_alert"
        private const val NOTIF_ID = 1001
        private const val NOTIF_ALERT_ID = 1002
    }
}

