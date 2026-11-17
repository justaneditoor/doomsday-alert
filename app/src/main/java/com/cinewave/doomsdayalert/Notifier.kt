package com.cinewave.doomsdayalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri

object Notifier {
    const val CH_WATCH = "watch"
    const val CH_ALERT = "alert"
    const val CH_NOTICE = "notice"
    private const val ICON = android.R.drawable.ic_dialog_info
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val watch = NotificationChannel(CH_WATCH, "Watching status", NotificationManager.IMPORTANCE_LOW)
        watch.setShowBadge(false)
        nm.createNotificationChannel(watch)

        // Silent on purpose: the sound is played by AlertPlayer on the alarm volume.
        val alert = NotificationChannel(CH_ALERT, "Ticket alerts", NotificationManager.IMPORTANCE_HIGH)
        alert.setSound(null, null)
        alert.enableVibration(false)
        alert.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alert)

        val notice = NotificationChannel(CH_NOTICE, "Problems and warnings", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(notice)
    }

    private fun openApp(c: Context): PendingIntent =
        PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), FLAGS)

    fun watching(c: Context, text: String): Notification =
        Notification.Builder(c, CH_WATCH)
            .setSmallIcon(ICON)
            .setContentTitle("Doomsday Alert is watching Chennai")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(c))
            .build()

    fun updateWatching(c: Context, text: String) {
        c.getSystemService(NotificationManager::class.java).notify(1, watching(c, text))
    }

    fun notice(c: Context, title: String, text: String) {
        ensureChannels(c)
        val n = Notification.Builder(c, CH_NOTICE)
            .setSmallIcon(ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(2, n)
    }

    fun alert(c: Context, title: String, text: String, url: String) {
        ensureChannels(c)
        val view: PendingIntent = if (url.startsWith("http")) {
            PendingIntent.getActivity(
                c, 1,
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                FLAGS
            )
        } else openApp(c)
        val stop = PendingIntent.getBroadcast(c, 2, Intent(c, StopReceiver::class.java), FLAGS)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(c, android.R.drawable.ic_media_pause), "Stop sound", stop
        ).build()

        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(view)
            .addAction(stopAction)
            .build()
        c.getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % 100000).toInt() + 100, n)
        AlertPlayer.start(c)
    }
}
