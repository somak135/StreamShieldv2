package com.example.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a backup file contains, shown before importing it. */
data class BackupSummary(
    val exportedAt: Long,
    val device: String,
    val subscriptions: Int,
    val playlists: Int,
    val playlistItems: Int,
    val history: Int,
    val searches: Int,
    val bookmarks: Int
)

enum class ImportMode { MERGE, REPLACE }

/**
 * Exports / imports everything the app knows about the user as one JSON file, so a new phone (or
 * a reinstall) can pick up where the old one left off without any account. Downloads and the
 * smart-download listening log stay on the device.
 *
 * Format 1:
 * ```
 * { "app": "StreamShield", "format": 1, "exportedAt": ms, "device": "...",
 *   "preferences": {...}, "subscriptions": [...], "videos": [...], "history": [...],
 *   "playlists": [{ "name", "kind", "builtIn", "createdAt", "items": [videoId, ...] }],
 *   "feedback": [...], "searches": [...], "bookmarks": [...] }
 * ```
 */
class BackupManager(
    private val db: StreamShieldDatabase,
    private val prefs: UserPreferencesRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = db.libraryDao()
    private val library = LibraryRepository(dao, now)

    suspend fun export(): String {
        val root = JSONObject()
            .put("app", "StreamShield")
            .put("format", FORMAT)
            .put("exportedAt", now())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("preferences", prefs.toBackupJson(prefs.preferencesFlow.first()))

        root.put("subscriptions", JSONArray().apply {
            dao.allSubscriptions().forEach {
                put(JSONObject().put("channelId", it.channelId).put("name", it.name).put("subscribedAt", it.subscribedAt))
            }
        })
        root.put("videos", JSONArray().apply {
            dao.allVideos().forEach {
                put(
                    JSONObject().put("videoId", it.videoId).put("title", it.title).put("channelId", it.channelId)
                        .put("channelName", it.channelName).put("durationSec", it.durationSec).put("viewCount", it.viewCount)
                        .put("isMusic", it.isMusic).put("updatedAt", it.updatedAt)
                )
            }
        })
        root.put("history", JSONArray().apply {
            dao.allWatchEntries().forEach {
                put(
                    JSONObject().put("videoId", it.videoId).put("positionSec", it.positionSec).put("durationSec", it.durationSec)
                        .put("watchedFraction", it.watchedFraction.toDouble()).put("firstWatchedAt", it.firstWatchedAt)
                        .put("lastWatchedAt", it.lastWatchedAt).put("playCount", it.playCount).put("isMusic", it.isMusic)
                )
            }
        })
        val items = dao.allPlaylistItems().groupBy { it.playlistId }
        root.put("playlists", JSONArray().apply {
            dao.allPlaylists().forEach { p ->
                put(
                    JSONObject().put("name", p.name).put("kind", p.kind).put("builtIn", p.builtIn ?: JSONObject.NULL)
                        .put("createdAt", p.createdAt)
                        .put("items", JSONArray(items[p.id].orEmpty().sortedBy { it.position }.map { it.videoId }))
                )
            }
        })
        root.put("feedback", JSONArray().apply {
            dao.allFeedback().forEach {
                put(JSONObject().put("targetId", it.targetId).put("type", it.type).put("targetType", it.targetType).put("at", it.at))
            }
        })
        root.put("searches", JSONArray().apply {
            dao.allSearches().forEach { put(JSONObject().put("query", it.query).put("lastUsedAt", it.lastUsedAt)) }
        })
        root.put("bookmarks", JSONArray().apply {
            db.bookmarkDao().allBookmarks().forEach {
                put(JSONObject().put("title", it.title).put("url", it.url).put("category", it.category).put("createdAt", it.createdAt))
            }
        })
        return root.toString()
    }

    /** Parses a backup file; throws IllegalArgumentException if it is not one. */
    fun summarize(text: String): BackupSummary {
        val root = parse(text)
        val playlists = root.optJSONArray("playlists") ?: JSONArray()
        var itemCount = 0
        for (i in 0 until playlists.length()) itemCount += playlists.optJSONObject(i)?.optJSONArray("items")?.length() ?: 0
        return BackupSummary(
            exportedAt = root.optLong("exportedAt"),
            device = root.optString("device"),
            subscriptions = root.optJSONArray("subscriptions")?.length() ?: 0,
            playlists = playlists.length(),
            playlistItems = itemCount,
            history = root.optJSONArray("history")?.length() ?: 0,
            searches = root.optJSONArray("searches")?.length() ?: 0,
            bookmarks = root.optJSONArray("bookmarks")?.length() ?: 0
        )
    }

    suspend fun import(text: String, mode: ImportMode) {
        val root = parse(text)
        root.optJSONObject("preferences")?.let { prefs.restoreFromBackup(it) }
        // One transaction: an interrupted import leaves the library as it was, and is much faster.
        db.withTransaction { importLibrary(root, mode) }
    }

    private suspend fun importLibrary(root: JSONObject, mode: ImportMode) {
        if (mode == ImportMode.REPLACE) {
            dao.clearLibrary()
            db.bookmarkDao().clearBookmarks()
        }
        library.ensureBuiltInPlaylists()

        // Subscriptions (existing ones are kept as they are).
        val subs = root.optJSONArray("subscriptions").objects().mapNotNull { o ->
            val id = o.optString("channelId")
            if (!LibraryRepository.CHANNEL_ID.matches(id)) null
            else SubscriptionEntity(id, o.optString("name"), o.optLong("subscribedAt", now()), 0L, stale = false)
        }
        dao.insertSubscriptions(subs)

        // Video metadata: known values win over empty ones.
        val videos = root.optJSONArray("videos").objects().mapNotNull { o ->
            val id = o.optString("videoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val incoming = VideoEntity(
                id, o.optString("title"), o.optString("channelId"), o.optString("channelName"),
                o.optInt("durationSec"), o.optLong("viewCount"), o.optBoolean("isMusic"), o.optLong("updatedAt", now())
            )
            val old = dao.getVideo(id)
            if (old == null) incoming else VideoEntity(
                id,
                incoming.title.ifBlank { old.title },
                incoming.channelId.ifBlank { old.channelId },
                incoming.channelName.ifBlank { old.channelName },
                if (incoming.durationSec > 0) incoming.durationSec else old.durationSec,
                maxOf(incoming.viewCount, old.viewCount),
                incoming.isMusic || old.isMusic,
                maxOf(incoming.updatedAt, old.updatedAt)
            )
        }
        dao.upsertVideos(videos)

        // History: furthest point and most recent position win.
        val history = root.optJSONArray("history").objects().mapNotNull { o ->
            val id = o.optString("videoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val incoming = WatchEntryEntity(
                id, o.optInt("positionSec"), o.optInt("durationSec"), o.optDouble("watchedFraction", 0.0).toFloat(),
                o.optLong("firstWatchedAt"), o.optLong("lastWatchedAt"), o.optInt("playCount", 1), o.optBoolean("isMusic")
            )
            mergeWatch(dao.getWatchEntry(id), incoming)
        }
        dao.upsertWatchEntries(history)

        // Playlists: built-in lists map onto this phone's; others match by name and kind.
        val existing = dao.allPlaylists()
        root.optJSONArray("playlists").objects().forEach { o ->
            val builtIn = if (o.isNull("builtIn")) null else o.optString("builtIn").takeIf { it.isNotBlank() }
            val name = o.optString("name").ifBlank { "Playlist" }
            val kind = o.optString("kind").ifBlank { PlaylistKind.VIDEO.name }
            val targetId = when {
                builtIn != null -> dao.getBuiltInPlaylist(builtIn)?.id
                else -> existing.firstOrNull { it.builtIn == null && it.name == name && it.kind == kind }?.id
                    ?: dao.insertPlaylist(PlaylistEntity(name = name, kind = kind, builtIn = null, createdAt = o.optLong("createdAt", now())))
            } ?: return@forEach
            val ids = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until ids.length()) {
                val videoId = ids.optString(i)
                if (videoId.isNotBlank()) dao.appendToPlaylist(targetId, videoId, now())
            }
        }

        root.optJSONArray("feedback").objects().forEach { o ->
            val target = o.optString("targetId")
            val type = o.optString("type")
            if (target.isNotBlank() && type.isNotBlank()) {
                dao.insertFeedback(FeedbackEntity(target, type, o.optString("targetType"), o.optLong("at", now())))
            }
        }
        val searches = dao.allSearches().associateBy { it.query }
        root.optJSONArray("searches").objects().forEach { o ->
            val q = o.optString("query")
            val at = o.optLong("lastUsedAt")
            if (q.isNotBlank() && at > (searches[q]?.lastUsedAt ?: -1L)) dao.upsertSearch(SearchHistoryEntity(q, at))
        }
        val bookmarkUrls = db.bookmarkDao().allBookmarks().mapTo(HashSet()) { it.url }
        root.optJSONArray("bookmarks").objects().forEach { o ->
            val url = o.optString("url")
            if (url.isNotBlank() && bookmarkUrls.add(url)) {
                db.bookmarkDao().insertBookmark(
                    BookmarkEntity(title = o.optString("title"), url = url, category = o.optString("category"), createdAt = o.optLong("createdAt", now()))
                )
            }
        }
    }

    /**
     * Google Takeout "watch-history.json" (YouTube and YouTube Music history). Takeout has no
     * watch progress, so entries count as watched. Returns the number of videos imported.
     */
    suspend fun importTakeoutHistory(text: String): Int {
        val array = try { JSONArray(text.trim()) } catch (e: Exception) {
            throw IllegalArgumentException("Not a watch-history.json file (choose the JSON format in Takeout)")
        }
        data class Agg(var title: String, var channel: String, var channelId: String, var music: Boolean,
                       var first: Long, var last: Long, var count: Int)
        val byId = LinkedHashMap<String, Agg>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("titleUrl")
            val id = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: continue
            val time = SubscriptionFeedClient.parseIso8601(o.optString("time"))
            val sub = o.optJSONArray("subtitles")?.optJSONObject(0)
            val channelId = sub?.optString("url")?.let { Regex("channel/(UC[A-Za-z0-9_-]{22})").find(it)?.groupValues?.get(1) }.orEmpty()
            val title = o.optString("title").removePrefix("Watched ").trim()
            val music = o.optString("header").contains("Music", ignoreCase = true)
            val agg = byId.getOrPut(id) { Agg(title, sub?.optString("name").orEmpty(), channelId, music, time, time, 0) }
            agg.count++
            if (time in 1 until agg.first) agg.first = time
            if (time > agg.last) agg.last = time
        }
        db.withTransaction {
            for ((id, a) in byId) {
                library.rememberVideo(id, a.title, a.channelId, a.channel, 0, 0, a.music)
                val incoming = WatchEntryEntity(id, 0, 0, TAKEOUT_FRACTION, a.first, a.last, a.count, a.music)
                dao.upsertWatchEntry(mergeWatch(dao.getWatchEntry(id), incoming))
            }
        }
        return byId.size
    }

    private fun mergeWatch(old: WatchEntryEntity?, new: WatchEntryEntity): WatchEntryEntity {
        if (old == null) return new
        val newer = if (new.lastWatchedAt >= old.lastWatchedAt) new else old
        return WatchEntryEntity(
            videoId = old.videoId,
            positionSec = newer.positionSec,
            durationSec = if (newer.durationSec > 0) newer.durationSec else maxOf(old.durationSec, new.durationSec),
            watchedFraction = maxOf(old.watchedFraction, new.watchedFraction),
            firstWatchedAt = listOf(old.firstWatchedAt, new.firstWatchedAt).filter { it > 0 }.minOrNull() ?: 0L,
            lastWatchedAt = maxOf(old.lastWatchedAt, new.lastWatchedAt),
            playCount = maxOf(old.playCount, new.playCount),
            isMusic = old.isMusic || new.isMusic
        )
    }

    private fun parse(text: String): JSONObject {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("Not a StreamShield backup file") }
        if (root.optString("app") != "StreamShield") throw IllegalArgumentException("Not a StreamShield backup file")
        if (root.optInt("format") > FORMAT) throw IllegalArgumentException("Backup is from a newer version of the app")
        return root
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    companion object {
        const val FORMAT = 1
        private const val TAKEOUT_FRACTION = 0.95f

        fun defaultFileName(now: Long = System.currentTimeMillis()): String =
            "streamshield-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)) + ".json"
    }
}

/** Writes backups into a folder the user picked once (Storage Access Framework document tree). */
object BackupFolder {
    private const val AUTO_PREFIX = "streamshield-auto-"
    private const val KEEP = 7

    /** Human-readable folder name for a tree URI, e.g. "Documents/Backups". */
    fun displayName(treeUri: String): String = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)).substringAfter(':').ifBlank { "Selected folder" }
    } catch (_: Exception) {
        "Selected folder"
    }

    /** Writes today's automatic backup and keeps only the newest [KEEP] automatic ones. */
    fun writeAutoBackup(context: Context, treeUri: String, json: String, now: Long = System.currentTimeMillis()): Boolean {
        val tree = Uri.parse(treeUri)
        val resolver = context.contentResolver
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = AUTO_PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".json"
            val doc = DocumentsContract.createDocument(resolver, parent, "application/json", name) ?: return false
            resolver.openOutputStream(doc, "wt")?.use { it.write(json.toByteArray()) } ?: return false
            pruneOldAutoBackups(context, tree)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pruneOldAutoBackups(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>() // documentId, name
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(AUTO_PREFIX)) found += c.getString(0) to name
            }
        }
        // Names sort chronologically; only this app's own automatic backups are ever removed.
        found.sortedByDescending { it.second }.drop(KEEP).forEach { (docId, _) ->
            try {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            } catch (_: Exception) {
            }
        }
    }
}
