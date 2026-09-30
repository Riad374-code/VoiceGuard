package com.guardvoice.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.guardvoice.MainActivity
import com.guardvoice.R

internal object CallFallbackNotifier {
    /**
     * Last-resort popup path: when the overlay FGS can't start (Android 12+
     * background-start restrictions, OEM task killers), a high-priority
     * notification with a full-screen intent still rings the user.
     */
    fun showPopupUnavailable(context: Context, reason: String, phoneNumber: String = "") {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) {
            return
        }
        ensureNotificationChannel(appContext, NOTIFICATION_CHANNEL_ID, context.getString(R.string.popup_fallback_notification_channel), NotificationManager.IMPORTANCE_HIGH)
        appContext.getSystemService(NotificationManager::class.java).notify(
            FALLBACK_NOTIFICATION_ID,
            buildNotification(appContext, NOTIFICATION_CHANNEL_ID, context.getString(R.string.popup_fallback_notification_title), reason, phoneNumber)
        )
    }

    fun showAudioHealthAlert(context: Context, message: String) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) {
            return
        }
        ensureNotificationChannel(appContext, HEALTH_NOTIFICATION_CHANNEL_ID, context.getString(R.string.audio_health_notification_channel), NotificationManager.IMPORTANCE_HIGH)
        appContext.getSystemService(NotificationManager::class.java).notify(
            HEALTH_ALERT_NOTIFICATION_ID,
            buildNotification(appContext, HEALTH_NOTIFICATION_CHANNEL_ID, context.getString(R.string.audio_health_notification_title), message)
        )
    }

    private fun buildNotification(context: Context, channelId: String, title: String, text: String, phoneNumber: String = ""): Notification {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            if (phoneNumber.isNotBlank()) putExtra(EXTRA_INCOMING_NUMBER, phoneNumber)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
        if (phoneNumber.isNotBlank()) {
            // Full-screen fallback for blocked overlay starts. Guarded: without
            // USE_FULL_SCREEN_INTENT (Android 14+) this call throws.
            try {
                builder.setFullScreenIntent(pendingIntent, true)
            } catch (_: Exception) {}
        }
        return builder.build()
    }

    private fun ensureNotificationChannel(context: Context, channelId: String, channelName: String, importance: Int) {
        val channel = NotificationChannel(
            channelId,
            channelName,
            importance
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private const val NOTIFICATION_CHANNEL_ID = "guardvoice_call_fallback"
    private const val EXTRA_INCOMING_NUMBER = "extra_incoming_number"
    private const val HEALTH_NOTIFICATION_CHANNEL_ID = "guardvoice_audio_health"
    private const val FALLBACK_NOTIFICATION_ID = 2003
    private const val HEALTH_ALERT_NOTIFICATION_ID = 2004
    private const val NOTIFICATION_REQUEST_CODE = 45
}
