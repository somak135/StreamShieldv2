package com.example.recommend

import android.content.Context
import com.example.data.BuiltInPlaylist
import com.example.data.FeedbackType
import com.example.data.StreamShieldDatabase
import com.example.data.SubscriptionFeedClient
import com.example.download.NewPipeSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import kotlin.random.Random

/**
 * Builds the Music tab's "For you" list with [MusicForYouRule]: reads the songs played in the
 * app, keeps YouTube Music's queues as the page reports them ([addQueue], no requests), fetches a
 * few related lists (NewPipe Extractor) and the followed artists' uploads (RSS) per refresh, and
 * keeps the result, the lists and what was shown in a file, so the tab shows the last list at once.
 * Works like [ForYouRepository] (Home).
 */
class MusicForYouRepository(private val context: Context) {

    data class State(
        val items: List<MusicSong> = emptyList(),
        val refreshedAt: Long = 0L,
        /** Increases with every new list (the tab starts again at the top). */
        val generation: Int = 0,
        val refreshing: Boolean = false,
        /** Loading more at the end of the list. */
        val extending: Boolean = false,
        /** Nothing more to add until the next refresh. */
        val endReached: Boolean = false,
        /** A short note when part of the list could not be loaded. */
        val note: String = ""
    )

    private data class Cached(val at: Long, val items: List<MusicSong>)

    private class Inputs(
        val signals: MusicSignals,
        val pool: List<Pair<SongPlay, Double>>,
        val follow: List<String>
    )

    private class Tally {
        var attempted = 0
        var failed = 0
        var captcha = false
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private val fileLock = Any()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val feeds = SubscriptionFeedClient()

    // Guarded by `this`.
    private val upNext = HashMap<String, Cached>()
    private val related = HashMap<String, Cached>()
    private val artists = HashMap<String, Cached>()
    private val impressions = HashMap<String, Impression>()
    /** Songs on screen in the current list. */
    private val seen = HashSet<String>()

    @Volatile
    private var loaded = false
    /** Queues were added since the last save. */
    @Volatile
    private var dirty = false

    /** Reads the saved state (once). */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        synchronized(this@MusicForYouRepository) {
            if (loaded) return@withContext
            loaded = true
        }
        try {
            if (!file.exists()) return@withContext
            val root = JSONObject(file.readText())
            synchronized(this@MusicForYouRepository) {
                readCache(root.optJSONObject("upNext"), upNext)
                readCache(root.optJSONObject("related"), related)
                readCache(root.optJSONObject("artists"), artists)
                root.optJSONObject("impressions")?.let { o ->
                    o.keys().forEach { id ->
                        val a = o.optJSONArray(id) ?: return@forEach
                        impressions[id] = Impression(a.optInt(0), a.optLong(1))
                    }
                }
                root.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen.add(a.optString(i)) }
            }
            _state.update {
                it.copy(
                    items = songsFrom(root.optJSONArray("items")),
                    refreshedAt = root.optLong("at"),
                    generation = root.optInt("gen")
                )
            }
        } catch (_: Exception) {
            // A damaged file only means starting from an empty list.
        }
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        now - _state.value.refreshedAt > REFRESH_AFTER_MS

    /** YouTube Music's queue after [seed], as the page reported it (used by the next lists). */
    fun addQueue(seed: String, items: List<MusicSong>) {
        if (seed.isBlank() || items.isEmpty()) return
        synchronized(this) {
            upNext[seed] = Cached(System.currentTimeMillis(), items.take(MAX_QUEUE))
            if (upNext.size > MAX_QUEUES) {
                upNext.entries.sortedBy { it.value.at }.take(upNext.size - MAX_QUEUES).map { it.key }.forEach { upNext.remove(it) }
            }
            dirty = true
        }
    }

    /** Makes a new list ([force]: even if the current one is recent). */
    suspend fun refresh(force: Boolean) = withContext(Dispatchers.IO) {
        load()
        val now = System.currentTimeMillis()
        if (!force && !isStale(now) && _state.value.items.isNotEmpty()) return@withContext
        if (!mutex.tryLock()) return@withContext
        try {
            _state.update { it.copy(refreshing = true) }
            commitImpressions(now)
            val inputs = readInputs(now)
            val random = Random(now)
            val tally = Tally()
            val seeds = MusicForYouRule.seedsToFetch(inputs.pool, { fresh(related, it, now, LIST_TTL_MS) }, MusicForYouRule.RELATED_FETCHES_PER_REFRESH, random)
            fetchInto(related, seeds.map { it.videoId }, now, tally) { id -> relatedOf(id) }
            fetchInto(artists, inputs.follow.filter { !fresh(artists, it, now, ARTIST_TTL_MS) }, now, null) { id -> uploadsOf(id) }
            val items = MusicForYouRule.order(MusicForYouRule.score(candidates(inputs, now), inputs.signals, inputs.pool, now), random)
            prune(inputs, now)
            _state.value = State(
                items = items,
                refreshedAt = now,
                generation = _state.value.generation + 1,
                note = noteFor(tally, items.isEmpty())
            )
            save()
        } catch (_: Exception) {
            _state.update { it.copy(refreshing = false, note = "Could not update this list. Pull down to try again.") }
        } finally {
            _state.update { it.copy(refreshing = false) }
            mutex.unlock()
        }
    }

    /** End of the list reached: fetch a few more related lists and add the songs not yet listed. */
    suspend fun extend() = withContext(Dispatchers.IO) {
        load()
        val current = _state.value
        if (current.items.isEmpty() || current.endReached || current.refreshing) return@withContext
        if (!mutex.tryLock()) return@withContext
        try {
            _state.update { it.copy(extending = true) }
            val now = System.currentTimeMillis()
            val inputs = readInputs(now)
            val random = Random(now)
            val seeds = MusicForYouRule.seedsToFetch(inputs.pool, { fresh(related, it, now, LIST_TTL_MS) }, MusicForYouRule.RELATED_FETCHES_PER_EXTEND, random)
            fetchInto(related, seeds.map { it.videoId }, now, null) { id -> relatedOf(id) }
            val listed = _state.value.items
            val scored = MusicForYouRule.score(candidates(inputs, now), inputs.signals, inputs.pool, now)
            val more = MusicForYouRule.order(scored, random, listed = listed, max = EXTEND_MAX)
            prune(inputs, now)
            _state.update { it.copy(items = listed + more, endReached = more.isEmpty()) }
            save()
        } catch (_: Exception) {
            _state.update { it.copy(endReached = true) }
        } finally {
            _state.update { it.copy(extending = false) }
            mutex.unlock()
        }
    }

    /** Songs that were on screen (counted as shown when the next list is made). */
    fun markSeen(videoIds: Collection<String>) {
        synchronized(this) { seen.addAll(videoIds) }
    }

    /** A song opened from the list: it was not skipped. */
    fun opened(videoId: String) {
        synchronized(this) {
            seen.remove(videoId)
            impressions.remove(videoId)
        }
    }

    /** Writes what was shown and the queues kept (e.g. when the app goes to the background). */
    suspend fun persist() = withContext(Dispatchers.IO) {
        if (loaded || dirty) {
            load()
            save()
        }
    }

    /** "Not interested" or a dislike: drop it now (the rule leaves it out from then on). */
    suspend fun remove(videoId: String) = withContext(Dispatchers.IO) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.videoId == videoId }) }
        save()
    }

    // ---- building ---------------------------------------------------------------------------

    private fun commitImpressions(now: Long) {
        synchronized(this) {
            val window = MusicForYouRule.IMPRESSION_DAYS * DAY_MS
            impressions.entries.removeAll { now - it.value.lastAt > window }
            for (id in seen) {
                val count = impressions[id]?.count ?: 0
                impressions[id] = Impression(count + 1, now)
            }
            seen.clear()
        }
    }

    private suspend fun readInputs(now: Long): Inputs {
        val dao = StreamShieldDatabase.getInstance(context).libraryDao()
        val videos = dao.allVideos().associateBy { it.videoId }
        val entries = dao.allWatchEntries().associateBy { it.videoId }
        val playlists = dao.allPlaylists()
        val playlistItems = dao.allPlaylistItems()
        val likedId = playlists.firstOrNull { it.builtIn == BuiltInPlaylist.LIKED.name }?.id
        val liked = playlistItems.filter { it.playlistId == likedId }.map { it.videoId }.toSet()
        val saved = playlistItems.filter { it.playlistId != likedId }.map { it.videoId }.toSet()
        val addedAt = playlistItems.groupBy { it.videoId }.mapValues { (_, list) -> list.maxOf { it.addedAt } }
        val feedback = dao.allFeedback()
        fun targets(type: FeedbackType) = feedback.filter { it.type == type.name }.map { it.targetId }.toSet()
        val notInterested = targets(FeedbackType.NOT_INTERESTED)
        val hiddenChannels = targets(FeedbackType.HIDE_CHANNEL)
        val disliked = targets(FeedbackType.DISLIKED)

        val plays = (entries.keys + liked + saved).mapNotNull { id ->
            val v = videos[id] ?: return@mapNotNull null
            val e = entries[id]
            if (!v.isMusic && e?.isMusic != true) return@mapNotNull null
            SongPlay(
                videoId = id,
                channelId = v.channelId,
                artist = v.channelName,
                listenedFraction = e?.watchedFraction ?: 0f,
                lastPlayedAt = maxOf(e?.lastWatchedAt ?: 0L, addedAt[id] ?: 0L),
                playCount = e?.playCount ?: 0,
                liked = id in liked,
                saved = id in saved
            )
        }
        val dislikedArtists = HashMap<String, Int>()
        for (id in disliked) {
            val v = videos[id] ?: continue
            val key = MusicForYouRule.artistKey(v.channelName, v.channelId)
            if (key.isNotBlank()) dislikedArtists[key] = (dislikedArtists[key] ?: 0) + 1
        }
        val signals = MusicSignals(
            plays = plays,
            dislikedArtists = dislikedArtists,
            impressions = synchronized(this) { HashMap(impressions) },
            excludedIds = notInterested + disliked,
            hiddenChannels = hiddenChannels
        )
        val pool = MusicForYouRule.seedPool(plays, now)
        val follow = MusicForYouRule.followedArtists(plays, hiddenChannels, dislikedArtists, now)
        return Inputs(signals, pool, follow)
    }

    private fun candidates(inputs: Inputs, now: Long): MusicCandidates = synchronized(this) {
        fun usable(c: Cached?, keep: Long) = c != null && now - c.at <= keep
        val seeds = inputs.pool.map { it.first.videoId }
        MusicCandidates(
            upNext = seeds.mapNotNull { id -> upNext[id]?.takeIf { usable(it, KEEP_QUEUES_MS) }?.let { id to it.items } }.toMap(),
            related = seeds.mapNotNull { id -> related[id]?.takeIf { usable(it, KEEP_LISTS_MS) }?.let { id to it.items } }.toMap(),
            artistUploads = inputs.follow.flatMap { id -> artists[id]?.takeIf { usable(it, KEEP_LISTS_MS) }?.items.orEmpty() }
        )
    }

    private fun fresh(cache: HashMap<String, Cached>, key: String, now: Long, ttl: Long): Boolean =
        synchronized(this) { cache[key]?.let { now - it.at < ttl } ?: false }

    /** Fetches lists in parallel (3 at a time); a failure keeps the older list. */
    private suspend fun fetchInto(
        cache: HashMap<String, Cached>,
        keys: List<String>,
        now: Long,
        tally: Tally?,
        load: suspend (String) -> List<MusicSong>
    ) {
        if (keys.isEmpty()) return
        val semaphore = Semaphore(PARALLEL_FETCHES)
        coroutineScope {
            keys.distinct().map { key ->
                async {
                    semaphore.withPermit {
                        synchronized(this@MusicForYouRepository) { tally?.let { it.attempted++ } }
                        try {
                            val list = load(key)
                            synchronized(this@MusicForYouRepository) { cache[key] = Cached(now, list) }
                        } catch (e: Exception) {
                            synchronized(this@MusicForYouRepository) {
                                tally?.let {
                                    it.failed++
                                    if (e is ReCaptchaException) it.captcha = true
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun relatedOf(videoId: String): List<MusicSong> =
        NewPipeSupport.relatedVideos(NewPipeSupport.streamInfo(videoId)).map {
            MusicSong(it.videoId, it.title, it.channelName, it.channelId, it.durationSec, it.viewCount, 0L, it.isShort, MusicSource.RELATED)
        }

    private suspend fun uploadsOf(channelId: String): List<MusicSong> {
        val feed = feeds.fetch(channelId) ?: throw IllegalStateException("feed unavailable")
        return feed.items.map {
            MusicSong(it.videoId, it.title, it.channelName.ifBlank { feed.channelName }, it.channelId, 0, it.viewCount, it.publishedAt, it.isShort, MusicSource.ARTIST)
        }
    }

    /** Keeps the lists to what the next refreshes can use. */
    private fun prune(inputs: Inputs, now: Long) = synchronized(this) {
        val poolIds = inputs.pool.map { it.first.videoId }.toSet()
        related.entries.removeAll { it.key !in poolIds || now - it.value.at > KEEP_LISTS_MS }
        upNext.entries.removeAll { now - it.value.at > KEEP_QUEUES_MS }
        val followKeep = inputs.follow.toSet()
        artists.entries.removeAll { it.key !in followKeep }
    }

    private fun noteFor(tally: Tally, empty: Boolean): String {
        if (tally.attempted == 0 || tally.failed < tally.attempted || !empty) return ""
        return if (tally.captcha) "YouTube asked for a check, so related songs could not be loaded."
        else "Related songs could not be loaded from YouTube right now."
    }

    // ---- storage ----------------------------------------------------------------------------

    private fun save() {
        try {
            val root = JSONObject()
                .put("v", 1)
                .put("at", _state.value.refreshedAt)
                .put("gen", _state.value.generation)
                .put("items", songsJson(_state.value.items))
            synchronized(this) {
                root.put("upNext", cacheJson(upNext))
                root.put("related", cacheJson(related))
                root.put("artists", cacheJson(artists))
                root.put("impressions", JSONObject().apply {
                    impressions.forEach { (id, i) -> put(id, JSONArray().put(i.count).put(i.lastAt)) }
                })
                root.put("seen", JSONArray(seen.toList()))
                dirty = false
            }
            synchronized(fileLock) {
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(file)) {
                    file.writeText(root.toString())
                    tmp.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun cacheJson(cache: HashMap<String, Cached>): JSONObject = JSONObject().apply {
        cache.forEach { (key, c) -> put(key, JSONObject().put("at", c.at).put("items", songsJson(c.items))) }
    }

    private fun readCache(o: JSONObject?, into: HashMap<String, Cached>) {
        o ?: return
        o.keys().forEach { key ->
            val entry = o.optJSONObject(key) ?: return@forEach
            val at = entry.optLong("at")
            // A queue the page reported before the file was read is newer.
            if ((into[key]?.at ?: -1L) < at) into[key] = Cached(at, songsFrom(entry.optJSONArray("items")))
        }
    }

    private fun songsJson(list: List<MusicSong>): JSONArray = JSONArray().apply {
        list.forEach { s ->
            put(
                JSONObject()
                    .put("id", s.videoId)
                    .put("title", s.title)
                    .put("artist", s.artist)
                    .put("channelId", s.channelId)
                    .put("dur", s.durationSec)
                    .put("views", s.viewCount)
                    .put("pub", s.publishedAt)
                    .put("short", s.isShort)
                    .put("src", s.source.name)
            )
        }
    }

    private fun songsFrom(array: JSONArray?): List<MusicSong> {
        if (array == null) return emptyList()
        val out = ArrayList<MusicSong>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                MusicSong(
                    videoId = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    channelId = o.optString("channelId"),
                    durationSec = o.optInt("dur"),
                    viewCount = o.optLong("views"),
                    publishedAt = o.optLong("pub"),
                    isShort = o.optBoolean("short"),
                    source = MusicSource.entries.firstOrNull { it.name == o.optString("src") } ?: MusicSource.UP_NEXT
                )
            )
        }
        return out
    }

    companion object {
        private const val FILE_NAME = "music_for_you.json"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** Opening the Music tab makes a new list when the current one is older than this. */
        const val REFRESH_AFTER_MS = 4L * 60 * 60 * 1000
        /** A related list is fetched again after this. */
        private const val LIST_TTL_MS = DAY_MS
        /** A followed artist's uploads are fetched again after this. */
        private const val ARTIST_TTL_MS = 6L * 60 * 60 * 1000
        /** Related lists and uploads older than this are no longer used. */
        private const val KEEP_LISTS_MS = 7 * DAY_MS
        /** YouTube Music queues are kept this long (they cost nothing to get). */
        private const val KEEP_QUEUES_MS = 30 * DAY_MS
        private const val MAX_QUEUES = 100
        private const val MAX_QUEUE = 50
        private const val EXTEND_MAX = 100
        private const val PARALLEL_FETCHES = 3

        /** Parses a queue reported by the page: {seed, list, items: [{id, title, artist, channelId, dur}]}. */
        fun parseQueue(json: String): Pair<String, List<MusicSong>>? = try {
            val o = JSONObject(json)
            val seed = o.optString("seed")
            val array = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id")
                if (!VIDEO_ID.matches(id)) return@mapNotNull null
                MusicSong(id, item.optString("title"), item.optString("artist"), item.optString("channelId"), item.optInt("dur"), 0L, 0L, false, MusicSource.UP_NEXT)
            }
            if (VIDEO_ID.matches(seed) && items.isNotEmpty()) seed to items else null
        } catch (_: Exception) {
            null
        }

        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
