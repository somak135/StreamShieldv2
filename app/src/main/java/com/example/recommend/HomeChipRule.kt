package com.example.recommend

import kotlin.math.exp
import kotlin.math.ln

/**
 * YouTube's own topic pages (its Explore destinations) that return videos without an account.
 * Checked signed out on 3 Nov 2026 (US, English): Music 36 videos, Gaming 25, Live 63, News 204,
 * Podcasts 24 episodes, Sports 30, Learning 103, Fashion & Beauty 83. [params] as YouTube's own
 * links resolve them; [webPath] is the page on m.youtube.com; [categories] are the YouTube
 * categories (of videos you watch) that count for the page.
 */
enum class YtPage(
    val label: String,
    val browseId: String,
    val params: String,
    val webPath: String,
    val categories: Set<String>,
    /** Always a chip; the others only when you watch their categories (always in Explore). */
    val always: Boolean
) {
    MUSIC("Music", "UC-9-kyTW8ZkZNDHQJ6FgpwQ", "", "/channel/UC-9-kyTW8ZkZNDHQJ6FgpwQ", setOf("Music"), true),
    GAMING("Gaming", "UCOpNcN46UbXVtpKMrmU4Abg", "EgC4AQCSAwDyBgQKAjIA", "/gaming", setOf("Gaming"), true),
    LIVE("Live", "UC4R8DWoMoI7CAwX8_LjQHig", "", "/channel/UC4R8DWoMoI7CAwX8_LjQHig", emptySet(), true),
    NEWS("News", "UCYfdidRxbB8Qhf0Nx7ioOYw", "", "/channel/UCYfdidRxbB8Qhf0Nx7ioOYw", setOf("News & Politics"), true),
    PODCASTS("Podcasts", "FEpodcasts_destination", "qgcCCAE=", "/podcasts", emptySet(), true),
    SPORTS("Sports", "UCEgdi0XIXXZ-qJOFPf4JSKw", "", "/channel/UCEgdi0XIXXZ-qJOFPf4JSKw", setOf("Sports"), false),
    LEARNING("Learning", "UCtFRv9O2AHqOZjjynzrv-xg", "", "/channel/UCtFRv9O2AHqOZjjynzrv-xg", setOf("Education", "Science & Technology"), false),
    FASHION("Fashion & Beauty", "UCrpQ4p1Ql_hG8rKXIKM1MOQ", "", "/channel/UCrpQ4p1Ql_hG8rKXIKM1MOQ", emptySet(), false)
}

/** An Explore entry: a topic page shown in the app like a chip, or a page that opens on YouTube. */
data class ExploreEntry(val label: String, val page: YtPage? = null, val path: String = "")

/** A watched video's topic: its YouTube category and the creator's keywords (from the page). */
data class TopicVideo(
    val videoId: String,
    /** Channel id, else the channel name in lower case. */
    val channelKey: String,
    val channelName: String,
    val category: String,
    val keywords: List<String>,
    /** When it was watched (ms). */
    val at: Long
)

enum class HomeChipKind { ALL, SUBS, PAGE, TOPIC, RECENT, WATCHED, NEW }

/** A chip in Home's row. [label] is shown; [page] for [HomeChipKind.PAGE]. */
data class HomeChip(val kind: HomeChipKind, val label: String, val page: YtPage? = null) {
    val key: String
        get() = when (kind) {
            HomeChipKind.TOPIC -> "topic:" + label.lowercase()
            HomeChipKind.PAGE -> "page:" + (page?.name ?: label)
            else -> kind.name.lowercase()
        }
}

/** A video in a topic or page chip's list. [meta]: "13K views · 2 days ago" as YouTube words it. */
data class ChipVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int,
    val meta: String,
    val live: Boolean = false,
    val isShort: Boolean = false
)

/** A history entry as the Watched chip needs it. */
data class PastVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int,
    val watchedFraction: Float,
    val playCount: Int,
    val lastWatchedAt: Long,
    val isMusic: Boolean
)

/**
 * Home's chip row, as agreed (November 2026), like the YouTube app's but from what the app knows
 * without an account:
 *
 * All (For you) · Subs · the topic pages you watch most · your topics · the other topic pages ·
 * Recently uploaded · Watched · New to you.
 *
 * - Topic pages ([YtPage]): Music, Gaming, Live, News and Podcasts always; Sports and Learning
 *   when at least [PAGE_SHARE] of the videos you watched (recent ones count more) are in their
 *   YouTube categories, from at least [MIN_PAGE_VIDEOS] videos. Those come first, by share.
 * - Your topics: keywords creators gave the videos you watched, used by at least
 *   [MIN_TOPIC_VIDEOS] videos from at least [MIN_TOPIC_CHANNELS] channels (not the channel's own
 *   name, not generic words, at most 4 words); weight halves every [HALF_LIFE_DAYS] days, more
 *   channels count more. A keyword inside a stronger one (or the other way round) is left out.
 *   Up to [MAX_SEARCH_TOPICS] of your searches of the last [SEARCH_DAYS] days (up to 3 words)
 *   are added; [MAX_TOPICS] topics in all.
 * - A topic's list ([topicFeed]): For you videos with the topic in their title and YouTube's
 *   results for it, taking turns; no Shorts, nothing you watched or disliked.
 * - Watched ([watched]): videos (not music) you finished or played twice, not in the last day,
 *   most played first.
 */
object HomeChipRule {
    const val MAX_TOPICS = 8
    const val MAX_SEARCH_TOPICS = 2
    const val MIN_TOPIC_VIDEOS = 3
    const val MIN_TOPIC_CHANNELS = 2
    const val HALF_LIFE_DAYS = 30.0
    const val SEARCH_DAYS = 14
    const val PAGE_SHARE = 0.10
    const val MIN_PAGE_VIDEOS = 3
    const val MAX_WATCHED = 60
    const val MAX_FEED = 80
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Explore: YouTube's topic pages (in the app) and the pages without plain videos (on YouTube). */
    val EXPLORE: List<ExploreEntry> = listOf(
        ExploreEntry("Music", YtPage.MUSIC),
        ExploreEntry("Gaming", YtPage.GAMING),
        ExploreEntry("Live", YtPage.LIVE),
        ExploreEntry("News", YtPage.NEWS),
        ExploreEntry("Sports", YtPage.SPORTS),
        ExploreEntry("Learning", YtPage.LEARNING),
        ExploreEntry("Podcasts", YtPage.PODCASTS),
        ExploreEntry("Fashion & Beauty", YtPage.FASHION),
        ExploreEntry("Movies & TV", path = "/feed/storefront"),
        // Courses is left out: on m.youtube.com its link shows the Learning page.
        ExploreEntry("Charts", path = "https://charts.youtube.com/")
    )

    /** Generic keywords that do not make a topic. */
    private val STOP = setOf(
        "video", "videos", "youtube", "youtuber", "funny", "new", "official", "trending", "viral", "shorts",
        "short", "vlog", "vlogs", "music", "song", "songs", "hd", "4k", "live", "full", "episode", "best",
        "top", "news", "today", "latest", "update", "reaction", "review", "explained", "how to", "tutorial",
        "the", "and", "for", "you", "with", "subscribe", "like", "channel", "fyp", "foryou", "for you"
    )
    private val YEAR = Regex("^(19|20)\\d\\d$")
    private val SPACES = Regex("\\s+")

    fun normalize(keyword: String): String = keyword.trim().removePrefix("#").replace(SPACES, " ").lowercase()

    private fun usable(tag: String, channelName: String): Boolean {
        if (tag.length < 3 || tag.length > 32 || tag.split(' ').size > 4) return false
        if (tag in STOP || YEAR.matches(tag) || tag.all { it.isDigit() || it == ' ' }) return false
        if (tag.startsWith("http") || tag.contains("www.")) return false
        val channel = normalize(channelName)
        return channel.isBlank() || tag.replace(" ", "") != channel.replace(" ", "")
    }

    private fun recency(at: Long, now: Long): Double =
        exp(-ln(2.0) * (now - at).coerceAtLeast(0L).toDouble() / (HALF_LIFE_DAYS * DAY_MS))

    /** One contains the other as whole words ("minecraft" and "minecraft survival"). */
    private fun overlaps(a: String, b: String): Boolean = " $a ".contains(" $b ") || " $b ".contains(" $a ")

    /** The topics, strongest first, as shown (the creators' spelling if it has capitals). */
    fun topics(videos: List<TopicVideo>, searches: List<Pair<String, Long>>, now: Long, max: Int = MAX_TOPICS): List<String> {
        class Tally {
            var weight = 0.0
            val videos = HashSet<String>()
            val channels = HashSet<String>()
            val spellings = HashMap<String, Int>()
        }
        val tallies = HashMap<String, Tally>()
        for (v in videos.distinctBy { it.videoId }) {
            val w = recency(v.at, now)
            val seen = HashSet<String>()
            for (raw in v.keywords) {
                val tag = normalize(raw)
                if (!seen.add(tag) || !usable(tag, v.channelName)) continue
                val t = tallies.getOrPut(tag) { Tally() }
                t.weight += w
                t.videos += v.videoId
                t.channels += v.channelKey
                val spelling = raw.trim().removePrefix("#").replace(SPACES, " ")
                t.spellings[spelling] = (t.spellings[spelling] ?: 0) + 1
            }
        }
        val searched = searches
            .filter { now - it.second <= SEARCH_DAYS * DAY_MS }
            .sortedByDescending { it.second }
            .map { normalize(it.first) }
            .filter { it.length in 3..32 && it.split(' ').size <= 3 && it !in STOP && !it.contains("://") }
            .distinct()
            .take(MAX_SEARCH_TOPICS)
        val ranked = tallies.entries
            .filter { it.value.videos.size >= MIN_TOPIC_VIDEOS && it.value.channels.size >= MIN_TOPIC_CHANNELS }
            .sortedWith(
                compareByDescending<Map.Entry<String, Tally>> { it.value.weight * (1.0 + ln(it.value.channels.size.toDouble())) }
                    .thenBy { it.key }
            )
        val chosen = ArrayList<String>()
        val shown = HashMap<String, String>()
        val roomForTags = (max - searched.size).coerceAtLeast(0)
        for ((tag, tally) in ranked) {
            if (chosen.size >= roomForTags) break
            if (chosen.any { overlaps(it, tag) }) continue
            chosen += tag
            val spelling = tally.spellings.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })?.key
            shown[tag] = if (spelling != null && spelling.any { it.isUpperCase() }) spelling else label(tag)
        }
        for (q in searched) {
            if (chosen.size >= max) break
            if (chosen.any { overlaps(it, q) }) continue
            chosen += q
            shown[q] = label(q)
        }
        return chosen.map { shown[it] ?: label(it) }
    }

    private fun label(tag: String): String = tag.replaceFirstChar { it.titlecase() }

    /** Share of the watched videos (recent ones count more) in each page's categories, and counts. */
    fun pageShares(videos: List<TopicVideo>, now: Long): Map<YtPage, Pair<Double, Int>> {
        val distinct = videos.distinctBy { it.videoId }.filter { it.category.isNotBlank() }
        val total = distinct.sumOf { recency(it.at, now) }
        if (total <= 0.0) return emptyMap()
        return YtPage.entries.associateWith { page ->
            val inPage = distinct.filter { it.category in page.categories }
            (inPage.sumOf { recency(it.at, now) } / total) to inPage.size
        }
    }

    /** The chip row (see the rule above). */
    fun chips(videos: List<TopicVideo>, searches: List<Pair<String, Long>>, now: Long): List<HomeChip> {
        val shares = pageShares(videos, now)
        val yours = YtPage.entries
            .filter { p -> shares[p]?.let { it.first >= PAGE_SHARE && it.second >= MIN_PAGE_VIDEOS } == true }
            .sortedByDescending { shares[it]?.first ?: 0.0 }
        val others = YtPage.entries.filter { it.always && it !in yours }
        val pageLabels = YtPage.entries.map { it.label.lowercase() }.toSet()
        val topics = topics(videos, searches, now).filter { it.lowercase() !in pageLabels }
        return listOf(HomeChip(HomeChipKind.ALL, "All"), HomeChip(HomeChipKind.SUBS, "Subs")) +
            yours.map { HomeChip(HomeChipKind.PAGE, it.label, it) } +
            topics.map { HomeChip(HomeChipKind.TOPIC, it) } +
            others.map { HomeChip(HomeChipKind.PAGE, it.label, it) } +
            listOf(
                HomeChip(HomeChipKind.RECENT, "Recently uploaded"),
                HomeChip(HomeChipKind.WATCHED, "Watched"),
                HomeChip(HomeChipKind.NEW, "New to you")
            )
    }

    /** Lower case, letters and digits only, single spaces. */
    private fun words(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(SPACES, " ").trim()

    /** [title] names [topic] (whole words, any case and punctuation). */
    fun matchesTopic(title: String, topic: String): Boolean {
        val t = words(topic)
        return t.isNotEmpty() && " ${words(title)} ".contains(" $t ")
    }

    /** A topic's list: For you matches and search results taking turns (see the rule). */
    fun topicFeed(topic: String, pool: List<ChipVideo>, search: List<ChipVideo>, exclude: Set<String>, max: Int = MAX_FEED): List<ChipVideo> {
        fun ok(v: ChipVideo) = !v.isShort && v.videoId !in exclude
        val a = pool.filter { ok(it) && matchesTopic(it.title, topic) }
        val b = search.filter { ok(it) }
        val out = LinkedHashMap<String, ChipVideo>()
        var i = 0
        while ((i < a.size || i < b.size) && out.size < max) {
            a.getOrNull(i)?.let { out.putIfAbsent(it.videoId, it) }
            if (out.size < max) b.getOrNull(i)?.let { out.putIfAbsent(it.videoId, it) }
            i++
        }
        return out.values.toList()
    }

    /** The Watched chip (see the rule). */
    fun watched(history: List<PastVideo>, now: Long, max: Int = MAX_WATCHED): List<PastVideo> =
        history
            .filter { !it.isMusic && it.durationSec > 60 && (it.watchedFraction >= 0.8f || it.playCount >= 2) && now - it.lastWatchedAt >= DAY_MS }
            .sortedWith(compareByDescending<PastVideo> { it.playCount }.thenByDescending { it.lastWatchedAt })
            .take(max)

    /** "1:02:03" / "10:07" → seconds; 0 if not a duration. */
    fun parseDuration(text: String): Int {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return 0
        var total = 0
        for (p in parts) total = total * 60 + (p.toIntOrNull() ?: return 0)
        return total
    }
}
