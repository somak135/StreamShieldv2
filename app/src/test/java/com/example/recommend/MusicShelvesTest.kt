package com.example.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music home's own shelves (pure Kotlin, no Android). */
class MusicShelvesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun song(id: String, artist: String, daysAgo: Long = 1, plays: Int = 1, fraction: Float = 1f) =
        SongStat(id, "Song $id", artist, "", 200, fraction, now - daysAgo * day, plays)

    @Test
    fun `speed dial puts an artist on every third tile, by score`() {
        val songs = listOf(
            song("a1", "A", plays = 5), song("a2", "A", plays = 4),
            song("b1", "B - Topic", plays = 3), song("b2", "B", plays = 1),
            song("c1", "C", plays = 2)
        )
        val tiles = MusicShelves.speedDial(songs, now)
        assertEquals(listOf(false, false, true, false, false, true, false), tiles.map { it.isArtist })
        assertEquals("a1", tiles[0].song.videoId)
        assertEquals("a", tiles[2].song.artistKey)          // A outscores B
        assertEquals("b", tiles[5].song.artistKey)          // "B - Topic" and "B" are one artist
        assertTrue(tiles.none { it.isArtist && it.song.artistKey == "c" })   // one song only
    }

    @Test
    fun `speed dial stops at 27 tiles`() {
        val songs = (1..40).map { song("s$it", "Artist $it") }
        assertEquals(27, MusicShelves.speedDial(songs, now).size)
    }

    @Test
    fun `radios take the best song of different artists`() {
        val songs = listOf(song("a1", "A", plays = 9), song("a2", "A", plays = 8), song("b1", "B", plays = 2))
        assertEquals(listOf("a1", "b1"), MusicShelves.radios(songs, now).map { it.videoId })
    }

    @Test
    fun `forgotten favorites are played twice and not in the last 30 days`() {
        val songs = listOf(
            song("old", "A", daysAgo = 60, plays = 3),
            song("older", "B", daysAgo = 200, plays = 5),
            song("recent", "C", daysAgo = 10, plays = 9),
            song("once", "D", daysAgo = 60, plays = 1),
            song("ancient", "E", daysAgo = 400, plays = 9)
        )
        assertEquals(listOf("older", "old"), MusicShelves.forgottenFavorites(songs, now).map { it.videoId })
    }
}
