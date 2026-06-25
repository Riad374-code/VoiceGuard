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
    fun showPopupUnavailable(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) {
            return
        }
        ensureNotificationChannel(appContext, NOTIFICATION_CHANNEL_ID, context.getString(R.string.popup_fallback_notification_channel), NotificationManager.IMPORTANCE_HIGH)
        appContext.getSystemService(NotificationManager::class.java).notify(
            FALLBACK_NOTIFICATION_ID,
            buildNotification(appContext, NOTIFICATION_CHANNEL_ID, context.getString(R.string.popup_fallback_notification_title), reason)
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

    private fun buildNotification(context: Context, channelId: String, title: String, text: String): Notification {
        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
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
    private const val HEALTH_NOTIFICATION_CHANNEL_ID = "guardvoice_audio_health"
    private const val FALLBACK_NOTIFICATION_ID = 2003
    private const val HEALTH_ALERT_NOTIFICATION_ID = 2004
    private const val NOTIFICATION_REQUEST_CODE = 45
}
