package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Table of the original app's (simulated) downloads; unused, kept so the schema stays the same. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val qualityOptionId: String,
    val qualityBadge: String,
    val formatContainer: String, // MP4, WEBM, M4A, OPUS
    val fileExtension: String,
    val isAudioOnly: Boolean,
    val durationSeconds: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val storageSavedBytes: Long,
    val status: String, // DOWNLOADING, PAUSED, COMPLETED, FAILED
    val localFilePath: String,
    val sourceStreamUrl: String,
    val sourcePageUrl: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val url: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoUrl: String,
    val title: String,
    val channelName: String,
    val playbackSpeedUsed: Float,
    val visitedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt ASC")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks")
    suspend fun clearBookmarks()
}

@Dao
interface WatchHistoryDao {
    @Query("DELETE FROM watch_history")
    suspend fun clearAllHistory()
}

@Database(
    entities = [
        DownloadEntity::class, BookmarkEntity::class, WatchHistoryEntity::class,
        VideoEntity::class, WatchEntryEntity::class, SubscriptionEntity::class, FeedItemEntity::class,
        PlaylistEntity::class, PlaylistItemEntity::class, FeedbackEntity::class, SearchHistoryEntity::class,
        OfflineItemEntity::class, PlayEventEntity::class, SmartRemovedEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class StreamShieldDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun libraryDao(): LibraryDao
    abstract fun offlineDao(): OfflineDao

    companion object {
        @Volatile
        private var INSTANCE: StreamShieldDatabase? = null

        /**
         * Version 2 adds the local library tables. The SQL must match the entity classes exactly
         * (Room validates the schema after migrating); `LibraryTest` checks this.
         * The old `watch_history` rows are copied into `videos` / `watch_entries`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `videos` (`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`channelId` TEXT NOT NULL, `channelName` TEXT NOT NULL, `durationSec` INTEGER NOT NULL, " +
                        "`viewCount` INTEGER NOT NULL, `isMusic` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_entries` (`videoId` TEXT NOT NULL, `positionSec` INTEGER NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, `watchedFraction` REAL NOT NULL, `firstWatchedAt` INTEGER NOT NULL, " +
                        "`lastWatchedAt` INTEGER NOT NULL, `playCount` INTEGER NOT NULL, `isMusic` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subscriptions` (`channelId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`subscribedAt` INTEGER NOT NULL, `lastRefreshedAt` INTEGER NOT NULL, `stale` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`channelId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feed_items` (`videoId` TEXT NOT NULL, `channelId` TEXT NOT NULL, " +
                        "`channelName` TEXT NOT NULL, `title` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, " +
                        "`viewCount` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `kind` TEXT NOT NULL, `builtIn` TEXT, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_items` (`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `videoId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feedback` (`targetId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`targetType` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`targetId`, `type`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `lastUsedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`query`))"
                )
                // Carry over v1 history (video ids are the 11 characters after "v=").
                db.execSQL(
                    "INSERT OR IGNORE INTO `videos` (videoId, title, channelId, channelName, durationSec, viewCount, isMusic, updatedAt) " +
                        "SELECT substr(videoUrl, instr(videoUrl, 'v=') + 2, 11), title, '', channelName, 0, 0, 0, visitedAt " +
                        "FROM watch_history WHERE instr(videoUrl, 'v=') > 0"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `watch_entries` (videoId, positionSec, durationSec, watchedFraction, firstWatchedAt, " +
                        "lastWatchedAt, playCount, isMusic) " +
                        "SELECT substr(videoUrl, instr(videoUrl, 'v=') + 2, 11), 0, 0, 0.0, visitedAt, visitedAt, 1, 0 " +
                        "FROM watch_history WHERE instr(videoUrl, 'v=') > 0"
                )
            }
        }

        /** Version 3: downloads, the listening log and removed smart downloads (OfflineEntities.kt). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_items` (`id` TEXT NOT NULL, `videoId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `channelId` TEXT NOT NULL, `channelName` TEXT NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, `isMusic` INTEGER NOT NULL, `audioOnly` INTEGER NOT NULL, " +
                        "`targetHeight` INTEGER NOT NULL, `qualityLabel` TEXT NOT NULL, `origin` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `totalBytes` INTEGER NOT NULL, `downloadedBytes` INTEGER NOT NULL, " +
                        "`location` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `error` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `smartSection` TEXT NOT NULL, " +
                        "`smartRank` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`videoId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `listenedSec` INTEGER NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `isMusic` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smart_removed` (`videoId` TEXT NOT NULL, `removedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))"
                )
            }
        }

        /** Version 4: feed items know whether they are Shorts (from the feed's link). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `feed_items` ADD COLUMN `isShort` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): StreamShieldDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StreamShieldDatabase::class.java,
                    "streamshield_offline.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
