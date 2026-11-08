package com.example.data

/**
 * Where a video you open continues, as in the YouTube app (agreed November 2026): from where you
 * stopped, if you watched at least [MIN_POSITION_SEC] and stopped before [MAX_FRACTION] of it and
 * more than [END_MARGIN_SEC] before its end; otherwise from the start. Music (YouTube Music and
 * music videos) always starts from the beginning, and so do live streams (no length).
 *
 * Applies to videos you pick (a list in the app, search results, a channel or playlist page, a
 * link, a video tapped in the player page); autoplay, mixes and playlists moving on start at 0:00.
 * A link that already names a time (`t=` / `start=`) keeps it.
 */
object ResumeRule {
    const val MIN_POSITION_SEC = 10
    const val MAX_FRACTION = 0.9f
    const val END_MARGIN_SEC = 10

    /** Seconds to continue from, or 0 for the start. */
    fun resumeAt(positionSec: Int, durationSec: Int, watchedFraction: Float, isMusic: Boolean): Int {
        if (isMusic || durationSec <= 0 || positionSec < MIN_POSITION_SEC) return 0
        if (watchedFraction >= MAX_FRACTION || positionSec >= durationSec - END_MARGIN_SEC) return 0
        return positionSec
    }

    private val START_PARAM = Regex("[?&](t|start)=")

    /** The link already names where to start. */
    fun hasStartTime(url: String): Boolean = START_PARAM.containsMatchIn(url.substringBefore('#'))

    /** A watch page link (not Shorts, live or YouTube Music), which a start time can be added to. */
    fun isWatchLink(url: String): Boolean {
        val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val host = url.substringAfter("://", "").substringBefore('/')
        return path == "watch" && (host == "m.youtube.com" || host == "www.youtube.com" || host == "youtube.com")
    }

    /** [url] starting at [sec] seconds (YouTube's own `t=` parameter). */
    fun withStart(url: String, sec: Int): String {
        val base = url.substringBefore('#')
        val fragment = if (url.contains('#')) "#" + url.substringAfter('#') else ""
        val sep = if (base.contains('?')) "&" else "?"
        return "$base${sep}t=${sec}s$fragment"
    }
}
