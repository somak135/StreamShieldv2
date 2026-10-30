package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local library (added in database version 2). Nothing here needs a YouTube account: subscriptions,
 * history, playlists and likes live only on the device.
 *
 * Every table has a plain primary key and no indices, so the hand-written migrations in
 * [StreamShieldDatabase] stay easy to keep in sync with these classes. A column added later needs
 * a default in both the entity (`@ColumnInfo(defaultValue = …)`) and its `ALTER TABLE`.
 */

/** Metadata cache for any video the app has seen (page state, feeds, imports). */
@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int,
    val viewCount: Long,
    val isMusic: Boolean,
    val updatedAt: Long
)

/** One row per watched video: resume position and how much of it was watched. */
@Entity(tableName = "watch_entries")
data class WatchEntryEntity(
    @PrimaryKey val videoId: String,
    val positionSec: Int,
    val durationSec: Int,
    /** Furthest point reached, as a fraction of the duration (0..1). */
    val watchedFraction: Float,
    val firstWatchedAt: Long,
    val lastWatchedAt: Long,
    val playCount: Int,
    val isMusic: Boolean
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val subscribedAt: Long,
    val lastRefreshedAt: Long,
    /** The last refresh of this channel failed; its feed items are from an earlier refresh. */
    val stale: Boolean
)

/** Latest uploads of subscribed channels, from the channel RSS feeds. */
@Entity(tableName = "feed_items")
data class FeedItemEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAt: Long,
    val viewCount: Long,
    /** A YouTube Short (the feed links it as /shorts/...). Added in database version 4. */
    @ColumnInfo(defaultValue = "0") val isShort: Boolean = false
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** [PlaylistKind] name. */
    val kind: String,
    /** Non-null for the built-in lists ([BuiltInPlaylist] name); those cannot be renamed or deleted. */
    val builtIn: String?,
    val createdAt: Long
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "videoId"])
data class PlaylistItemEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val addedAt: Long
)

/** "Not interested" in a video, or "don't recommend" a channel. Used by recommendations. */
@Entity(tableName = "feedback", primaryKeys = ["targetId", "type"])
data class FeedbackEntity(
    val targetId: String,
    /** [FeedbackType] name. */
    val type: String,
    val targetType: String,
    val at: Long
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val lastUsedAt: Long
)

enum class PlaylistKind { VIDEO, MUSIC }

enum class BuiltInPlaylist(val title: String) {
    WATCH_LATER("Watch later"),
    LIKED("Liked videos")
}

/** DISLIKED: YouTube's Dislike button on a video (left out of Home, lowers its channel). */
enum class FeedbackType { NOT_INTERESTED, HIDE_CHANNEL, DISLIKED }

// -------------------------------------------------------------------------------------------
// Query results (joins)
// -------------------------------------------------------------------------------------------

/** A watched video with its metadata, for history and "continue watching". */
data class WatchedVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int,
    val positionSec: Int,
    val watchedFraction: Float,
    val lastWatchedAt: Long,
    val playCount: Int,
    val isMusic: Boolean
)

/** A playlist entry with its metadata. */
data class PlaylistVideo(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int
)

/** A playlist with its size and first video (for the thumbnail). */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val kind: String,
    val builtIn: String?,
    val itemCount: Int,
    val firstVideoId: String?
)
