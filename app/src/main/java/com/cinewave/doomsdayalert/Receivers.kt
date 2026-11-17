package com.cinewave.doomsdayalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlertPlayer.stop()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.bool(context, Prefs.WATCHING, false)) {
            try {
                context.startForegroundService(Intent(context, WatchService::class.java))
            } catch (e: Exception) {
                Prefs.log(context, "Could not auto-start after reboot: ${e.message}")
            }
        }
    }
}
