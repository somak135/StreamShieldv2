package com.example.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's chip row (pure Kotlin, no Android). */
class HomeChipRuleTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun v(id: String, channel: String, category: String = "Entertainment", daysAgo: Int = 1, vararg kw: String) =
        TopicVideo(id, channel.lowercase(), channel, category, kw.toList(), now - daysAgo * day)

    @Test
    fun `a keyword becomes a topic with 3 videos from 2 channels`() {
        val videos = listOf(
            v("a", "Chan A", kw = arrayOf("Minecraft", "Chan A")),
            v("b", "Chan A", kw = arrayOf("minecraft")),
            v("c", "Chan B", kw = arrayOf("#Minecraft", "redstone")),
            v("d", "Chan B", kw = arrayOf("redstone")),
            v("e", "Chan B", kw = arrayOf("redstone"))
        )
        // "redstone": 3 videos but one channel; the channel's own name is never a topic.
        assertEquals(listOf("Minecraft"), HomeChipRule.topics(videos, emptyList(), now))
    }

    @Test
    fun `generic words, years and numbers are not topics`() {
        val videos = (1..4).map { v("v$it", "Chan $it", kw = arrayOf("funny", "2026", "Live", "12345", "how to")) }
        assertEquals(emptyList<String>(), HomeChipRule.topics(videos, emptyList(), now))
    }

    @Test
    fun `a keyword inside a stronger one is left out, and recent videos count more`() {
        val videos = (1..6).map { v("m$it", "Chan ${it % 3}", daysAgo = 2, kw = arrayOf("cricket", "cricket highlights")) } +
            (1..4).map { v("o$it", "Old ${it % 2}", daysAgo = 200, kw = arrayOf("chess")) } +
            (1..3).map { v("n$it", "New ${it % 2}", daysAgo = 1, kw = arrayOf("linear algebra")) }
        val topics = HomeChipRule.topics(videos, emptyList(), now)
        assertTrue(topics.toString(), topics.count { it.lowercase().startsWith("cricket") } == 1)
        assertTrue(topics.toString(), topics.indexOf("Linear algebra") < topics.indexOf("Chess"))
    }

    @Test
    fun `recent short searches are added, at most two`() {
        val searches = listOf("lofi beats" to now - day, "how do i fix my sink drain" to now - day, "chess openings" to now - 2 * day,
            "f1" to now - day, "old search" to now - 30 * day, "rust lang" to now - 3 * day)
        assertEquals(listOf("Lofi beats", "Chess openings"), HomeChipRule.topics(emptyList(), searches, now))
    }

    @Test
    fun `chip row order, and pages you watch come first`() {
        val videos = (1..6).map { v("g$it", "G$it", category = "Gaming") } +
            (1..4).map { v("s$it", "S$it", category = "Sports") } +
            (1..2).map { v("e$it", "E$it", category = "Education") } +
            (1..8).map { v("x$it", "X$it", category = "Entertainment") }
        val chips = HomeChipRule.chips(videos, emptyList(), now)
        assertEquals(
            listOf("All", "Subs", "Gaming", "Sports", "Music", "Live", "News", "Podcasts", "Recently uploaded", "Watched", "New to you"),
            chips.map { it.label }
        )
        // Learning: only 2 videos (10 % of 20 but fewer than 3), so not shown.
        assertFalse(chips.any { it.page == YtPage.LEARNING })
        assertEquals("page:GAMING", chips[2].key)
    }

    @Test
    fun `topics sit between your pages and the others, a topic named like a page is dropped`() {
        val videos = (1..3).map { v("t$it", "C$it", kw = arrayOf("Formula 1", "music")) } +
            (1..3).map { v("n$it", "N$it", kw = arrayOf("gaming")) }
        val labels = HomeChipRule.chips(videos, emptyList(), now).map { it.label }
        assertEquals(listOf("All", "Subs", "Formula 1", "Music", "Gaming", "Live", "News", "Podcasts"), labels.take(8))
    }

    @Test
    fun `topic list takes turns between For you matches and search, without shorts or watched`() {
        fun c(id: String, title: String, short: Boolean = false) = ChipVideo(id, title, "", "", 300, "", isShort = short)
        val pool = listOf(c("p1", "Best CRICKET moments"), c("p2", "Cooking show"), c("p3", "cricket: the final"), c("p4", "cricketers"))
        val search = listOf(c("s1", "x"), c("p3", "cricket: the final"), c("s2", "y", short = true), c("s3", "z"), c("w", "watched"))
        val feed = HomeChipRule.topicFeed("Cricket", pool, search, exclude = setOf("w"))
        assertEquals(listOf("p1", "s1", "p3", "s3"), feed.map { it.videoId })
    }

    @Test
    fun `Watched lists finished or replayed videos, not music, not today`() {
        fun p(id: String, fraction: Float, plays: Int, daysAgo: Int, music: Boolean = false, dur: Int = 600) =
            PastVideo(id, id, "", "", dur, fraction, plays, now - daysAgo * day, music)
        val list = HomeChipRule.watched(
            listOf(p("half", 0.5f, 1, 3), p("done", 0.95f, 1, 5), p("twice", 0.3f, 2, 9), p("today", 1f, 3, 0),
                p("song", 1f, 4, 3, music = true), p("short", 1f, 1, 3, dur = 40), p("thrice", 1f, 3, 20)),
            now
        )
        assertEquals(listOf("thrice", "twice", "done"), list.map { it.videoId })
    }

    @Test
    fun `durations`() {
        assertEquals(607, HomeChipRule.parseDuration("10:07"))
        assertEquals(3723, HomeChipRule.parseDuration("1:02:03"))
        assertEquals(0, HomeChipRule.parseDuration("LIVE"))
        assertEquals(0, HomeChipRule.parseDuration(""))
    }
}
