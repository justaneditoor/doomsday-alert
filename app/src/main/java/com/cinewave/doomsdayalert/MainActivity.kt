package com.cinewave.doomsdayalert

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var tabButtons: List<Button>

    private lateinit var tvHeadline: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var tvSound: TextView
    private lateinit var seekVol: SeekBar
    private lateinit var etUrl: EditText
    private lateinit var etInterval: EditText

    private val green = Color.rgb(22, 128, 60)
    private val amber = Color.rgb(200, 120, 0)
    private val red = Color.rgb(190, 30, 30)

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    // ---------- small UI helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.rgb(30, 30, 30)): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(6), 0, dp(6))
        return tv
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun page(vararg views: View): ScrollView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(32))
        for (v in views) col.addView(v)
        val sv = ScrollView(this)
        sv.addView(col)
        return sv
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("Could not open that settings screen on this phone.") }
    }

    private fun fmt(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(Date(ms))

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val title = label("Doomsday Alert - Chennai", 20f, true)
        title.setPadding(dp(16), dp(36), dp(16), dp(4))
        root.addView(title)

        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        val names = listOf("Status", "Alerts", "Setup")
        val btns = ArrayList<Button>()
        for (i in names.indices) {
            val b = button(names[i]) { showTab(i) }
            tabRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            btns.add(b)
        }
        tabButtons = btns
        root.addView(tabRow)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        pages = listOf(buildStatus(), buildAlerts(), buildSetup())
        showTab(if (Prefs.str(this, Prefs.MOVIE_URL).startsWith("http")) 0 else 2)
    }

    override fun onResume() {
        super.onResume()
        // Self-heal: if you asked it to watch, make sure the service is really running.
        if (Prefs.bool(this, Prefs.WATCHING, false)) {
            try { startForegroundService(Intent(this, WatchService::class.java)) } catch (e: Exception) { }
        }
        syncVolume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun showTab(i: Int) {
        content.removeAllViews()
        content.addView(pages[i])
        for (j in tabButtons.indices) tabButtons[j].alpha = if (j == i) 1f else 0.5f
        refresh()
    }

    // ---------- STATUS TAB ----------
    private fun buildStatus(): View {
        tvHeadline = label("", 22f, true)
        tvDetail = label("", 15f)
        tvStats = label("", 13f, false, Color.DKGRAY)
        tvLog = label("", 12f, false, Color.DKGRAY)
        tvLog.typeface = Typeface.MONOSPACE
        btnToggle = button("") { toggleWatching() }
        return page(
            tvHeadline, tvDetail, btnToggle,
            button("Check now") { checkNow() },
            tvStats,
            label("Recent activity", 16f, true),
            tvLog,
            button("Copy debug info (to send to Claude)") { copyDebug() }
        )
    }

    private fun refresh() {
        if (!::tvHeadline.isInitialized) return
        val watching = Prefs.bool(this, Prefs.WATCHING, false)
        val last = Prefs.long(this, Prefs.LAST_CHECK, 0L)
        val status = Prefs.str(this, Prefs.LAST_STATUS)
        val interval = maxOf(60, Prefs.int(this, Prefs.INTERVAL, 120))
        val now = System.currentTimeMillis()
        val ageSec = (now - last) / 1000

        val headline: String
        val color: Int
        if (!watching) {
            headline = "NOT WATCHING"; color = red
        } else if (last == 0L) {
            headline = "STARTING..."; color = amber
        } else if (ageSec > interval * 3 + 90) {
            headline = "STALLED: no check for ${ageSec / 60} min"; color = red
        } else if (status == "OPEN") {
            headline = "BOOKINGS OPEN IN CHENNAI"; color = green
        } else if (status == "BUTTON_ONLY") {
            headline = "BOOKING BUTTON APPEARED"; color = green
        } else if (status == "BLOCKED" || status == "ERROR") {
            headline = "RUNNING, BUT CANNOT READ PAGE"; color = amber
        } else {
            headline = "WORKING"; color = green
        }
        tvHeadline.text = headline
        tvHeadline.setTextColor(color)
        tvDetail.text = if (last == 0L) "No check finished yet."
        else "Last check ${fmt(last)} (${ageSec}s ago)\n${Prefs.str(this, Prefs.LAST_DETAIL)}"
        tvStats.text = "Checks done: ${Prefs.int(this, Prefs.COUNT, 0)}   |   Every ~${interval}s   |   Failures in a row: ${Prefs.int(this, Prefs.FAIL_STREAK, 0)}"
        tvLog.text = Prefs.str(this, Prefs.LOG).ifBlank { "Nothing yet." }
        btnToggle.text = if (watching) "Stop watching" else "Start watching"
    }

    private fun toggleWatching() {
        if (Prefs.bool(this, Prefs.WATCHING, false)) {
            Prefs.putBool(this, Prefs.WATCHING, false)
            stopService(Intent(this, WatchService::class.java))
        } else {
            if (!Prefs.str(this, Prefs.MOVIE_URL).startsWith("http")) {
                toast("First paste your BookMyShow link in Setup.")
                showTab(2)
                return
            }
            Prefs.putBool(this, Prefs.WATCHING, true)
            startForegroundService(Intent(this, WatchService::class.java))
        }
        refresh()
    }

    private fun checkNow() {
        if (!Prefs.bool(this, Prefs.WATCHING, false)) {
            toast("Tap Start watching first.")
            return
        }
        startService(Intent(this, WatchService::class.java).setAction(WatchService.ACTION_NOW))
        toast("Checking now. Takes about 10-20 seconds.")
    }

    private fun copyDebug() {
        val sb = StringBuilder()
        sb.append("Doomsday Alert v1.0 | Android ${Build.VERSION.RELEASE} | ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Link: ${Prefs.str(this, Prefs.MOVIE_URL)}\n")
        sb.append("Status: ${Prefs.str(this, Prefs.LAST_STATUS)} | ${Prefs.str(this, Prefs.LAST_DETAIL)}\n")
        sb.append("Checks: ${Prefs.int(this, Prefs.COUNT, 0)} | fail streak: ${Prefs.int(this, Prefs.FAIL_STREAK, 0)}\n\n")
        sb.append("--- LOG ---\n${Prefs.str(this, Prefs.LOG)}\n\n")
        sb.append("--- MOVIE PAGE TEXT ---\n${Prefs.str(this, Prefs.SNIP_MOVIE)}\n\n")
        sb.append("--- SHOWTIMES PAGE TEXT ---\n${Prefs.str(this, Prefs.SNIP_SHOW)}\n")
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("debug", sb.toString()))
        toast("Copied. Paste it into your chat with Claude.")
    }

    // ---------- ALERTS TAB ----------
    private fun buildAlerts(): View {
        tvSound = label("", 15f, true)
        val loop = CheckBox(this)
        loop.text = "Keep ringing until I tap Stop (max 5 min)"
        loop.isChecked = Prefs.bool(this, Prefs.LOOP, true)
        loop.setOnCheckedChangeListener { _, v -> Prefs.putBool(this, Prefs.LOOP, v) }

        val vib = CheckBox(this)
        vib.text = "Vibrate"
        vib.isChecked = Prefs.bool(this, Prefs.VIBRATE, true)
        vib.setOnCheckedChangeListener { _, v -> Prefs.putBool(this, Prefs.VIBRATE, v) }

        val am = getSystemService(AudioManager::class.java)
        seekVol = SeekBar(this)
        seekVol.max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        seekVol.progress = am.getStreamVolume(AudioManager.STREAM_ALARM)
        seekVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    try { am.setStreamVolume(AudioManager.STREAM_ALARM, p, 0) } catch (e: Exception) { }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        updateSoundLabel()
        return page(
            label("Alert sound", 18f, true),
            tvSound,
            button("Choose from phone sounds") { pickRingtone() },
            button("Choose my own audio file (MP3 etc.)") { pickFile() },
            button("Use default alarm sound") {
                Prefs.putStr(this, Prefs.SOUND_URI, ""); Prefs.putStr(this, Prefs.SOUND_NAME, ""); updateSoundLabel()
            },
            label("How it rings", 18f, true),
            loop, vib,
            label("Alarm volume (alerts use the ALARM volume, so they ring even on silent)", 13f, false, Color.DKGRAY),
            seekVol,
            label("Test", 18f, true),
            button("Send test alert") {
                Notifier.alert(this, "TEST: Doomsday alert", "This is how a real alert will look and sound.", "")
            },
            button("Stop sound") { AlertPlayer.stop() }
        )
    }

    private fun syncVolume() {
        if (!::seekVol.isInitialized) return
        val am = getSystemService(AudioManager::class.java)
        seekVol.progress = am.getStreamVolume(AudioManager.STREAM_ALARM)
    }

    private fun updateSoundLabel() {
        val n = Prefs.str(this, Prefs.SOUND_NAME)
        tvSound.text = "Current: " + if (n.isBlank()) "Default alarm sound" else n
    }

    private fun pickRingtone() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose alert sound")
        val cur = Prefs.str(this, Prefs.SOUND_URI)
        if (cur.isNotBlank()) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur))
        safeStartForResult(i, 10)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        safeStartForResult(i, 11)
    }

    private fun safeStartForResult(i: Intent, code: Int) {
        try { startActivityForResult(i, code) } catch (e: Exception) { toast("Could not open the picker on this phone.") }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 10) {
            val uri: Uri? = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val name = try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) } catch (e: Exception) { null }
                Prefs.putStr(this, Prefs.SOUND_URI, uri.toString())
                Prefs.putStr(this, Prefs.SOUND_NAME, name ?: "Selected sound")
                updateSoundLabel()
            }
        } else if (requestCode == 11) {
            val uri = data.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            var name = "My audio file"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cur.moveToFirst()) name = cur.getString(idx)
                }
            } catch (e: Exception) { }
            Prefs.putStr(this, Prefs.SOUND_URI, uri.toString())
            Prefs.putStr(this, Prefs.SOUND_NAME, name)
            updateSoundLabel()
        }
    }

    // ---------- SETUP TAB ----------
    private fun buildSetup(): View {
        etUrl = EditText(this)
        etUrl.hint = "Paste the BookMyShow share link"
        etUrl.setText(Prefs.str(this, Prefs.MOVIE_URL))
        etUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        etUrl.minLines = 2

        etInterval = EditText(this)
        etInterval.inputType = InputType.TYPE_CLASS_NUMBER
        etInterval.setText(Prefs.int(this, Prefs.INTERVAL, 120).toString())

        return page(
            label("1. BookMyShow link", 18f, true),
            label("In the BookMyShow app open the Avengers: Doomsday page, tap Share, copy, then paste here.", 13f, false, Color.DKGRAY),
            etUrl,
            button("Paste from clipboard") { pasteLink() },
            label("2. Check every (seconds, minimum 60)", 18f, true),
            etInterval,
            button("Save settings") { saveSettings() },
            label("3. Phone settings that keep it alive", 18f, true),
            button("Allow notifications") {
                safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            },
            button("Stop battery optimisation for this app") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            button("Open app settings (set Battery to Unrestricted)") {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            label("Maintenance", 18f, true),
            button("Reset alert memory (to re-test alerts)") {
                Prefs.resetMemory(this); toast("Done. The next open shows will alert again.")
            }
        )
    }

    private fun pasteLink() {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (t.isBlank()) toast("Clipboard is empty.") else etUrl.setText(t.trim())
    }

    private fun saveSettings() {
        val raw = etUrl.text.toString()
        val m = Regex("""https?://\S+""").find(raw)
        if (m == null) {
            toast("That does not contain a link starting with http.")
            return
        }
        Prefs.putStr(this, Prefs.MOVIE_URL, m.value)
        etUrl.setText(m.value)
        val secs = etInterval.text.toString().toIntOrNull() ?: 120
        Prefs.putInt(this, Prefs.INTERVAL, maxOf(60, secs))
        toast("Saved.")
    }
}
