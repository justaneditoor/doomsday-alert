package com.cinewave.doomsdayalert

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

data class PageData(val title: String, val text: String, val href: String, val url: String)

data class CheckResult(
    val status: String,   // NOT_OPEN, OPEN, BUTTON_ONLY, BLOCKED, ERROR
    val detail: String,
    val items: Set<String> = emptySet(),
    val showUrl: String = "",
    val movieText: String = "",
    val showText: String = ""
)

data class Tokens(val hasShows: Boolean, val items: Set<String>)

object Extract {
    private val timeRe = Regex("""\b\d{1,2}:\d{2}\s?(AM|PM)\b""", RegexOption.IGNORE_CASE)
    private val skipRe = Regex(
        """(?i)^(filter|price|cancellation|non-cancellable|available|fast filling|almost full|sold out|info|more|show|shows|language|format|preferred|time|today|tomorrow|book|select|clear|apply|sort|search|hindi|english|tamil|telugu|kannada|malayalam)\b"""
    )
    private val fmtRe = Regex(
        """\b(IMAX(?: WITH LASER)?|4DX|SCREENX|MX4D|ICE|LUXE|ONYX|DOLBY ATMOS|DOLBY CINEMA|INFINITY VISION|INFINITY|TIMAX|3D|2D)\b"""
    )
    private val langRe = Regex("""\b(TAMIL|ENGLISH|HINDI|TELUGU|MALAYALAM|KANNADA)\b""")
    private val dateRe = Regex(
        """\b(?:(?:MON|TUE|WED|THU|FRI|SAT|SUN)[A-Z]*\s+)?(\d{1,2})\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\b"""
    )

    fun extract(text: String): Tokens {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val items = LinkedHashSet<String>()
        var hasShows = false

        for (i in lines.indices) {
            if (!timeRe.containsMatchIn(lines[i])) continue
            hasShows = true
            var j = i - 1
            while (j >= 0 && j >= i - 5) {
                val cand = lines[j]
                if (!timeRe.containsMatchIn(cand) && cand.length in 6..80 &&
                    cand.any { it.isLetter() } && !cand.contains("₹") && !skipRe.containsMatchIn(cand)
                ) {
                    items.add("venue: $cand")
                    break
                }
                j--
            }
        }
        val up = text.uppercase()
        for (m in fmtRe.findAll(up)) items.add("format: ${m.groupValues[1]}")
        for (m in langRe.findAll(up)) items.add("lang: ${m.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }}")
        for (m in dateRe.findAll(up.replace('\n', ' '))) items.add("date: ${m.groupValues[1]} ${m.groupValues[2]}")

        return Tokens(hasShows, items.take(300).toSet())
    }

    fun summary(items: Set<String>): String {
        fun pick(prefix: String) = items.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val dates = pick("date: ")
        val venues = pick("venue: ")
        val formats = pick("format: ")
        val langs = pick("lang: ")
        val parts = mutableListOf<String>()
        if (dates.isNotEmpty()) parts.add("Dates: " + dates.joinToString(", "))
        if (venues.isNotEmpty()) {
            parts.add(
                "Theatres (${venues.size}): " + venues.take(5).joinToString("; ") +
                    if (venues.size > 5) " +${venues.size - 5} more" else ""
            )
        }
        if (formats.isNotEmpty()) parts.add("Formats: " + formats.joinToString(", "))
        if (langs.isNotEmpty()) parts.add("Languages: " + langs.joinToString(", "))
        return if (parts.isEmpty()) "Open BookMyShow now." else parts.joinToString("\n")
    }
}

/** Loads BookMyShow in a hidden real browser (WebView) from the phone's own connection. */
class Checker(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    private val js = """(function(){try{var a=document.querySelector('a[href*="buytickets"]');var h=a?a.href:'';var x=document.body?document.body.innerText:'';return JSON.stringify({t:document.title||'',x:x,h:h,u:location.href});}catch(e){return JSON.stringify({t:'',x:'JS error '+e,h:'',u:''});}})()"""

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(): WebView {
        web?.let { return it }
        val w = WebView(ctx)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.blockNetworkImage = true
        s.loadsImagesAutomatically = false
        s.userAgentString = s.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        CookieManager.getInstance().setAcceptCookie(true)
        web = w
        return w
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        try { web?.stopLoading(); web?.destroy() } catch (e: Exception) { }
        web = null
    }

    private fun loadAndRead(url: String, cb: (PageData?, String?) -> Unit) {
        val w = webView()
        var finished = false

        fun finish(d: PageData?, err: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            try { w.stopLoading() } catch (e: Exception) { }
            cb(d, err)
        }

        val readRunnable = Runnable {
            if (!finished) {
                w.evaluateJavascript(js) { raw ->
                    try {
                        val s = JSONTokener(raw).nextValue() as? String ?: ""
                        val o = JSONObject(s)
                        finish(PageData(o.optString("t"), o.optString("x"), o.optString("h"), o.optString("u")), null)
                    } catch (e: Exception) {
                        finish(null, "Read error: ${e.message}")
                    }
                }
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String?) {
                if (finished) return
                handler.removeCallbacks(readRunnable)
                handler.postDelayed(readRunnable, 7000)   // let the page finish drawing
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(null, "Load error: ${error.description}")
            }
        }
        handler.postDelayed({ finish(null, "Timed out after 50 seconds") }, 50000)
        w.loadUrl(url)
    }

    private fun looksBlocked(d: PageData): Boolean {
        val t = (d.title + " " + d.text.take(400)).lowercase()
        val words = listOf(
            "access denied", "just a moment", "attention required", "captcha",
            "verify you are human", "unusual traffic", "request blocked", "forbidden", "are you a robot"
        )
        return words.any { t.contains(it) } || d.text.trim().length < 40
    }

    private fun constructed(movieUrl: String): String? {
        val m = Regex("""/movies/[^/]+/([^/?#]+)/(ET\d+)""").find(movieUrl) ?: return null
        return "https://in.bookmyshow.com/buytickets/${m.groupValues[1]}-chennai/movie-chen-${m.groupValues[2]}-MT"
    }

    fun run(movieUrl: String, cb: (CheckResult) -> Unit) {
        loadAndRead(movieUrl) { d1, err1 ->
            if (d1 == null) {
                cb(CheckResult("ERROR", err1 ?: "Unknown error"))
            } else if (looksBlocked(d1)) {
                cb(CheckResult("BLOCKED", "Blocked or empty page. Title: \"${d1.title}\"", movieText = d1.text.take(2500)))
            } else {
                val bookText = d1.text.lines().any {
                    val t = it.trim()
                    t.equals("book tickets", true) || t.equals("book now", true)
                }
                val button = d1.href.isNotBlank() || bookText
                val mt = d1.text.take(2500)
                if (!button) {
                    cb(CheckResult("NOT_OPEN", "Page loaded fine. No booking button yet.", movieText = mt))
                } else {
                    val showUrl = if (d1.href.isNotBlank()) d1.href else (constructed(movieUrl) ?: "")
                    if (showUrl.isBlank()) {
                        cb(CheckResult("BUTTON_ONLY", "Booking button seen, but no showtimes link found.", movieText = mt))
                    } else {
                        loadAndRead(showUrl) { d2, err2 ->
                            if (d2 == null) {
                                cb(CheckResult("BUTTON_ONLY", "Booking button seen. Showtimes page failed: $err2", showUrl = showUrl, movieText = mt))
                            } else if (looksBlocked(d2)) {
                                cb(CheckResult("BUTTON_ONLY", "Booking button seen. Showtimes page blocked or empty.", showUrl = showUrl, movieText = mt, showText = d2.text.take(2500)))
                            } else {
                                val t = Extract.extract(d2.text)
                                if (t.hasShows) {
                                    cb(CheckResult("OPEN", "${t.items.size} items found", t.items, showUrl, mt, d2.text.take(2500)))
                                } else {
                                    cb(CheckResult("BUTTON_ONLY", "Booking button seen, no showtimes listed yet.", showUrl = showUrl, movieText = mt, showText = d2.text.take(2500)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
