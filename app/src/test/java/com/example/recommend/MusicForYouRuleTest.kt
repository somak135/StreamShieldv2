package com.example.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.random.Random

/** The Music tab's "For you" rule, version 1 (pure Kotlin, no Android). */
class MusicForYouRuleTest {
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_000L * day

    private fun song(
        id: String,
        artist: String = "Artist $id",
        channel: String = "",
        source: MusicSource = MusicSource.UP_NEXT,
        duration: Int = 200,
        daysOld: Long = 0,
        views: Long = 0,
        title: String = "Song $id"
    ) = MusicSong(id, title, artist, channel, duration, views, if (daysOld > 0) now - daysOld * day else 0L, false, source)

    private fun play(
        id: String,
        artist: String = "Artist $id",
        channel: String = "",
        fraction: Float = 1f,
        hoursAgo: Long = 24 * 20,
        plays: Int = 1,
        liked: Boolean = false,
        saved: Boolean = false
    ) = SongPlay(id, channel, artist, fraction, now - hoursAgo * hour, plays, liked, saved)

    private fun scoreOf(list: List<ScoredSong>, id: String) = list.firstOrNull { it.song.videoId == id }?.score

    // ---- artists and seeds -------------------------------------------------------------------

    @Test
    fun `artist key ignores the Topic suffix and case`() {
        assertEquals("coldplay", MusicForYouRule.artistKey("Coldplay - Topic", "UCx"))
        assertEquals("coldplay", MusicForYouRule.artistKey(" COLDPLAY ", "UCy"))
        assertEquals("UCz", MusicForYouRule.artistKey("", "UCz"))
    }

    @Test
    fun `seed strength grows with play count, likes, saves and a recent session`() {
        val once = MusicForYouRule.seedStrength(play("a", hoursAgo = 30 * 24), now)
        assertEquals(0.5, once, 1e-9)
        val often = MusicForYouRule.seedStrength(play("a", hoursAgo = 30 * 24, plays = 4), now)
        assertEquals(0.5 * (1 + ln(4.0)), often, 1e-9)
        val liked = MusicForYouRule.seedStrength(play("a", hoursAgo = 30 * 24, liked = true, saved = true), now)
        assertEquals(0.5 + 1.0 + 0.5, liked, 1e-9)
        val justNow = MusicForYouRule.seedStrength(play("a", hoursAgo = 1), now)
        assertTrue(justNow > 1.9 && justNow <= 2.0)
    }

    @Test
    fun `seed pool keeps recent songs listened to at least half, liked or saved`() {
        val pool = MusicForYouRule.seedPool(
            listOf(
                play("full"),
                play("half", fraction = 0.5f),
                play("skipped", fraction = 0.2f),
                play("skippedLiked", fraction = 0.2f, liked = true),
                play("old", hoursAgo = 61 * 24)
            ),
            now
        ).map { it.first.videoId }.toSet()
        assertEquals(setOf("full", "half", "skippedLiked"), pool)
    }

    @Test
    fun `seeds to fetch skip seeds with a fresh related list`() {
        val pool = (1..10).map { play("s$it") to 1.0 }
        val picked = MusicForYouRule.seedsToFetch(pool, { it == "s1" || it == "s2" }, 4, Random(1))
        assertEquals(4, picked.size)
        assertTrue(picked.none { it.videoId == "s1" || it.videoId == "s2" })
    }

    @Test
    fun `followed artists use the channel of their played songs and a minimum score`() {
        val topic = "UC" + "t".repeat(22)
        val other = "UC" + "o".repeat(22)
        val plays = listOf(
            play("a", "Band - Topic", topic, hoursAgo = 1, plays = 3),
            play("b", "Band - Topic", topic, hoursAgo = 2),
            play("c", "Quiet", other, fraction = 0.5f, hoursAgo = 24 * 50)
        )
        assertEquals(listOf(topic), MusicForYouRule.followedArtists(plays, emptySet(), emptyMap(), now))
        assertTrue(MusicForYouRule.followedArtists(plays, emptySet(), mapOf("band" to 1), now).isEmpty())
        assertTrue(MusicForYouRule.followedArtists(plays, setOf(topic), emptyMap(), now).isEmpty())
    }

    // ---- scoring -----------------------------------------------------------------------------

    @Test
    fun `up next counts more than related, weighted by seed strength`() {
        val seeds = listOf(play("seedA") to 2.0, play("seedB") to 1.0)
        val candidates = MusicCandidates(
            upNext = mapOf("seedA" to listOf(song("x"), song("both")), "seedB" to listOf(song("both"))),
            related = mapOf("seedA" to listOf(song("r", source = MusicSource.RELATED)))
        )
        val scored = MusicForYouRule.score(candidates, MusicSignals(emptyList()), seeds, now)
        assertEquals(2.0, scoreOf(scored, "both")!!, 1e-9)            // (2 + 1) / 3 x 2
        assertEquals(2.0 * 2 / 3, scoreOf(scored, "x")!!, 1e-9)
        assertEquals(1.5, scoreOf(scored, "r")!!, 1e-9)
        assertEquals(setOf(MusicSource.UP_NEXT), scored.first { it.song.videoId == "x" }.sources)
    }

    @Test
    fun `recently played, not interested, seeds and odd lengths are left out`() {
        val seeds = listOf(play("seed") to 1.0)
        val candidates = MusicCandidates(
            upNext = mapOf(
                "seed" to listOf(
                    song("seed"), song("recent"), song("olderPlay"), song("notInterested"),
                    song("long", duration = 16 * 60), song("short", duration = 30), song("unknown", duration = 0), song("ok")
                )
            )
        )
        val signals = MusicSignals(
            plays = listOf(play("recent", hoursAgo = 5 * 24), play("olderPlay", hoursAgo = 20 * 24)),
            excludedIds = setOf("notInterested")
        )
        val ids = MusicForYouRule.score(candidates, signals, seeds, now).map { it.song.videoId }.toSet()
        assertEquals(setOf("olderPlay", "unknown", "ok"), ids)
    }

    @Test
    fun `artist affinity joins Topic channels and artist names`() {
        val seeds = listOf(play("seed") to 1.0)
        val candidates = MusicCandidates(upNext = mapOf("seed" to listOf(song("fav", artist = "Band"), song("new", artist = "Stranger"))))
        val signals = MusicSignals(plays = listOf(play("old1", "Band - Topic", "UCt", hoursAgo = 30 * 24)))
        val scored = MusicForYouRule.score(candidates, signals, seeds, now)
        assertEquals(1.0, scoreOf(scored, "fav")!! - scoreOf(scored, "new")!!, 1e-9)
    }

    @Test
    fun `a disliked artist scores lower`() {
        val seeds = listOf(play("seed") to 1.0)
        val candidates = MusicCandidates(upNext = mapOf("seed" to listOf(song("a", artist = "Loud"), song("b", artist = "Calm"))))
        val scored = MusicForYouRule.score(candidates, MusicSignals(emptyList(), dislikedArtists = mapOf("loud" to 1)), seeds, now)
        assertEquals(0.5, scoreOf(scored, "b")!! - scoreOf(scored, "a")!!, 1e-9)
    }

    @Test
    fun `artist uploads count for 60 days, halving every 14`() {
        val candidates = MusicCandidates(
            artistUploads = listOf(
                song("today", source = MusicSource.ARTIST, daysOld = 0),
                song("twoWeeks", source = MusicSource.ARTIST, daysOld = 14),
                song("tooOld", source = MusicSource.ARTIST, daysOld = 61)
            ).map { if (it.videoId == "today") it.copy(publishedAt = now) else it }
        )
        val scored = MusicForYouRule.score(candidates, MusicSignals(emptyList()), emptyList(), now)
        assertEquals(1.5, scoreOf(scored, "today")!!, 1e-9)
        assertEquals(0.75, scoreOf(scored, "twoWeeks")!!, 1e-9)
        assertEquals(null, scoreOf(scored, "tooOld"))
    }

    @Test
    fun `songs shown without being opened fade`() {
        val seeds = listOf(play("seed") to 1.0)
        val candidates = MusicCandidates(upNext = mapOf("seed" to listOf(song("a"), song("b"))))
        val signals = MusicSignals(emptyList(), impressions = mapOf("a" to Impression(2, now - day), "b" to Impression(2, now - 4 * day)))
        val scored = MusicForYouRule.score(candidates, signals, seeds, now)
        assertEquals(2.0 * 0.36, scoreOf(scored, "a")!!, 1e-6)
        assertEquals(2.0, scoreOf(scored, "b")!!, 1e-9)
    }

    @Test
    fun `popularity adds up to a quarter point`() {
        val seeds = listOf(play("seed") to 1.0)
        val candidates = MusicCandidates(related = mapOf("seed" to listOf(song("hit", views = 10_000_000L), song("niche"))))
        val scored = MusicForYouRule.score(candidates, MusicSignals(emptyList()), seeds, now)
        assertEquals(0.25, scoreOf(scored, "hit")!! - scoreOf(scored, "niche")!!, 1e-6)
    }

    // ---- order ---------------------------------------------------------------------------------

    @Test
    fun `at most two songs of an artist in any twenty`() {
        val scored = (1..4).map { ScoredSong(song("a$it", artist = "Same"), 5.0, setOf(MusicSource.UP_NEXT), 0) } +
            (1..40).map { ScoredSong(song("o$it"), 1.0, setOf(MusicSource.UP_NEXT), 0) }
        val list = MusicForYouRule.order(scored, Random(7))
        assertEquals(44, list.size)
        assertEquals("Same", list[0].artist)                     // the best songs still lead
        for (start in 0..list.size - 20) {
            val window = list.subList(start, start + 20)
            assertTrue(window.count { it.artist == "Same" } <= 2)
        }
    }

    @Test
    fun `higher scores tend to come first and listed songs are left out`() {
        val scored = listOf(
            ScoredSong(song("top"), 4.0, setOf(MusicSource.UP_NEXT), 0),
            ScoredSong(song("low"), 0.1, setOf(MusicSource.UP_NEXT), 0)
        )
        var topFirst = 0
        repeat(200) { seed -> if (MusicForYouRule.order(scored, Random(seed)).first().videoId == "top") topFirst++ }
        assertTrue(topFirst > 190)
        val more = MusicForYouRule.order(scored, Random(1), listed = listOf(song("top")))
        assertEquals(listOf("low"), more.map { it.videoId })
        assertFalse(more.any { it.videoId == "top" })
    }
}
