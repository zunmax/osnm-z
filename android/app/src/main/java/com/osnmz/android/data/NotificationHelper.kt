package com.osnmz.android.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.osnmz.android.ui.MainActivity

object NotificationHelper {
    const val SERVICE_CHANNEL = "osnmz_service"
    const val EVENT_CHANNEL = "osnmz_events"
    const val SERVICE_ID = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Mint berjalan", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifikasi persisten selama ada job mint yang berjalan di background."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(EVENT_CHANNEL, "Event mint", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Job mulai, transaksi terkirim, terkonfirmasi, atau gagal."
            }
        )
    }

    fun serviceNotification(context: Context, text: String): Notification {
        return Notification.Builder(context, SERVICE_CHANNEL)
            .setContentTitle("OSNM-Z Multi-Job")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun event(context: Context, title: String, text: String) {
        try {
            createChannels(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, EVENT_CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        } catch (_: Exception) {
            // Notification permission denied: the in-app log is still authoritative.
            // Callers also write the same event to the job log, so nothing is lost silently.
        }
    }
}
