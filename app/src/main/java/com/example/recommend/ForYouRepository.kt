package com.example.recommend

import android.content.Context
import com.example.data.BuiltInPlaylist
import com.example.data.FeedbackType
import com.example.data.StreamShieldDatabase
import com.example.data.SubscriptionFeedClient
import com.example.download.NewPipeSupport
import com.example.download.RelatedVideo
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
 * Builds the Home tab's "For you" list with [ForYouRule]: reads the local library, fetches
 * YouTube lists (related videos, related of related, searches: NewPipe Extractor; uploads of
 * watched channels: RSS) within a small budget per refresh, caches them, and keeps the result,
 * the cache and what was shown in a file so Home shows the last list at once on the next start.
 *
 * A list (generation) is ordered once per refresh; Home shows it page by page and asks for
 * more ([extend]) at the end. Videos that were on screen ([markSeen]) count as shown when the
 * next list is made.
 */
class ForYouRepository(private val context: Context) {

    data class State(
        val items: List<ForYouVideo> = emptyList(),
        val refreshedAt: Long = 0L,
        /** Increases with every new list (Home starts again at the top). */
        val generation: Int = 0,
        val refreshing: Boolean = false,
        /** Loading more at the end of the list. */
        val extending: Boolean = false,
        /** Nothing more to add until the next refresh. */
        val endReached: Boolean = false,
        /** A short note when part of the list could not be loaded. */
        val note: String = ""
    )

    private data class Cached(val at: Long, val items: List<ForYouVideo>)

    /** What the rule needs from the library, read once per refresh. */
    private class Inputs(
        val user: UserSignals,
        val uploads: List<ForYouVideo>,
        val pool: List<Pair<WatchSignal, Double>>,
        val searches: List<String>,
        val follow: List<String>
    )

    /** Fetch results of one refresh, for the note. */
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
    private val related = HashMap<String, Cached>()
    private val deep = HashMap<String, Cached>()
    private val searches = HashMap<String, Cached>()
    private val channels = HashMap<String, Cached>()
    private val impressions = HashMap<String, Impression>()
    /** Videos on screen in the current list. */
    private val seen = HashSet<String>()

    @Volatile
    private var loaded = false

    /** Reads the saved state (once). */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        synchronized(this@ForYouRepository) {
            if (loaded) return@withContext
            loaded = true
        }
        try {
            if (!file.exists()) return@withContext
            val root = JSONObject(file.readText())
            synchronized(this@ForYouRepository) {
                readCache(root.optJSONObject("related"), related)
                readCache(root.optJSONObject("deep"), deep)
                readCache(root.optJSONObject("searches"), searches)
                readCache(root.optJSONObject("channels"), channels)
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
                    items = videosFrom(root.optJSONArray("items")),
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

    /**
     * Makes a new list ([force]: even if the current one is recent). [starred] are the starred
     * channel ids. Returns at once if a refresh is already running.
     */
    suspend fun refresh(starred: Set<String>, force: Boolean) = withContext(Dispatchers.IO) {
        load()
        val now = System.currentTimeMillis()
        if (!force && !isStale(now)) return@withContext
        if (!mutex.tryLock()) return@withContext
        try {
            _state.update { it.copy(refreshing = true) }
            commitImpressions(now)
            val inputs = readInputs(starred, now)
            val random = Random(now)
            val tally = Tally()

            val seeds = ForYouRule.seedsToFetch(inputs.pool, { fresh(related, it, now, LIST_TTL_MS) }, ForYouRule.SEED_FETCHES_PER_REFRESH, random)
            fetchInto(related, seeds.map { it.videoId }, now, tally) { id -> relatedOf(id, ForYouSource.RELATED) }
            fetchInto(channels, inputs.follow.filter { !fresh(channels, it, now, CHANNEL_TTL_MS) }, now, null) { id -> uploadsOf(id) }
            inputs.searches.firstOrNull { !fresh(searches, it, now, LIST_TTL_MS) }?.let { q ->
                fetchInto(searches, listOf(q), now, null) { query -> resultsOf(query) }
            }
            val scored = scoreWithDeep(inputs, now, tally)
            val items = ForYouRule.order(scored, now, random)

            prune(inputs, now)
            val note = noteFor(tally, inputs, now, items.isEmpty())
            _state.value = State(
                items = items,
                refreshedAt = now,
                generation = _state.value.generation + 1,
                note = note
            )
            save()
        } catch (_: Exception) {
            _state.update { it.copy(refreshing = false, note = "Could not update this list. Pull down to try again.") }
        } finally {
            _state.update { it.copy(refreshing = false) }
            mutex.unlock()
        }
    }

    /** End of the list reached: fetch a few more lists and add the videos not yet listed. */
    suspend fun extend(starred: Set<String>) = withContext(Dispatchers.IO) {
        load()
        val current = _state.value
        if (current.items.isEmpty() || current.endReached || current.refreshing) return@withContext
        if (!mutex.tryLock()) return@withContext
        try {
            _state.update { it.copy(extending = true) }
            val now = System.currentTimeMillis()
            val inputs = readInputs(starred, now)
            val random = Random(now)
            val seeds = ForYouRule.seedsToFetch(inputs.pool, { fresh(related, it, now, LIST_TTL_MS) }, ForYouRule.SEED_FETCHES_PER_EXTEND, random)
            fetchInto(related, seeds.map { it.videoId }, now, null) { id -> relatedOf(id, ForYouSource.RELATED) }
            inputs.searches.firstOrNull { !fresh(searches, it, now, LIST_TTL_MS) }?.let { q ->
                fetchInto(searches, listOf(q), now, null) { query -> resultsOf(query) }
            }
            val scored = scoreWithDeep(inputs, now, null)
            val listed = _state.value.items
            val more = ForYouRule.order(scored, now, random, listed = listed, pin = false, max = EXTEND_MAX)
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

    /** Videos that were on screen in Home (counted as shown when the next list is made). */
    fun markSeen(videoIds: Collection<String>) {
        synchronized(this) { seen.addAll(videoIds) }
    }

    /** A video opened from Home: it was not skipped. */
    fun opened(videoId: String) {
        synchronized(this) {
            seen.remove(videoId)
            impressions.remove(videoId)
        }
    }

    /** Writes what was shown (e.g. when the app goes to the background). */
    suspend fun persist() = withContext(Dispatchers.IO) {
        if (loaded) save()
    }

    /** "Not interested" or a dislike: drop it now (the rule leaves it out from then on). */
    suspend fun remove(videoId: String) = withContext(Dispatchers.IO) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.videoId == videoId }) }
        save()
    }

    // ---- building ---------------------------------------------------------------------------

    private fun commitImpressions(now: Long) {
        synchronized(this) {
            val window = ForYouRule.IMPRESSION_DAYS * DAY_MS
            impressions.entries.removeAll { now - it.value.lastAt > window }
            for (id in seen) {
                val count = impressions[id]?.count ?: 0
                impressions[id] = Impression(count + 1, now)
            }
            seen.clear()
        }
    }

    private suspend fun readInputs(starred: Set<String>, now: Long): Inputs {
        val dao = StreamShieldDatabase.getInstance(context).libraryDao()
        val videos = dao.allVideos().associateBy { it.videoId }
        val entries = dao.allWatchEntries().associateBy { it.videoId }
        val playlists = dao.allPlaylists()
        val playlistItems = dao.allPlaylistItems()
        val likedId = playlists.firstOrNull { it.builtIn == BuiltInPlaylist.LIKED.name }?.id
        val liked = playlistItems.filter { it.playlistId == likedId }.map { it.videoId }.toSet()
        val saved = playlistItems.filter { it.playlistId != likedId }.map { it.videoId }.toSet()
        val addedAt = playlistItems.groupBy { it.videoId }.mapValues { (_, list) -> list.maxOf { it.addedAt } }
        val subscribed = dao.allSubscriptions().map { it.channelId }.toSet()
        val feedback = dao.allFeedback()
        fun targets(type: FeedbackType) = feedback.filter { it.type == type.name }.map { it.targetId }.toSet()
        val notInterested = targets(FeedbackType.NOT_INTERESTED)
        val hiddenChannels = targets(FeedbackType.HIDE_CHANNEL)
        val disliked = targets(FeedbackType.DISLIKED)

        val watches = (entries.keys + liked + saved).mapNotNull { id ->
            val v = videos[id] ?: return@mapNotNull null
            val e = entries[id]
            WatchSignal(
                videoId = id,
                channelId = v.channelId,
                channelName = v.channelName,
                watchedFraction = e?.watchedFraction ?: 0f,
                lastWatchedAt = maxOf(e?.lastWatchedAt ?: 0L, addedAt[id] ?: 0L),
                isMusic = v.isMusic || e?.isMusic == true,
                liked = id in liked,
                saved = id in saved
            )
        }.filter { !it.isMusic }
        val watched = entries.values.filter { it.watchedFraction >= ForYouRule.WATCHED_FRACTION }.map { it.videoId }.toSet()
        val dislikedChannels = HashMap<String, Int>()
        for (id in disliked) {
            val v = videos[id] ?: continue
            val key = v.channelId.ifBlank { v.channelName.trim().lowercase() }
            if (key.isNotBlank()) dislikedChannels[key] = (dislikedChannels[key] ?: 0) + 1
        }
        val user = UserSignals(
            watches = watches,
            subscribed = subscribed,
            starred = starred,
            dislikedChannels = dislikedChannels,
            impressions = synchronized(this) { HashMap(impressions) },
            excludedIds = watched + notInterested + disliked,
            hiddenChannels = hiddenChannels
        )
        val uploads = dao.allFeedItems().map {
            ForYouVideo(it.videoId, it.title, it.channelId, it.channelName, 0, it.viewCount, it.publishedAt, "", it.isShort, ForYouSource.SUBSCRIPTION)
        }
        // Seeds: recently watched / liked / saved videos; a new user's subscriptions instead.
        var pool = ForYouRule.seedPool(watches, now)
        if (pool.isEmpty()) {
            pool = uploads.filter { it.publishedAt > 0 && !it.isShort }
                .sortedByDescending { it.publishedAt }
                .distinctBy { it.channelKey }
                .take(NEW_USER_SEEDS)
                .map { WatchSignal(it.videoId, it.channelId, it.channelName, 1f, it.publishedAt, false, false, false) to 1.0 }
        }
        val recentSearches = dao.allSearches()
            .filter { now - it.lastUsedAt <= ForYouRule.SEARCH_DAYS * DAY_MS && it.query.isNotBlank() }
            .sortedByDescending { it.lastUsedAt }
            .take(ForYouRule.SEARCH_QUERIES)
            .map { it.query }
        val follow = ForYouRule.watchedChannels(watches, subscribed, hiddenChannels, dislikedChannels, now)
        return Inputs(user, uploads, pool, recentSearches, follow)
    }

    /** Scores, fetches related-of-related lists for the best related videos, scores again. */
    private suspend fun scoreWithDeep(inputs: Inputs, now: Long, tally: Tally?): List<ScoredVideo> {
        val first = ForYouRule.score(candidates(inputs, now), inputs.user, inputs.pool, now)
        val parents = ForYouRule.deepParents(first, { fresh(deep, it, now, LIST_TTL_MS) })
        if (parents.isEmpty()) return first
        fetchInto(deep, parents, now, tally) { id -> relatedOf(id, ForYouSource.RELATED_DEEP) }
        return ForYouRule.score(candidates(inputs, now), inputs.user, inputs.pool, now)
    }

    private fun candidates(inputs: Inputs, now: Long): ForYouCandidates = synchronized(this) {
        val keep = KEEP_LISTS_MS
        fun usable(c: Cached?) = c != null && now - c.at <= keep
        ForYouCandidates(
            uploads = inputs.uploads,
            related = inputs.pool.mapNotNull { (seed, _) -> related[seed.videoId]?.takeIf(::usable)?.let { seed.videoId to it.items } }.toMap(),
            deep = deep.filterValues(::usable).mapValues { it.value.items },
            channelUploads = inputs.follow.flatMap { id -> channels[id]?.takeIf(::usable)?.items.orEmpty() },
            searches = inputs.searches.mapNotNull { q -> searches[q]?.takeIf(::usable)?.let { q to it.items } }
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
        load: suspend (String) -> List<ForYouVideo>
    ) {
        if (keys.isEmpty()) return
        val semaphore = Semaphore(PARALLEL_FETCHES)
        coroutineScope {
            keys.distinct().map { key ->
                async {
                    semaphore.withPermit {
                        synchronized(this@ForYouRepository) { tally?.let { it.attempted++ } }
                        try {
                            val list = load(key)
                            synchronized(this@ForYouRepository) { cache[key] = Cached(now, list) }
                        } catch (e: Exception) {
                            synchronized(this@ForYouRepository) {
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

    private fun relatedOf(videoId: String, source: ForYouSource): List<ForYouVideo> =
        NewPipeSupport.relatedVideos(NewPipeSupport.streamInfo(videoId)).map { it.toForYou(source) }

    private fun resultsOf(query: String): List<ForYouVideo> =
        NewPipeSupport.searchVideos(query).take(SEARCH_RESULTS).map { it.toForYou(ForYouSource.SEARCH) }

    private suspend fun uploadsOf(channelId: String): List<ForYouVideo> {
        val feed = feeds.fetch(channelId) ?: throw IllegalStateException("feed unavailable")
        return feed.items.map {
            ForYouVideo(it.videoId, it.title, it.channelId, it.channelName.ifBlank { feed.channelName }, 0, it.viewCount, it.publishedAt, "", it.isShort, ForYouSource.CHANNEL)
        }
    }

    private fun RelatedVideo.toForYou(source: ForYouSource) = ForYouVideo(
        videoId = videoId,
        title = title,
        channelId = channelId,
        channelName = channelName,
        durationSec = durationSec,
        viewCount = viewCount,
        publishedAt = uploadedAt,
        publishedText = uploadedText,
        isShort = isShort,
        source = source
    )

    /** Keeps the cache to what the next refreshes can use. */
    private fun prune(inputs: Inputs, now: Long) = synchronized(this) {
        val poolIds = inputs.pool.map { it.first.videoId }.toSet()
        related.entries.removeAll { it.key !in poolIds || now - it.value.at > KEEP_LISTS_MS }
        deep.entries.removeAll { now - it.value.at > KEEP_LISTS_MS }
        if (deep.size > MAX_DEEP_LISTS) {
            deep.entries.sortedBy { it.value.at }.take(deep.size - MAX_DEEP_LISTS).map { it.key }.forEach { deep.remove(it) }
        }
        val searchKeep = inputs.searches.toSet()
        searches.entries.removeAll { it.key !in searchKeep || now - it.value.at > KEEP_LISTS_MS }
        val followKeep = inputs.follow.toSet()
        channels.entries.removeAll { it.key !in followKeep }
    }

    private fun noteFor(tally: Tally, inputs: Inputs, now: Long, empty: Boolean): String {
        if (tally.attempted == 0 || tally.failed < tally.attempted) return ""
        val haveLists = synchronized(this) { inputs.pool.any { related[it.first.videoId] != null } }
        return when {
            tally.captcha -> "YouTube asked for a check, so new related videos could not be loaded."
            empty || !haveLists -> "Related videos could not be loaded from YouTube right now."
            else -> ""
        }
    }

    // ---- storage ----------------------------------------------------------------------------

    private fun save() {
        try {
            val root = JSONObject()
                .put("v", 2)
                .put("at", _state.value.refreshedAt)
                .put("gen", _state.value.generation)
                .put("items", videosJson(_state.value.items))
            synchronized(this) {
                root.put("related", cacheJson(related))
                root.put("deep", cacheJson(deep))
                root.put("searches", cacheJson(searches))
                root.put("channels", cacheJson(channels))
                root.put("impressions", JSONObject().apply {
                    impressions.forEach { (id, i) -> put(id, JSONArray().put(i.count).put(i.lastAt)) }
                })
                root.put("seen", JSONArray(seen.toList()))
            }
            // persist() can run while a refresh saves; both write the same temporary file.
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
        cache.forEach { (key, c) -> put(key, JSONObject().put("at", c.at).put("items", videosJson(c.items))) }
    }

    private fun readCache(o: JSONObject?, into: HashMap<String, Cached>) {
        o ?: return
        o.keys().forEach { key ->
            val entry = o.optJSONObject(key) ?: return@forEach
            into[key] = Cached(entry.optLong("at"), videosFrom(entry.optJSONArray("items")))
        }
    }

    private fun videosJson(list: List<ForYouVideo>): JSONArray = JSONArray().apply {
        list.forEach { v ->
            put(
                JSONObject()
                    .put("id", v.videoId)
                    .put("title", v.title)
                    .put("channelId", v.channelId)
                    .put("channelName", v.channelName)
                    .put("dur", v.durationSec)
                    .put("views", v.viewCount)
                    .put("pub", v.publishedAt)
                    .put("pubText", v.publishedText)
                    .put("short", v.isShort)
                    .put("src", v.source.name)
            )
        }
    }

    private fun videosFrom(array: JSONArray?): List<ForYouVideo> {
        if (array == null) return emptyList()
        val out = ArrayList<ForYouVideo>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                ForYouVideo(
                    videoId = id,
                    title = o.optString("title"),
                    channelId = o.optString("channelId"),
                    channelName = o.optString("channelName"),
                    durationSec = o.optInt("dur"),
                    viewCount = o.optLong("views"),
                    publishedAt = o.optLong("pub"),
                    publishedText = o.optString("pubText"),
                    isShort = o.optBoolean("short"),
                    source = ForYouSource.entries.firstOrNull { it.name == o.optString("src") } ?: ForYouSource.RELATED
                )
            )
        }
        return out
    }

    companion object {
        private const val FILE_NAME = "for_you.json"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** Opening Home makes a new list when the current one is older than this. */
        const val REFRESH_AFTER_MS = 4L * 60 * 60 * 1000
        /** A list (related, related of related, search) is fetched again after this. */
        private const val LIST_TTL_MS = DAY_MS
        /** A watched channel's uploads are fetched again after this. */
        private const val CHANNEL_TTL_MS = 6L * 60 * 60 * 1000
        /** Lists older than this are no longer used. */
        private const val KEEP_LISTS_MS = 7 * DAY_MS
        private const val MAX_DEEP_LISTS = 30
        private const val SEARCH_RESULTS = 20
        private const val EXTEND_MAX = 100
        private const val PARALLEL_FETCHES = 3
        private const val NEW_USER_SEEDS = 5
    }
}
