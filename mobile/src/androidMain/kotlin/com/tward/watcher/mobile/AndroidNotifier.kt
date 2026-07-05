package com.tward.watcher.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

/** Surfaces hook notifications through Android's notification system. */
class AndroidNotifier(private val context: Context) : Notifier {

    private val nextId = AtomicInteger(1)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hook notifications",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications fired by terminal-watcher hooks"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun notify(title: String, body: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(nextId.getAndIncrement(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; drop the notification.
        }
    }

    private companion object {
        const val CHANNEL_ID = "terminal-watcher-hooks"
    }
}
