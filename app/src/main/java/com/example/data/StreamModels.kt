package com.example.data

import android.net.Uri
import java.util.Locale
import kotlin.math.roundToInt

val PLAYBACK_SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/** Playback state of the YouTube page, as reported by the injected shield script. */
data class WebVideoState(
    val videoId: String = "",
    val url: String = "",
    val title: String = "",
    val channel: String = "",
    val durationSec: Int = 0,
    val isPlaying: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Milliseconds from navigation until the video's data was loaded (metadata), or -1. */
    val loadMs: Int = -1,
    /** Milliseconds from navigation to the first frame, or -1 until the video has started. */
    val startMs: Int = -1,
    /** Milliseconds the player spent in an ad state while starting this video. */
    val adMs: Int = 0,
    /** Ad entries removed from YouTube's responses for this video. */
    val adsPruned: Int = 0,
    /** In PiP: pause calls from the page that were refused, and pauses that came from the browser. */
    val pipBlockedPauses: Int = 0,
    val pipSystemPauses: Int = 0,
    /** In PiP: times playback stalled waiting for data (live streams run dry when this grows). */
    val pipStalls: Int = 0,
    /** From the player response's videoDetails (empty / 0 until it has been seen). */
    val channelId: String = "",
    val lengthSec: Int = 0,
    val viewCount: Long = 0L,
    /** YouTube category, e.g. "Music". */
    val category: String = "",
    /** Playback position: exact while paused, in 10 s steps while playing. */
    val positionSec: Int = 0,
    val ended: Boolean = false,
    /** YouTube's 17 s player timers that were shortened while this video started. */
    val timersBoosted: Int = 0,
    /** Taps on the page's play/pause button that the page missed and the app applied. */
    val tapFixes: Int = 0,
    /** What was on top at the tapped point the last time (diagnostics). */
    val tapFixTop: String = "",
    /**
     * The last time the page moved on to the next video while the app was hidden, and how:
     * "page" (opened the next video's page), "queue" (YouTube Music's next track), "player" (the
     * page's next track), "none" (nothing to play next). Empty if it has not happened.
     */
    val autoAdvance: String = "",
    val autoAdvanceAt: Long = 0L
) {
    /** Best known duration: the player response's length, else the media element's. */
    val bestDurationSec: Int get() = if (lengthSec > 0) lengthSec else durationSec

    val isMusicPage: Boolean get() = url.startsWith("https://music.youtube.com")

    val isMusic: Boolean get() = isMusicPage || category.equals("Music", ignoreCase = true)
}

fun extractYouTubeVideoId(url: String): String? {
    if (url.isBlank()) return null
    return try {
        val uri = Uri.parse(url)
        val vParam = uri.getQueryParameter("v")
        if (!vParam.isNullOrBlank()) {
            return vParam
        }
        val path = uri.path ?: ""
        when {
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore("/")
            path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore("/")
            path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore("/")
            uri.host?.contains("youtu.be") == true -> path.removePrefix("/").substringBefore("/")
            else -> null
        }?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

fun formatSpeedBadge(speed: Float): String {
    val hundredths = (speed * 100f).roundToInt()
    return if (hundredths % 10 == 0) {
        String.format(Locale.US, "%.1fx", hundredths / 100f)
    } else {
        String.format(Locale.US, "%.2fx", hundredths / 100f)
    }
}

fun formatCount(count: Long): String = when {
    count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 10_000L -> String.format(Locale.US, "%.0fk", count / 1_000.0)
    count >= 1_000L -> String.format(Locale.US, "%.1fk", count / 1_000.0)
    else -> count.toString()
}

/** Medium-quality thumbnail (320x180) for a video id; served without an API key. */
fun thumbnailUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

fun watchUrl(videoId: String, startSec: Int = 0): String =
    if (startSec > 5) "https://m.youtube.com/watch?v=$videoId&t=${startSec}s" else "https://m.youtube.com/watch?v=$videoId"

fun formatDuration(totalSec: Int): String {
    if (totalSec <= 0) return ""
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** "3 hours ago", "2 days ago", ... */
fun formatAgo(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return ""
    val minutes = ((now - epochMs) / 60_000L).coerceAtLeast(0L)
    fun unit(n: Long, name: String) = if (n == 1L) "1 $name ago" else "$n ${name}s ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> unit(minutes, "minute")
        minutes < 60 * 24 -> unit(minutes / 60, "hour")
        minutes < 60 * 24 * 30 -> unit(minutes / (60 * 24), "day")
        minutes < 60 * 24 * 365 -> unit(minutes / (60 * 24 * 30), "month")
        else -> unit(minutes / (60 * 24 * 365), "year")
    }
}

private val YOUTUBE_LINK = Regex(
    """(?i)\b((?:https?://)?(?:[a-z0-9-]+\.)*(?:youtube\.com|youtu\.be)/[^\s<>"']*)"""
)

/**
 * Finds a YouTube link in [text] (a pasted URL, or a message shared from another app such as
 * "watch this https://youtu.be/abc?si=x") and returns the URL to load in the player page, or null
 * if there is none. youtu.be short links become m.youtube.com watch URLs (keeping a start time);
 * other youtube.com links are moved to m.youtube.com, except YouTube Music links.
 */
fun youTubeLinkOrNull(text: String): String? {
    val raw = YOUTUBE_LINK.find(text)?.groupValues?.get(1)?.trimEnd('.', ',', ')', '!', '?') ?: return null
    val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
    val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
    val host = uri.host?.lowercase() ?: return null
    return when {
        host == "youtu.be" -> {
            val id = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val t = uri.getQueryParameter("t")
            if (t.isNullOrBlank()) "https://m.youtube.com/watch?v=$id" else "https://m.youtube.com/watch?v=$id&t=$t"
        }
        host == "music.youtube.com" -> url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        host == "youtube.com" || host.endsWith(".youtube.com") ->
            uri.buildUpon().scheme("https").authority("m.youtube.com").build().toString()
        else -> null
    }
}

/**
 * True for a YouTube (not YouTube Music) page that is a video: /watch, /shorts/, /live/<id>,
 * /embed/. These get the full video page (no tabs); every other page keeps the app's tabs.
 */
fun isYouTubeVideoPage(url: String): Boolean {
    val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
    val host = uri.host?.lowercase() ?: return false
    if (host == "music.youtube.com" || !(host == "youtube.com" || host.endsWith(".youtube.com"))) return false
    val path = uri.path.orEmpty()
    return path == "/watch" || path.startsWith("/shorts/") || path.startsWith("/embed/") ||
        (path.startsWith("/live/") && path.length > "/live/".length)
}

private val BROWSE_PATH = Regex("^/(@|channel/|c/|user/|results|hashtag/|playlist|feed/|source/)")

/** A YouTube (not YouTube Music) page that belongs in the browse page: results, channels, playlists. */
fun isYouTubeBrowsePage(url: String): Boolean {
    val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
    val host = uri.host?.lowercase() ?: return false
    if (host == "music.youtube.com" || !(host == "youtube.com" || host.endsWith(".youtube.com"))) return false
    return BROWSE_PATH.containsMatchIn(uri.path.orEmpty())
}
