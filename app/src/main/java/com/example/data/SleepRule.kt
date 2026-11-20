package com.example.data

/**
 * Sleep protection, as agreed (November 2026), for YouTube, YouTube Music and downloaded songs:
 *
 * - Sleep timer: playback pauses after the chosen minutes, or when the video / song playing when
 *   it was set ends (the next one starting counts as its end).
 * - "Still watching?": after [stillWatchingMinutes] of playback without a touch or key in the app
 *   (media buttons count too), the app asks; without an answer, and without any touch since it
 *   asked, playback pauses after [ANSWER_MS].
 *
 * All times are elapsed realtime (ms).
 */
object SleepRule {
    const val ANSWER_MS = 60_000L
    const val MINUTE_MS = 60_000L

    /** Time to ask "Still watching?": on, playing, not asking yet, and no activity for [minutes]. */
    fun shouldAsk(now: Long, lastActivityAt: Long, minutes: Int, playing: Boolean, asking: Boolean): Boolean =
        minutes > 0 && playing && !asking && now - lastActivityAt >= minutes * MINUTE_MS

    /** The question's time is up: pause unless someone touched the app after it was asked. */
    fun pauseUnanswered(now: Long, deadline: Long, askedAt: Long, lastActivityAt: Long): Boolean =
        deadline > 0 && now >= deadline && lastActivityAt <= askedAt

    /** The minutes left on a timer ending at [endsAt], rounded up (at least 1). */
    fun minutesLeft(now: Long, endsAt: Long): Int =
        (((endsAt - now).coerceAtLeast(0L) + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)

    /** An end-of-video timer set for [timerVideoId] is due: it ended, or another one is playing. */
    fun endReached(timerVideoId: String, videoId: String, ended: Boolean): Boolean =
        timerVideoId.isNotBlank() && videoId.isNotBlank() && (videoId != timerVideoId || ended)
}
