package com.example.recommend

import kotlin.math.ln

/** A song played in the app, as the music home's own shelves see it. */
data class SongStat(
    val videoId: String,
    val title: String,
    val artist: String,
    val channelId: String,
    val durationSec: Int,
    val listenedFraction: Float,
    val lastPlayedAt: Long,
    val playCount: Int
) {
    val artistKey: String get() = MusicForYouRule.artistKey(artist, channelId)
}

/** A Speed dial tile: a song, or an artist (shown round, with the picture of their top song). */
data class SpeedDialTile(
    val isArtist: Boolean,
    /** The song (for an artist: their most played song, for the picture). */
    val song: SongStat
)

/**
 * The music home's shelves built from the songs played in the app (YouTube Music's app has the
 * same shelves for an account):
 *
 * - **Speed dial**: up to 27 tiles. A song's score is its listening score (listened fraction x
 *   0.5^(days ago / 30) x (1 + ln(play count)), as in [MusicForYouRule]); an artist's is the sum
 *   over their songs, and an artist gets a tile only with at least 2 played songs. Songs and
 *   artists by score, every third tile an artist while there are artists left.
 * - **Mixed for you**: radios of the 6 best-scoring songs, at most one per artist.
 * - **Forgotten favorites**: songs played at least twice, last played 30 to 365 days ago, most
 *   played first (up to 20).
 */
object MusicShelves {
    const val SPEED_DIAL_MAX = 27
    const val ARTIST_EVERY = 3
    const val ARTIST_MIN_SONGS = 2
    const val RADIOS = 6
    const val FORGOTTEN_MIN_PLAYS = 2
    const val FORGOTTEN_AFTER_DAYS = 30
    const val FORGOTTEN_WITHIN_DAYS = 365
    const val FORGOTTEN_MAX = 20

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun score(s: SongStat, now: Long): Double =
        s.listenedFraction * MusicForYouRule.decay(now - s.lastPlayedAt, MusicForYouRule.LISTEN_HALF_LIFE_DAYS) *
            (1.0 + ln(s.playCount.coerceAtLeast(1).toDouble()))

    fun speedDial(songs: List<SongStat>, now: Long, max: Int = SPEED_DIAL_MAX): List<SpeedDialTile> {
        val played = songs.distinctBy { it.videoId }.filter { score(it, now) > 0 }
        val byScore = played.sortedByDescending { score(it, now) }
        val artists = played.filter { it.artistKey.isNotBlank() }
            .groupBy { it.artistKey }
            .filterValues { it.size >= ARTIST_MIN_SONGS }
            .map { (_, list) -> list.maxBy { score(it, now) } to list.sumOf { score(it, now) } }
            .sortedByDescending { it.second }
            .map { it.first }
        val songQueue = ArrayDeque(byScore)
        val artistQueue = ArrayDeque(artists)
        val out = ArrayList<SpeedDialTile>()
        while (out.size < max && (songQueue.isNotEmpty() || artistQueue.isNotEmpty())) {
            val artistTurn = out.size % ARTIST_EVERY == ARTIST_EVERY - 1
            out.add(
                if (artistTurn && artistQueue.isNotEmpty() || songQueue.isEmpty()) SpeedDialTile(true, artistQueue.removeFirst())
                else SpeedDialTile(false, songQueue.removeFirst())
            )
        }
        return out
    }

    fun radios(songs: List<SongStat>, now: Long, count: Int = RADIOS): List<SongStat> =
        songs.distinctBy { it.videoId }
            .filter { score(it, now) > 0 }
            .sortedByDescending { score(it, now) }
            .distinctBy { it.artistKey.ifBlank { it.videoId } }
            .take(count)

    fun forgottenFavorites(songs: List<SongStat>, now: Long, max: Int = FORGOTTEN_MAX): List<SongStat> =
        songs.distinctBy { it.videoId }
            .filter {
                val age = now - it.lastPlayedAt
                it.playCount >= FORGOTTEN_MIN_PLAYS && age >= FORGOTTEN_AFTER_DAYS * DAY_MS && age <= FORGOTTEN_WITHIN_DAYS * DAY_MS
            }
            .sortedWith(compareByDescending<SongStat> { it.playCount }.thenByDescending { it.lastPlayedAt })
            .take(max)
}
