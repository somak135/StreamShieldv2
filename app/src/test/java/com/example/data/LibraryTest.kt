package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Builds a version-1 database exactly as Room created it, then opens it with the current version (migrations 1 → 4). */
    @Test
    fun `migration 1 to 2 keeps old history and matches the entities`() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `videoId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `channelName` TEXT NOT NULL, `thumbnailUrl` TEXT NOT NULL, `qualityOptionId` TEXT NOT NULL, " +
                    "`qualityBadge` TEXT NOT NULL, `formatContainer` TEXT NOT NULL, `fileExtension` TEXT NOT NULL, " +
                    "`isAudioOnly` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, " +
                    "`downloadedBytes` INTEGER NOT NULL, `storageSavedBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`localFilePath` TEXT NOT NULL, `sourceStreamUrl` TEXT NOT NULL, `sourcePageUrl` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, `category` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `watch_history` (`videoUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `channelName` TEXT NOT NULL, " +
                    "`playbackSpeedUsed` REAL NOT NULL, `visitedAt` INTEGER NOT NULL, PRIMARY KEY(`videoUrl`))"
            )
            db.execSQL("INSERT INTO watch_history VALUES ('https://m.youtube.com/watch?v=abcdefghijk', 'Old video', 'Old channel', 1.0, 1000)")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, StreamShieldDatabase::class.java, name)
            .addMigrations(StreamShieldDatabase.MIGRATION_1_2, StreamShieldDatabase.MIGRATION_2_3, StreamShieldDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        val history = room.libraryDao().recentHistory(10)
        assertEquals(1, history.size)
        assertEquals("abcdefghijk", history[0].videoId)
        assertEquals("Old video", history[0].title)
        assertEquals("Old channel", history[0].channelName)
        room.close()
    }

    @Test
    fun `history, subscriptions and playlists round trip`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, StreamShieldDatabase::class.java).allowMainThreadQueries().build()
        var clock = 10_000L
        val repo = LibraryRepository(db.libraryDao()) { clock }

        repo.rememberVideo("vid00000001", "Title", "UCaaaaaaaaaaaaaaaaaaaaaa", "Chan", 600, 10, false)
        repo.recordProgress("vid00000001", 120, 600, false, newSession = true)
        clock += 1_000
        repo.recordProgress("vid00000001", 60, 600, false, newSession = false)
        val entry = repo.recentHistory(5).single()
        assertEquals(60, entry.positionSec)
        assertEquals(0.2f, entry.watchedFraction, 0.001f) // furthest point is kept
        assertEquals(1, entry.playCount)
        assertEquals("Chan", entry.channelName)

        // Empty values never overwrite known ones.
        repo.rememberVideo("vid00000001", "", "", "", 0, 0, false)
        assertEquals("Title", repo.recentHistory(5).single().title)

        assertTrue(repo.subscribe("UCaaaaaaaaaaaaaaaaaaaaaa", "Chan"))
        assertFalse(repo.subscribe("UCaaaaaaaaaaaaaaaaaaaaaa", "Chan"))
        assertFalse(repo.subscribe("not-a-channel", "x"))
        repo.saveChannelFeed(
            "UCaaaaaaaaaaaaaaaaaaaaaa", "Chan",
            listOf(FeedItemEntity("feedvid0001", "UCaaaaaaaaaaaaaaaaaaaaaa", "Chan", "New", 5_000L, 3))
        )
        assertEquals(1, db.libraryDao().allSubscriptions().size)
        repo.unsubscribe("UCaaaaaaaaaaaaaaaaaaaaaa")
        assertTrue(db.libraryDao().allSubscriptions().isEmpty())

        repo.ensureBuiltInPlaylists()
        repo.ensureBuiltInPlaylists()
        val laterId = repo.builtInPlaylistId(BuiltInPlaylist.WATCH_LATER)
        assertTrue(repo.toggleBuiltIn(BuiltInPlaylist.WATCH_LATER, "vid00000001", currentlyIn = false))
        assertNotNull(db.libraryDao().getPlaylist(laterId))
        val custom = repo.createPlaylist("Mix")
        repo.addToPlaylist(custom, "vid00000001")
        repo.addToPlaylist(custom, "vid00000002")
        repo.deletePlaylist(laterId) // built-in: ignored
        assertNotNull(db.libraryDao().getPlaylist(laterId))
        db.close()
    }

    @Test
    fun `rss feed is parsed`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
             <title>Some Channel</title>
             <author><name>Some Channel</name></author>
             <entry>
              <yt:videoId>AAAAAAAAAAA</yt:videoId>
              <yt:channelId>UCbbbbbbbbbbbbbbbbbbbbbb</yt:channelId>
              <title>First &amp; best</title>
              <published>2026-10-20T15:00:12+02:00</published>
              <media:group><media:title>First</media:title><media:community><media:statistics views="1234"/></media:community></media:group>
             </entry>
             <entry>
              <yt:videoId>BBBBBBBBBBB</yt:videoId>
              <link rel="alternate" href="https://www.youtube.com/shorts/BBBBBBBBBBB"/>
              <title>Second</title>
              <published>2026-10-19T00:00:00Z</published>
             </entry>
            </feed>
        """.trimIndent()
        val feed = SubscriptionFeedClient.parse("UCbbbbbbbbbbbbbbbbbbbbbb", xml)!!
        assertEquals("Some Channel", feed.channelName)
        assertEquals(2, feed.items.size)
        assertEquals("First & best", feed.items[0].title)
        assertEquals(1234L, feed.items[0].viewCount)
        assertEquals("Some Channel", feed.items[1].channelName)
        assertEquals(false, feed.items[0].isShort)
        assertEquals(true, feed.items[1].isShort)
        // 15:00:12 at +02:00 is 13:00:12 UTC.
        assertEquals(SubscriptionFeedClient.parseIso8601("2026-10-20T13:00:12Z"), feed.items[0].publishedAt)
    }

    @Test
    fun `takeout csv is parsed by header names`() {
        val csv = "Channel Id,Channel Url,Channel Title\n" +
            "UCcccccccccccccccccccccc,http://www.youtube.com/channel/UCcccccccccccccccccccccc,\"Music, Live\"\n" +
            "garbage,,\n"
        val channels = TakeoutSubscriptionsParser.parse(csv)
        assertEquals(1, channels.size)
        assertEquals("UCcccccccccccccccccccccc", channels[0].channelId)
        assertEquals("Music, Live", channels[0].name)
    }

    @Test
    fun `video pages hide the tabs, every other page keeps them`() {
        assertTrue(isYouTubeVideoPage("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(isYouTubeVideoPage("https://m.youtube.com/shorts/abcdefghijk"))
        assertTrue(isYouTubeVideoPage("https://www.youtube.com/live/abcdefghijk"))
        assertTrue(isYouTubeVideoPage("https://www.youtube.com/embed/abcdefghijk"))
        assertFalse(isYouTubeVideoPage("https://m.youtube.com/results?search_query=lofi"))
        assertFalse(isYouTubeVideoPage("https://m.youtube.com/channel/UCcccccccccccccccccccccc"))
        assertFalse(isYouTubeVideoPage("https://m.youtube.com/@somechannel/live"))
        assertFalse(isYouTubeVideoPage("https://m.youtube.com/"))
        assertFalse(isYouTubeVideoPage("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(isYouTubeVideoPage("https://example.com/watch?v=dQw4w9WgXcQ"))
    }
}
