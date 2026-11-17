package com.cinewave.doomsdayalert

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    const val MOVIE_URL = "movie_url"
    const val INTERVAL = "interval"
    const val SOUND_URI = "sound_uri"
    const val SOUND_NAME = "sound_name"
    const val LOOP = "loop"
    const val VIBRATE = "vibrate"
    const val WATCHING = "watching"
    const val LAST_CHECK = "last_check"
    const val LAST_STATUS = "last_status"
    const val LAST_DETAIL = "last_detail"
    const val COUNT = "count"
    const val FAIL_STREAK = "fail_streak"
    const val OPENED = "opened"
    const val BTN_ALERTED = "btn_alerted"
    const val SEEN = "seen"
    const val LOG = "log"
    const val SNIP_MOVIE = "snip_movie"
    const val SNIP_SHOW = "snip_show"
    const val LAST_NOTICE = "last_notice"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("doomsday", Context.MODE_PRIVATE)

    fun str(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun putStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    fun bool(c: Context, k: String, d: Boolean = false): Boolean = sp(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    fun int(c: Context, k: String, d: Int = 0): Int = sp(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).apply() }
    fun long(c: Context, k: String, d: Long = 0L): Long = sp(c).getLong(k, d)
    fun putLong(c: Context, k: String, v: Long) { sp(c).edit().putLong(k, v).apply() }

    fun seen(c: Context): Set<String> = HashSet(sp(c).getStringSet(SEEN, emptySet()) ?: emptySet())
    fun putSeen(c: Context, s: Set<String>) { sp(c).edit().putStringSet(SEEN, HashSet(s)).apply() }

    fun resetMemory(c: Context) {
        sp(c).edit().remove(SEEN).remove(OPENED).remove(BTN_ALERTED).apply()
    }

    fun log(c: Context, msg: String) {
        val t = SimpleDateFormat("dd MMM HH:mm:ss", Locale.ENGLISH).format(Date())
        val lines = str(c, LOG).split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add(0, "$t  $msg")
        while (lines.size > 60) lines.removeAt(lines.size - 1)
        putStr(c, LOG, lines.joinToString("\n"))
    }
}
