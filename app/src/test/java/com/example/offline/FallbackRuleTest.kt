package com.example.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Playing downloaded songs when the connection is gone (pure Kotlin, no Android). */
class FallbackRuleTest {
    private val now = 1_000_000_000L

    private fun web(playing: Boolean, stallAt: Long = 0L, userPaused: Boolean = false, music: Boolean = true) =
        WebSnapshot(isMusicPage = music, isPlaying = playing, userPaused = userPaused, stallAt = stallAt)

    private fun song(id: String, artist: String = "Artist $id", cached: Boolean = false, rank: Int = Int.MAX_VALUE) =
        LocalSong(id, "Song $id", artist, "", 200, cached, rank)

    @Test
    fun `no internet at the end of a song switches almost at once`() {
        assertEquals(FallbackTrigger.NONE, FallbackRule.trigger(now, false, web(false), now - 1_000))
        assertEquals(FallbackTrigger.NO_CONNECTION, FallbackRule.trigger(now, false, web(false), now - 1_500))
    }

    @Test
    fun `with internet, a next song that does not start switches after 10 s`() {
        assertEquals(FallbackTrigger.NONE, FallbackRule.trigger(now, true, web(false), now - 9_000))
        assertEquals(FallbackTrigger.NOT_STARTING, FallbackRule.trigger(now, true, web(false), now - 10_000))
    }

    @Test
    fun `waiting for data switches after 10 s, 5 s without internet`() {
        assertEquals(FallbackTrigger.NONE, FallbackRule.trigger(now, true, web(true, stallAt = now - 9_000), 0))
        assertEquals(FallbackTrigger.STALLED, FallbackRule.trigger(now, true, web(true, stallAt = now - 10_000), 0))
        assertEquals(FallbackTrigger.STALLED, FallbackRule.trigger(now, false, web(true, stallAt = now - 5_000), 0))
        assertEquals(FallbackTrigger.NONE, FallbackRule.trigger(now, false, web(true), 0))
    }

    @Test
    fun `never after the user paused, or outside YouTube Music`() {
        assertEquals(FallbackTrigger.NONE, FallbackRule.trigger(now, false, web(false, userPaused = true), now - 60_000))
        assertEquals(FallbackTrigger.NONE, FallbackRule.trigger(now, false, web(false, music = false), now - 60_000))
        assertEquals(FallbackTrigger.NONE, FallbackRule.trigger(now, false, web(false), 0))
    }

    @Test
    fun `queue songs first, then listed with the current one, same artist, smart rank`() {
        val available = listOf(
            song("rank2", rank = 2), song("rank1", rank = 1), song("q2", cached = true), song("q1", cached = true),
            song("listed"), song("sameArtist", artist = "Band - Topic"), song("cur", cached = true)
        )
        val order = FallbackRule.order(
            currentId = "cur",
            currentArtistKey = "band",
            queueAfter = listOf("q1", "missing", "q2"),
            listedWith = setOf("listed"),
            available = available,
            recentlyPlayed = emptySet()
        )
        assertEquals(listOf("q1", "q2", "listed", "sameArtist", "rank1", "rank2"), order.map { it.videoId })
    }

    @Test
    fun `recently played songs are left out except in the queue, and a song is listed once`() {
        val available = listOf(song("q1", cached = true), song("q1"), song("recent", rank = 1), song("other", rank = 5))
        val order = FallbackRule.order("cur", "", listOf("q1"), emptySet(), available, recentlyPlayed = setOf("recent", "q1"))
        assertEquals(listOf("q1", "other"), order.map { it.videoId })
        assertEquals(false, order.first().cached)   // the download is preferred over the cached copy
    }

    @Test
    fun `back to YouTube Music once the connection is stable, later after a weak signal`() {
        val start = 1_000_000L
        // Switched while offline; the connection came back 25 s ago.
        assertEquals(true, FallbackRule.mayReturn(start + 60_000, true, start + 35_000, start))
        assertEquals(false, FallbackRule.mayReturn(start + 50_000, true, start + 35_000, start))
        assertEquals(false, FallbackRule.mayReturn(start + 60_000, false, start + 35_000, start))
        // Not before 20 s after the switch, even if online for long.
        assertEquals(false, FallbackRule.mayReturn(start + 10_000, true, start + 5_000 - 60_000, start))
        // Switched while Android reported a connection (weak signal): only after 5 min.
        assertEquals(false, FallbackRule.mayReturn(start + 60_000, true, start - 600_000, start))
        assertEquals(true, FallbackRule.mayReturn(start + 300_000, true, start - 600_000, start))
    }

    @Test
    fun `YouTube Music continues after the last queue song that played`() {
        val queue = listOf("cur", "q1", "q2", "q3")
        assertEquals(1, FallbackRule.resumeIndex(queue, listOf("cur")))
        assertEquals(3, FallbackRule.resumeIndex(queue, listOf("cur", "q1", "x", "q2")))
        assertEquals(0, FallbackRule.resumeIndex(queue, listOf("x")))
    }
}
