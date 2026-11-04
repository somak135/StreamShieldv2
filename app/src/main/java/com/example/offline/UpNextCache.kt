package com.example.offline

import android.content.Context
import com.example.data.OfflineItemEntity
import com.example.data.OfflineStatus
import com.example.download.ExpiredUrlException
import com.example.download.NewPipeSupport
import com.example.download.OfflineStorage
import com.example.download.RangeFetcher
import com.example.download.StreamChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A song of YouTube Music's queue (from the page). */
data class QueueSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val channelId: String,
    val durationSec: Int
)

/**
 * The up-next cache: the next [WINDOW] songs of YouTube Music's queue, downloaded (audio, normal
 * quality) while music plays, so they can play when the connection is gone. Separate from smart
 * downloads: a folder in the app's cache, not listed under Downloads, emptied when the app is
 * swiped away and whenever the app starts.
 *
 * Kept: the song playing, the next [WINDOW] and the last [RECENT] played (Previous works without a
 * connection); everything else is deleted at once, and the folder never holds more than
 * [CAP_BYTES]. Songs already downloaded (own or smart) are not fetched again. One song at a time,
 * written under a temporary name and renamed when complete. Nothing is fetched while less than
 * [MIN_FREE_BYTES] are free. While downloaded songs play instead of YouTube Music ([frozen])
 * nothing is deleted or fetched.
 */
class UpNextCache(private val context: Context, private val scope: CoroutineScope) {

    data class Entry(val song: QueueSong, val file: File, val bytes: Long, val mimeType: String, val addedAt: Long)
    data class Stats(val songs: Int = 0, val bytes: Long = 0L, val fetched: Int = 0, val failed: Int = 0)

    private val dir: File get() = dirOf(context)
    private val entries = ConcurrentHashMap<String, Entry>()
    /** Songs that could not be fetched, and when (tried again after [RETRY_AFTER_MS]). */
    private val failures = ConcurrentHashMap<String, Long>()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    @Volatile private var window: List<QueueSong> = emptyList()
    @Volatile private var keep: Set<String> = emptySet()
    @Volatile private var skip: (String) -> Boolean = { false }
    @Volatile var frozen: Boolean = false
    private var worker: Job? = null
    private var fetched = 0
    private var failed = 0

    fun has(videoId: String): Boolean = entries[videoId]?.file?.exists() == true

    /** Cached songs, as songs that play without a connection. */
    fun localSongs(): List<LocalSong> = entries.values.filter { it.file.exists() }.map {
        LocalSong(it.song.videoId, it.song.title, it.song.artist, it.song.channelId, it.song.durationSec, cached = true)
    }

    /** A cached song as the offline player takes it. */
    fun item(videoId: String): OfflineItemEntity? {
        val e = entries[videoId]?.takeIf { it.file.exists() } ?: return null
        return OfflineItemEntity(
            id = "upnext:" + e.song.videoId,
            videoId = e.song.videoId,
            title = e.song.title,
            channelId = e.song.channelId,
            channelName = e.song.artist,
            durationSec = e.song.durationSec,
            isMusic = true,
            audioOnly = true,
            targetHeight = 0,
            qualityLabel = "",
            origin = ORIGIN,
            status = OfflineStatus.DONE.name,
            totalBytes = e.bytes,
            downloadedBytes = e.bytes,
            location = e.file.absolutePath,
            mimeType = e.mimeType,
            error = "",
            createdAt = e.addedAt,
            completedAt = e.addedAt,
            smartSection = "",
            smartRank = 0
        )
    }

    /**
     * YouTube Music plays [current]; [upcoming] are its next songs in order, [recent] the songs
     * played before (newest first). [download]: fetching is allowed now (setting on, online,
     * music playing); [alreadyDownloaded]: an own or smart download exists.
     */
    fun update(
        current: String,
        upcoming: List<QueueSong>,
        recent: List<String>,
        download: Boolean,
        alreadyDownloaded: (String) -> Boolean
    ) {
        if (frozen) return
        val next = upcoming.filter { it.videoId != current }.take(WINDOW)
        window = next
        skip = alreadyDownloaded
        keep = (listOf(current) + next.map { it.videoId } + recent.take(RECENT)).toSet()
        evict { it !in keep }
        if (download) start() else stopFetching()
    }

    /** Stops fetching (e.g. the setting was turned off, or music stopped). */
    fun stopFetching() {
        worker?.cancel()
        worker = null
    }

    /** Deletes everything (setting turned off, or the app was swiped away). */
    fun clear() {
        stopFetching()
        window = emptyList()
        keep = emptySet()
        entries.clear()
        clearDir(context)
        publish()
    }

    private fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val song = window.firstOrNull { s ->
                    !has(s.videoId) && !skip(s.videoId) &&
                        (failures[s.videoId]?.let { System.currentTimeMillis() - it > RETRY_AFTER_MS } ?: true)
                } ?: break
                if (dir.apply { mkdirs() }.usableSpace < MIN_FREE_BYTES) break
                try {
                    if (!fetch(song) { isActive }) failures[song.videoId] = System.currentTimeMillis()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelled; or the song left the window (go on with the next) or downloaded
                    // songs started playing (stop).
                    if (!isActive || frozen) throw e
                }
            }
        }
    }

    /**
     * Downloads one song; false if it failed (tried again later). Stops (CancellationException)
     * when the song left the window, downloaded songs started playing, or [active] turned false.
     */
    private fun fetch(song: QueueSong, active: () -> Boolean): Boolean {
        val id = song.videoId
        val stop = { !active() || frozen || window.none { it.videoId == id } }
        var temp: File? = null
        return try {
            var info = NewPipeSupport.streamInfo(id)
            if (info.duration > MAX_SONG_SEC) return false
            var plan = StreamChoice.audioPlan(NewPipeSupport.audioCandidates(info)) ?: return false
            val estimate = plan.estimatedBytes(info.duration)
            if (!makeRoom(estimate)) return false
            val ext = OfflineStorage.extension(plan.container, audioOnly = true)
            val part = File(dir.apply { mkdirs() }, "$id.part").also { temp = it }
            part.delete()
            var attempt = 0
            while (true) {
                try {
                    RangeFetcher.fetch(plan.audio!!.url, part, stop) { _, _ -> }
                    break
                } catch (e: ExpiredUrlException) {
                    if (++attempt > 1) throw e
                    info = NewPipeSupport.streamInfo(id)
                    plan = StreamChoice.audioPlan(NewPipeSupport.audioCandidates(info)) ?: return false
                    part.delete()
                }
            }
            val target = File(dir, "$id.$ext")
            if (!part.renameTo(target)) {
                part.delete()
                return false
            }
            val now = System.currentTimeMillis()
            val durationSec = if (info.duration > 0) info.duration.toInt() else song.durationSec
            entries[id] = Entry(
                song.copy(title = song.title.ifBlank { info.name.orEmpty() }, artist = song.artist.ifBlank { info.uploaderName.orEmpty() }, durationSec = durationSec),
                target,
                target.length(),
                OfflineStorage.mimeType(plan.container, audioOnly = true),
                now
            )
            fetched++
            // Over the cap after all (estimates are rough): drop what is not needed.
            if (totalBytes() > CAP_BYTES) evict { it !in keep }
            publish()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            temp?.delete()
            throw e
        } catch (_: Exception) {
            temp?.delete()
            failed++
            publish()
            false
        }
    }

    /** Frees space for [bytes] by deleting songs that are not kept; false if it does not fit. */
    private fun makeRoom(bytes: Long): Boolean {
        if (totalBytes() + bytes <= CAP_BYTES) return true
        entries.values.filter { it.song.videoId !in keep }.sortedBy { it.addedAt }.forEach { e ->
            if (totalBytes() + bytes <= CAP_BYTES) return true
            remove(e.song.videoId)
        }
        return totalBytes() + bytes <= CAP_BYTES
    }

    private fun evict(drop: (String) -> Boolean) {
        entries.keys.filter(drop).forEach { remove(it) }
        publish()
    }

    private fun remove(videoId: String) {
        entries.remove(videoId)?.file?.delete()
    }

    private fun totalBytes(): Long = entries.values.sumOf { it.bytes }

    private fun publish() {
        _stats.value = Stats(entries.size, totalBytes(), fetched, failed)
    }

    companion object {
        const val WINDOW = 4
        const val RECENT = 2
        const val CAP_BYTES = 60L * 1024 * 1024
        const val MIN_FREE_BYTES = 1024L * 1024 * 1024
        const val MAX_SONG_SEC = 20 * 60L
        const val RETRY_AFTER_MS = 10 * 60_000L
        /** [OfflineItemEntity.origin] of cached songs (never stored in the database). */
        const val ORIGIN = "UP_NEXT"
        private const val DIR = "upnext"

        fun dirOf(context: Context): File = File(context.cacheDir, DIR)

        /** Removes the folder (app start, swipe-away). */
        fun clearDir(context: Context) {
            try {
                dirOf(context).deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }
}
