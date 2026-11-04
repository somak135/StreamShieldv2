package com.example.offline

/** A song that plays without a connection: in the up-next cache, or a smart or own download. */
data class LocalSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val channelId: String,
    val durationSec: Int,
    /** In the up-next cache (else a smart or own download). */
    val cached: Boolean,
    /** Rank at the last smart-download refresh (lower is better); [Int.MAX_VALUE] for others. */
    val smartRank: Int = Int.MAX_VALUE
) {
    val artistKey: String get() = artistKeyOf(artist, channelId)
}

/** "Artist - Topic" and "artist" are the same artist; else the channel id. */
fun artistKeyOf(artist: String, channelId: String): String =
    artist.trim().removeSuffix(" - Topic").trim().lowercase().ifBlank { channelId }

/** Why the app switched to downloaded songs. */
enum class FallbackTrigger { NONE, NO_CONNECTION, NOT_STARTING, STALLED }

/** What the online player (YouTube Music) is doing, as the switch needs it. */
data class WebSnapshot(
    val isMusicPage: Boolean,
    val isPlaying: Boolean,
    /** Paused by the user (page controls or the app's buttons). */
    val userPaused: Boolean,
    /** Waiting for data since (ms since 1970), 0 if not. */
    val stallAt: Long
)

/**
 * When the connection is gone, YouTube Music's songs are replaced by downloaded ones, as agreed:
 *
 * Switch (only on YouTube Music, not after the user paused, at most once per song):
 * - a song ended (or the next one is not playing) and Android reports no working internet: after
 *   [END_OFFLINE_MS];
 * - the next song has not started [START_TIMEOUT_MS] after the previous one stopped;
 * - the song is waiting for data for [STALL_ONLINE_MS] ([STALL_OFFLINE_MS] with no internet).
 *
 * Order of the downloaded songs: the songs after the current one in YouTube Music's queue that
 * are available (its own order); then songs YouTube lists with the current one (its Up next and
 * related lists); then songs by the current song's artist; then smart downloads by rank, then
 * the other downloads. Songs played in the last [RECENT_HOURS] hours are left out of the last
 * three groups. A stalled song that is in the cache continues first, from where it was.
 *
 * Back to YouTube Music: once the connection has worked for [RETURN_STABLE_MS] (and at least
 * that long after the switch), the downloaded song that is playing finishes, then YouTube Music
 * continues its queue after the last of its songs that played. If the switch happened while
 * Android still reported a connection (a weak signal), the connection must have dropped and come
 * back since, or [RETURN_WEAK_MS] have passed, so the app does not go back and forth.
 */
object FallbackRule {
    const val END_OFFLINE_MS = 1_500L
    const val START_TIMEOUT_MS = 10_000L
    const val STALL_ONLINE_MS = 10_000L
    const val STALL_OFFLINE_MS = 5_000L
    const val RETURN_STABLE_MS = 20_000L
    const val RETURN_WEAK_MS = 5 * 60_000L
    const val RECENT_HOURS = 2
    const val MAX_SONGS = 40

    /**
     * [notPlayingSince]: when YouTube Music stopped playing while playback was expected (ms since
     * 1970, 0 if it plays or nothing is expected). [now] in the same clock.
     */
    fun trigger(now: Long, online: Boolean, web: WebSnapshot, notPlayingSince: Long): FallbackTrigger {
        if (!web.isMusicPage || web.userPaused) return FallbackTrigger.NONE
        if (web.isPlaying) {
            if (web.stallAt <= 0) return FallbackTrigger.NONE
            val limit = if (online) STALL_ONLINE_MS else STALL_OFFLINE_MS
            return if (now - web.stallAt >= limit) FallbackTrigger.STALLED else FallbackTrigger.NONE
        }
        if (notPlayingSince <= 0) return FallbackTrigger.NONE
        val waited = now - notPlayingSince
        return when {
            !online && waited >= END_OFFLINE_MS -> FallbackTrigger.NO_CONNECTION
            waited >= START_TIMEOUT_MS -> FallbackTrigger.NOT_STARTING
            else -> FallbackTrigger.NONE
        }
    }

    /**
     * Downloaded songs to play after [currentId] (see the rule). [queueAfter]: YouTube Music's
     * queue after the current song, in order; [listedWith]: songs YouTube lists with it;
     * [recentlyPlayed]: played in the last [RECENT_HOURS] hours.
     */
    fun order(
        currentId: String,
        currentArtistKey: String,
        queueAfter: List<String>,
        listedWith: Set<String>,
        available: List<LocalSong>,
        recentlyPlayed: Set<String>,
        max: Int = MAX_SONGS
    ): List<LocalSong> {
        val byId = LinkedHashMap<String, LocalSong>()
        // One entry per song; a cached copy and a download of the same song are the same song.
        for (s in available) if (s.videoId !in byId || (!s.cached && byId.getValue(s.videoId).cached)) byId[s.videoId] = s
        byId.remove(currentId)
        val out = LinkedHashMap<String, LocalSong>()
        fun add(s: LocalSong) {
            if (out.size < max) out.putIfAbsent(s.videoId, s)
        }
        queueAfter.forEach { id -> byId[id]?.let(::add) }
        val rest = byId.values.filter { it.videoId !in recentlyPlayed }
        rest.filter { it.videoId in listedWith }.forEach(::add)
        if (currentArtistKey.isNotBlank()) rest.filter { it.artistKey == currentArtistKey }.forEach(::add)
        rest.sortedWith(compareBy<LocalSong> { it.smartRank }.thenBy { it.title.lowercase() }).forEach(::add)
        return out.values.toList()
    }

    /**
     * Whether YouTube Music may take over again (after the downloaded song that is playing).
     * [onlineChangedAt]: when [online] last changed; [startedAt]: when the switch happened; all
     * in the same clock as [now].
     */
    fun mayReturn(now: Long, online: Boolean, onlineChangedAt: Long, startedAt: Long): Boolean {
        if (!online || now - onlineChangedAt < RETURN_STABLE_MS || now - startedAt < RETURN_STABLE_MS) return false
        return onlineChangedAt > startedAt || now - startedAt >= RETURN_WEAK_MS
    }

    /** Where YouTube Music continues: the first queue song after the last one that played. */
    fun resumeIndex(queue: List<String>, played: Collection<String>): Int {
        val last = played.maxOfOrNull { queue.indexOf(it) } ?: -1
        return last + 1
    }
}
