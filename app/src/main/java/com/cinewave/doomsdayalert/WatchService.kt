package com.cinewave.doomsdayalert

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class WatchService : Service() {
    companion object {
        const val ACTION_NOW = "com.cinewave.doomsdayalert.CHECK_NOW"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var checker: Checker? = null
    private var wake: PowerManager.WakeLock? = null
    private var busy = false
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val n = Notifier.watching(this, "Starting...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (!active) {
            active = true
            Prefs.putBool(this, Prefs.WATCHING, true)
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "doomsday:watch").apply {
                setReferenceCounted(false)
                acquire()
            }
            checker = Checker(this)
            Prefs.log(this, "Watching started")
            handler.post(tick)
        } else if (intent?.action == ACTION_NOW) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (busy) {
                handler.postDelayed(this, 15000)
                return
            }
            val url = Prefs.str(this@WatchService, Prefs.MOVIE_URL)
            if (!url.startsWith("http")) {
                Prefs.log(this@WatchService, "No BookMyShow link saved (Setup tab)")
                scheduleNext()
                return
            }
            busy = true
            checker?.run(url) { r ->
                busy = false
                onResult(r)
                scheduleNext()
            }
        }
    }

    private fun scheduleNext() {
        if (!active) return
        val secs = maxOf(60, Prefs.int(this, Prefs.INTERVAL, 120))
        handler.postDelayed(tick, secs * 1000L + Random.nextLong(0, 15000))
    }

    private fun onResult(r: CheckResult) {
        val c: Context = this
        val now = System.currentTimeMillis()
        val prev = Prefs.str(c, Prefs.LAST_STATUS)
        Prefs.putLong(c, Prefs.LAST_CHECK, now)
        Prefs.putStr(c, Prefs.LAST_STATUS, r.status)
        Prefs.putStr(c, Prefs.LAST_DETAIL, r.detail)
        Prefs.putInt(c, Prefs.COUNT, Prefs.int(c, Prefs.COUNT, 0) + 1)
        if (r.movieText.isNotBlank()) Prefs.putStr(c, Prefs.SNIP_MOVIE, r.movieText)
        if (r.showText.isNotBlank()) Prefs.putStr(c, Prefs.SNIP_SHOW, r.showText)
        if (prev != r.status) Prefs.log(c, "${r.status}: ${r.detail}")

        val failed = r.status == "ERROR" || r.status == "BLOCKED"
        val streak = if (failed) Prefs.int(c, Prefs.FAIL_STREAK, 0) + 1 else 0
        Prefs.putInt(c, Prefs.FAIL_STREAK, streak)
        if (streak >= 4 && now - Prefs.long(c, Prefs.LAST_NOTICE, 0L) > 60 * 60 * 1000L) {
            Prefs.putLong(c, Prefs.LAST_NOTICE, now)
            Notifier.notice(
                c, "Doomsday Alert cannot read BookMyShow",
                "${r.detail}\nCheck BookMyShow yourself until this is fixed. Open the app > Status."
            )
        }

        val movieUrl = Prefs.str(c, Prefs.MOVIE_URL)
        if (r.status == "OPEN") {
            val seen = Prefs.seen(c)
            val fresh = r.items - seen
            if (!Prefs.bool(c, Prefs.OPENED, false)) {
                Prefs.putBool(c, Prefs.OPENED, true)
                Prefs.log(c, "ALERT: bookings open")
                Notifier.alert(c, "Doomsday bookings are OPEN in Chennai", Extract.summary(r.items), r.showUrl.ifBlank { movieUrl })
            } else if (fresh.isNotEmpty()) {
                Prefs.log(c, "ALERT: new shows (${fresh.size})")
                Notifier.alert(c, "New Doomsday shows in Chennai", Extract.summary(fresh), r.showUrl.ifBlank { movieUrl })
            }
            Prefs.putSeen(c, seen + r.items)
        } else if (r.status == "BUTTON_ONLY" && !Prefs.bool(c, Prefs.BTN_ALERTED, false)) {
            Prefs.putBool(c, Prefs.BTN_ALERTED, true)
            Prefs.log(c, "ALERT: booking button appeared")
            Notifier.alert(c, "Doomsday booking button appeared", "BookMyShow now shows a booking button. Open it and check Chennai.", r.showUrl.ifBlank { movieUrl })
        }

        val time = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(now))
        Notifier.updateWatching(c, "Last check $time: ${r.status.lowercase().replace('_', ' ')}")
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        checker?.destroy()
        checker = null
        try { wake?.release() } catch (e: Exception) { }
        Prefs.log(this, "Watching stopped")
        super.onDestroy()
    }
}
