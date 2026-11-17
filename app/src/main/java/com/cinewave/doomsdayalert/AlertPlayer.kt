package com.cinewave.doomsdayalert

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Plays the chosen sound on the ALARM volume, so it rings even in silent mode. */
object AlertPlayer {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    fun isPlaying(): Boolean = player != null

    private fun tryPlay(ctx: Context, uri: Uri, loop: Boolean): Boolean {
        return try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.setDataSource(ctx, uri)
            p.isLooping = loop
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            Prefs.log(ctx, "Sound failed: ${e.message}")
            false
        }
    }

    fun start(c: Context) {
        stop()
        val ctx = c.applicationContext
        val loop = Prefs.bool(ctx, Prefs.LOOP, true)
        val saved = Prefs.str(ctx, Prefs.SOUND_URI)
        val fallback: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        var ok = false
        if (saved.isNotBlank()) ok = tryPlay(ctx, Uri.parse(saved), loop)
        if (!ok && fallback != null) ok = tryPlay(ctx, fallback, loop)

        if (Prefs.bool(ctx, Prefs.VIBRATE, true)) {
            try {
                val v = ctx.getSystemService(Vibrator::class.java)
                v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), if (loop) 0 else -1))
                vibrator = v
            } catch (e: Exception) {
                Prefs.log(ctx, "Vibration failed: ${e.message}")
            }
        }
        handler.postDelayed(autoStop, if (loop) 5 * 60 * 1000L else 30 * 1000L)
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        vibrator = null
    }
}
