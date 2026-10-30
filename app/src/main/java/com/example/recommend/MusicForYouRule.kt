package com.example.recommend

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Where a music "For you" song came from. */
enum class MusicSource { UP_NEXT, RELATED, ARTIST }

/** A candidate song. 0 / "" = unknown. */
data class MusicSong(
    val videoId: String,
    val title: String,
    /** Artist (or channel) name as YouTube shows it. */
    val artist: String,
    val channelId: String,
    val durationSec: Int,
    val viewCount: Long,
    /** Upload time (ms), when known (artist uploads). */
    val publishedAt: Long,
    val isShort: Boolean,
    val source: MusicSource
) {
    val artistKey: String get() = MusicForYouRule.artistKey(artist, channelId)
}

/** A song played in the app (or saved to a playlist), as the rule sees it. */
data class SongPlay(
    val videoId: String,
    val channelId: String,
    val artist: String,
    /** Part of the song listened to, 0..1. */
    val listenedFraction: Float,
    val lastPlayedAt: Long,
    val playCount: Int,
    val liked: Boolean,
    val saved: Boolean
) {
    val artistKey: String get() = MusicForYouRule.artistKey(artist, channelId)
}

/** The candidate songs, by source (see [MusicForYouRule]). */
data class MusicCandidates(
    /** Seed song id -> YouTube Music's queue after it. */
    val upNext: Map<String, List<MusicSong>> = emptyMap(),
    /** Seed song id -> YouTube's related list for it. */
    val related: Map<String, List<MusicSong>> = emptyMap(),
    /** Recent uploads of the artists listened to most. */
    val artistUploads: List<MusicSong> = emptyList()
)

/** What the user did, as the rule needs it. */
data class MusicSignals(
    val plays: List<SongPlay>,
    /** Artist key -> number of the artist's songs the user disliked. */
    val dislikedArtists: Map<String, Int> = emptyMap(),
    val impressions: Map<String, Impression> = emptyMap(),
    /** "Not interested", disliked. */
    val excludedIds: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet()
)

/** A candidate with its score (after the impression discount). */
data class ScoredSong(
    val song: MusicSong,
    val score: Double,
    val sources: Set<MusicSource>,
    val impressions: Int
)

/**
 * The Music tab's "For you" rule (songs), as agreed (version 1). It follows Home's rule
 * ([ForYouRule], version 2) with music sources:
 *
 * Seeds: songs played in the app in the last 60 days for at least half their length, liked or
 * saved; strength = listened fraction x 0.5^(days ago / 30) x (1 + ln(play count)) + 1 if liked
 * + 0.5 if saved, doubled if played in the last 3 hours. The 30 strongest form the seed pool.
 *
 * Candidates: A. YouTube Music's queue ("Up next") after a seed song, kept from the page when
 * the song was played (its radio, album or playlist; no requests to YouTube); B. YouTube's
 * related list of a seed: each refresh fetches it for up to 4 pool seeds that have none from the
 * last 24 hours, drawn at random in proportion to strength (3 more when loading more); C. uploads
 * (60 days) of the 5 artists with the highest listening score (sum over their songs of listened
 * fraction x 0.5^(days ago / 30) x (1 + ln(play count)), at least 1.0), from their channels' RSS
 * feeds.
 *
 * Score = A + B + C + popularity + artist, where A = 2 x a / max a, a = sum of the strengths of
 * the seeds whose queue lists the song; B = 1.5 x b / max b, b = the same for related lists;
 * C = 1.5 x 0.5^(age in days / 14); popularity = 0.25 x min(1, log10(1 + views) / 7); artist =
 * listening score (+ 1 per liked song, at most 3) scaled 0..1 by the highest, - 0.5 per disliked
 * song of the artist, kept within -1..1. A song shown on screen in k lists of the last 3 days
 * without being opened scores x 0.6^k. Songs scoring 0 or less are left out.
 *
 * Removed: songs played for at least half their length in the last 14 days (they are in Speed
 * dial), "Not interested", disliked, hidden channels, Shorts, anything shorter than 60 s or longer
 * than 15 min (mixes, compilations, talk).
 *
 * Order: random, weighted by score (score / 0.35 + a Gumbel(0, 1) draw, highest first, which
 * picks songs in turn with probability proportional to e^(score / 0.35)); no artist more than
 * twice in any 20 consecutive songs. Up to 200 songs; the Music tab shows 30 and 20 more each
 * time the end is reached.
 *
 * Artists are matched by name ("- Topic" removed, case ignored): YouTube Music's queue names the
 * artist, while played songs often belong to the artist's auto-generated "- Topic" channel.
 */
object MusicForYouRule {
    const val LISTENED_FRACTION = 0.5f
    const val MIN_SONG_SEC = 60
    const val MAX_SONG_SEC = 15 * 60

    // Seeds and fetching
    const val SEED_DAYS = 60
    const val SEED_POOL = 30
    const val SESSION_HOURS = 3
    const val SESSION_BOOST = 2.0
    const val RELATED_FETCHES_PER_REFRESH = 4
    const val RELATED_FETCHES_PER_EXTEND = 3
    const val ARTISTS = 5
    const val ARTIST_MIN = 1.0
    const val RECENT_DAYS = 14

    // Scores
    const val UP_NEXT_WEIGHT = 2.0
    const val RELATED_WEIGHT = 1.5
    const val UPLOAD_WEIGHT = 1.5
    const val UPLOAD_DAYS = 60
    const val UPLOAD_HALF_LIFE_DAYS = 14.0
    const val LISTEN_HALF_LIFE_DAYS = 30.0
    const val IMPRESSION_FACTOR = 0.6
    const val IMPRESSION_DAYS = 3
    const val DISLIKE_PENALTY = 0.5

    // Order
    const val TEMPERATURE = 0.35
    const val ARTIST_WINDOW = 20
    const val MAX_PER_WINDOW = 2
    const val MAX_LIST = 200
    const val FIRST_PAGE = 30
    const val PAGE = 20

    private const val HOUR_MS = 60L * 60 * 1000
    private const val DAY_MS = 24 * HOUR_MS
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

    /** Key for "same artist": the name without " - Topic", lower case; else the channel id. */
    fun artistKey(artist: String, channelId: String): String {
        val name = artist.trim().removeSuffix(" - Topic").trim().lowercase()
        return name.ifBlank { channelId }
    }

    fun decay(ageMs: Long, halfLifeDays: Double): Double =
        0.5.pow(ageMs.coerceAtLeast(0L).toDouble() / DAY_MS / halfLifeDays)

    /** Listened fraction x 0.5^(days ago / 30) x (1 + ln(play count)). */
    fun listenWeight(p: SongPlay, now: Long): Double =
        p.listenedFraction * decay(now - p.lastPlayedAt, LISTEN_HALF_LIFE_DAYS) * (1.0 + ln(p.playCount.coerceAtLeast(1).toDouble()))

    // ---- seeds ----------------------------------------------------------------------------

    fun seedStrength(p: SongPlay, now: Long): Double {
        val base = listenWeight(p, now) + (if (p.liked) 1.0 else 0.0) + (if (p.saved) 0.5 else 0.0)
        return if (now - p.lastPlayedAt <= SESSION_HOURS * HOUR_MS) base * SESSION_BOOST else base
    }

    /** The seed pool, strongest first. */
    fun seedPool(plays: List<SongPlay>, now: Long): List<Pair<SongPlay, Double>> =
        plays.asSequence()
            .filter { now - it.lastPlayedAt <= SEED_DAYS * DAY_MS }
            .filter { it.listenedFraction >= LISTENED_FRACTION || it.liked || it.saved }
            .distinctBy { it.videoId }
            .map { it to seedStrength(it, now) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(SEED_POOL)
            .toList()

    /**
     * Up to [count] pool seeds without a recent related list ([hasFresh]), drawn without
     * replacement with probability proportional to strength (key ln(strength) + Gumbel).
     */
    fun seedsToFetch(
        pool: List<Pair<SongPlay, Double>>,
        hasFresh: (String) -> Boolean,
        count: Int,
        random: Random
    ): List<SongPlay> =
        pool.filter { !hasFresh(it.first.videoId) }
            .map { it.first to ln(it.second) + gumbel(random) }
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }

    // ---- artists ----------------------------------------------------------------------------

    /** Listening scores by artist key (no like bonus). */
    fun listeningScores(plays: List<SongPlay>, now: Long): Map<String, Double> {
        val scores = HashMap<String, Double>()
        for (p in plays) {
            val key = p.artistKey
            if (key.isBlank()) continue
            scores[key] = (scores[key] ?: 0.0) + listenWeight(p, now)
        }
        return scores
    }

    /** Artist scores within -1..1 (see the rule). */
    fun artistAffinity(plays: List<SongPlay>, disliked: Map<String, Int>, now: Long): Map<String, Double> {
        val raw = HashMap(listeningScores(plays, now))
        plays.filter { it.liked && it.artistKey.isNotBlank() }
            .groupingBy { it.artistKey }.eachCount()
            .forEach { (key, n) -> raw[key] = (raw[key] ?: 0.0) + min(n, 3) }
        val max = raw.values.maxOrNull()?.takeIf { it > 0 }
        val out = HashMap<String, Double>()
        if (max != null) raw.forEach { (key, v) -> out[key] = (v / max).coerceIn(0.0, 1.0) }
        disliked.forEach { (key, n) -> out[key] = ((out[key] ?: 0.0) - DISLIKE_PENALTY * n).coerceIn(-1.0, 1.0) }
        return out
    }

    /**
     * Channels whose uploads are followed (source C): the artists with the highest listening
     * scores, at least [ARTIST_MIN], neither hidden nor disliked; each by the channel id most of
     * their played songs have.
     */
    fun followedArtists(
        plays: List<SongPlay>,
        hidden: Set<String>,
        disliked: Map<String, Int>,
        now: Long,
        count: Int = ARTISTS
    ): List<String> {
        val channels = HashMap<String, HashMap<String, Int>>()
        for (p in plays) {
            if (!CHANNEL_ID.matches(p.channelId)) continue
            channels.getOrPut(p.artistKey) { HashMap() }.merge(p.channelId, 1, Int::plus)
        }
        return listeningScores(plays, now).entries
            .filter { (key, s) -> s >= ARTIST_MIN && (disliked[key] ?: 0) == 0 && channels.containsKey(key) }
            .sortedByDescending { it.value }
            .mapNotNull { (key, _) -> channels[key]?.maxByOrNull { it.value }?.key }
            .filter { it !in hidden }
            .distinct()
            .take(count)
    }

    // ---- scoring --------------------------------------------------------------------------

    /** Songs played for at least half their length in the last [RECENT_DAYS] days. */
    fun recentlyPlayed(plays: List<SongPlay>, now: Long): Set<String> =
        plays.filter { it.listenedFraction >= LISTENED_FRACTION && now - it.lastPlayedAt <= RECENT_DAYS * DAY_MS }
            .mapTo(HashSet()) { it.videoId }

    fun isExcluded(s: MusicSong, excludedIds: Set<String>, hiddenChannels: Set<String>): Boolean =
        s.videoId in excludedIds ||
            (s.channelId.isNotBlank() && s.channelId in hiddenChannels) ||
            s.isShort || s.title.contains("#shorts", ignoreCase = true) ||
            (s.durationSec in 1 until MIN_SONG_SEC) || s.durationSec > MAX_SONG_SEC

    /** Scores every candidate (merged across sources); see the rule above. */
    fun score(
        candidates: MusicCandidates,
        signals: MusicSignals,
        seeds: List<Pair<SongPlay, Double>>,
        now: Long
    ): List<ScoredSong> {
        val excluded = signals.excludedIds + recentlyPlayed(signals.plays, now)
        val artist = artistAffinity(signals.plays, signals.dislikedArtists, now)
        val parts = HashMap<String, DoubleArray>()
        val songs = HashMap<String, MusicSong>()
        fun add(s: MusicSong, source: MusicSource, value: Double) {
            if (value <= 0) return
            songs[s.videoId] = richer(songs[s.videoId], s)
            parts.getOrPut(s.videoId) { DoubleArray(MusicSource.entries.size) }[source.ordinal] += value
        }
        val strength = seeds.associate { it.first.videoId to it.second }

        // A and B: lists after / related to the seeds, weighted by seed strength.
        fun fromSeedLists(lists: Map<String, List<MusicSong>>, source: MusicSource, weight: Double) {
            val sum = HashMap<String, Double>()
            val listed = HashMap<String, MusicSong>()
            for ((seedId, list) in lists) {
                val w = strength[seedId] ?: continue
                for (s in list.distinctBy { it.videoId }) {
                    if (s.videoId == seedId || isExcluded(s, excluded, signals.hiddenChannels)) continue
                    sum[s.videoId] = (sum[s.videoId] ?: 0.0) + w
                    listed[s.videoId] = richer(listed[s.videoId], s)
                }
            }
            val max = sum.values.maxOrNull()?.takeIf { it > 0 } ?: return
            sum.forEach { (id, v) -> add(listed.getValue(id), source, weight * v / max) }
        }
        fromSeedLists(candidates.upNext, MusicSource.UP_NEXT, UP_NEXT_WEIGHT)
        fromSeedLists(candidates.related, MusicSource.RELATED, RELATED_WEIGHT)

        // C. uploads of the artists listened to most.
        for (s in candidates.artistUploads.distinctBy { it.videoId }) {
            if (isExcluded(s, excluded, signals.hiddenChannels)) continue
            if (s.publishedAt > 0 && now - s.publishedAt <= UPLOAD_DAYS * DAY_MS) {
                add(s, MusicSource.ARTIST, UPLOAD_WEIGHT * decay(now - s.publishedAt, UPLOAD_HALF_LIFE_DAYS))
            }
        }

        val impressionWindow = IMPRESSION_DAYS * DAY_MS
        return parts.mapNotNull { (id, p) ->
            val s = songs.getValue(id)
            val popularity = 0.25 * min(1.0, log10(1.0 + s.viewCount.coerceAtLeast(0L)) / 7.0)
            val raw = p.sum() + popularity + (artist[s.artistKey] ?: 0.0)
            val k = signals.impressions[id]?.takeIf { now - it.lastAt <= impressionWindow }?.count ?: 0
            val discounted = if (raw > 0) raw * IMPRESSION_FACTOR.pow(k) else raw
            if (discounted <= 0) return@mapNotNull null
            val sources = MusicSource.entries.filter { p[it.ordinal] > 0 }.toSet()
            val main = MusicSource.entries.maxBy { p[it.ordinal] }
            ScoredSong(s.copy(source = main), discounted, sources, k)
        }
    }

    /** Of two copies of a song from different lists, the one that knows more. */
    private fun richer(a: MusicSong?, b: MusicSong): MusicSong {
        if (a == null) return b
        fun known(s: MusicSong) = (if (s.durationSec > 0) 1 else 0) + (if (s.viewCount > 0) 1 else 0) +
            (if (s.publishedAt > 0) 1 else 0) + (if (s.channelId.isNotBlank()) 1 else 0) + (if (s.artist.isNotBlank()) 1 else 0)
        return if (known(b) > known(a)) b else a
    }

    // ---- order ----------------------------------------------------------------------------

    fun gumbel(random: Random): Double = ForYouRule.gumbel(random)

    /**
     * Orders [scored] as the rule says. [listed] are songs already in the list (left out; the
     * last of them count for the artist spacing).
     */
    fun order(
        scored: List<ScoredSong>,
        random: Random,
        listed: List<MusicSong> = emptyList(),
        max: Int = MAX_LIST
    ): List<MusicSong> {
        val listedIds = listed.mapTo(HashSet()) { it.videoId }
        val queue = ArrayDeque(
            scored.filter { it.song.videoId !in listedIds }
                .map { it to it.score / TEMPERATURE + gumbel(random) }
                .sortedByDescending { it.second }
                .map { it.first.song }
        )
        val out = ArrayList<MusicSong>()
        val placed = ArrayList<MusicSong>(listed.takeLast(ARTIST_WINDOW - 1))
        fun allowed(s: MusicSong): Boolean {
            val key = s.artistKey
            if (key.isBlank()) return true
            val window = placed.subList((placed.size - (ARTIST_WINDOW - 1)).coerceAtLeast(0), placed.size)
            return window.count { it.artistKey == key } < MAX_PER_WINDOW
        }
        while (out.size < max && queue.isNotEmpty()) {
            val i = queue.indexOfFirst { allowed(it) }
            // Nothing fits the spacing (only one or two artists left): take the next anyway.
            val next = if (i >= 0) queue.removeAt(i) else queue.removeFirst()
            out.add(next)
            placed.add(next)
        }
        return out
    }
}
