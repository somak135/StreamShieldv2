package com.example.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.BuiltInPlaylist
import com.example.data.FeedbackType
import com.example.data.OfflineItemEntity
import com.example.data.OfflineOrigin
import com.example.data.OfflineStatus
import com.example.data.PlayEventEntity
import com.example.data.SmartSection
import com.example.data.StreamShieldDatabase
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Smart downloads (music only): the daily refresh that applies [SmartRule], deletes songs that
 * dropped out and downloads new ones (inside the app, M4A audio).
 */
object SmartDownloads {
    private const val PERIODIC_WORK = "smart_downloads"
    private const val ONE_TIME_WORK = "smart_downloads_now"

    /** Size options offered in Settings (MB); 0 = off. */
    val SIZE_OPTIONS_MB = listOf(0, 250, 500, 1024, 2048, 4096)

    fun estimatedSongs(sizeMb: Int): Int =
        if (sizeMb <= 0) 0 else (sizeMb * 1024L * 1024L / SmartRule.estimatedBytes(SmartRule.DEFAULT_SONG_SEC)).toInt()

    /** Daily refresh on Wi-Fi while charging (the user's choice), or nothing if turned off. */
    fun schedule(context: Context, sizeMb: Int) {
        val work = WorkManager.getInstance(context)
        if (sizeMb <= 0) {
            work.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SmartDownloadWorker>(1, TimeUnit.DAYS)
            .setConstraints(dailyConstraints())
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** After a size change: a refresh when the daily conditions hold; turning off cleans up at once. */
    fun onSizeChanged(context: Context, sizeMb: Int) {
        schedule(context, sizeMb)
        enqueueOnce(context, if (sizeMb <= 0) Constraints.NONE else dailyConstraints())
    }

    /** "Refresh now" in Settings: on Wi-Fi, without waiting for the charger. */
    fun refreshNow(context: Context) {
        enqueueOnce(context, Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
    }

    private fun enqueueOnce(context: Context, constraints: Constraints) {
        val request = OneTimeWorkRequestBuilder<SmartDownloadWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun dailyConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .build()

    /** One refresh at a time (the daily and the one-time work can both become due). */
    private val refreshLock = Mutex()

    /** Runs one refresh (or nothing if one is already running). Returns a summary for Settings. */
    suspend fun refresh(
        context: Context,
        isStopped: () -> Boolean,
        onProgress: suspend (String, Float) -> Unit
    ): String {
        if (!refreshLock.tryLock()) return "Already updating"
        try {
            return refreshLocked(context, isStopped, onProgress)
        } finally {
            refreshLock.unlock()
        }
    }

    private suspend fun refreshLocked(
        context: Context,
        isStopped: () -> Boolean,
        onProgress: suspend (String, Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val db = StreamShieldDatabase.getInstance(context)
        val offline = db.offlineDao()
        val library = db.libraryDao()
        val prefsRepo = UserPreferencesRepository(context)
        val prefs = prefsRepo.preferencesFlow.first()
        val now = System.currentTimeMillis()

        val items = offline.allItems()
        val smartItems = items.filter { it.isSmart }
        val budget = prefs.smartSizeMb.toLong() * 1024L * 1024L
        if (budget <= 0) {
            smartItems.forEach { removeSmart(context, it) }
            val summary = "Off"
            prefsRepo.setSmartResult(now, summary)
            return@withContext summary
        }

        // ---- signals ----
        val videos = library.allVideos().associateBy { it.videoId }
        val plays: Map<String, List<PlayEventEntity>> = offline.musicPlays().groupBy { it.videoId }
        val playlists = library.allPlaylists()
        val playlistItems = library.allPlaylistItems()
        val likedId = playlists.firstOrNull { it.builtIn == BuiltInPlaylist.LIKED.name }?.id
        val userPlaylistIds = playlists.filter { it.builtIn == null }.map { it.id }.toSet()
        val liked = playlistItems.filter { it.playlistId == likedId }.map { it.videoId }.toSet()
        val inPlaylist = playlistItems.filter { it.playlistId in userPlaylistIds }.map { it.videoId }.toSet()
        val subscriptions = library.allSubscriptions()
        val subscribedIds = subscriptions.map { it.channelId }.toSet()
        val feedback = library.allFeedback()
        val notInterested = feedback.filter { it.type == FeedbackType.NOT_INTERESTED.name }.map { it.targetId }.toSet()
        val hiddenChannels = feedback.filter { it.type == FeedbackType.HIDE_CHANNEL.name }.map { it.targetId }.toSet()
        val removed = offline.removedSince(SmartRule.removedCutoff(now)).toSet()
        val manual = items.filter { !it.isSmart }.map { it.videoId }.toSet()
        val heard = plays.keys + library.allWatchEntries().map { it.videoId }

        val excluded = notInterested + removed + manual +
            videos.values.filter { it.channelId in hiddenChannels }.map { it.videoId }

        val musicIds = videos.values.filter { it.isMusic }.map { it.videoId }.toSet()
        val candidateIds = musicIds.filter { it in plays || it in liked || it in inPlaylist }
        val signals = candidateIds.mapNotNull { id ->
            val v = videos[id] ?: return@mapNotNull null
            SongSignals(
                song = SongInfo(id, v.title, v.channelId, v.channelName, v.durationSec),
                plays = plays[id].orEmpty(),
                liked = id in liked,
                inPlaylist = id in inPlaylist,
                subscribed = v.channelId in subscribedIds
            )
        }

        onProgress("Choosing songs", 0f)
        val yourMusic = SmartRule.rankYourMusic(signals, now, excluded)

        // ---- Discover: YouTube's related lists for the top songs ----
        val seeds = yourMusic.take(SmartRule.SEED_COUNT)
        val related = HashMap<String, List<SongInfo>>()
        for (seed in seeds) {
            if (isStopped()) break
            try {
                val info = NewPipeSupport.streamInfo(seed.song.videoId)
                related[seed.song.videoId] = NewPipeSupport.relatedSongs(info).map {
                    SongInfo(it.videoId, it.title, channelIdFromUrl(it.channelUrl), it.channelName, it.durationSec)
                }
            } catch (_: Exception) {
                // One seed failing (network, removed video) does not stop the refresh.
            }
        }
        // New listener: recent uploads of subscribed music channels.
        val songsWithPlays = signals.count { it.plays.isNotEmpty() }
        val extra = if (songsWithPlays < SmartRule.NEW_USER_SONGS) {
            val musicChannels = videos.values.filter { it.isMusic }.map { it.channelId }.toSet()
            val channelIds = subscriptions.filter { sub ->
                sub.channelId in musicChannels || sub.name.endsWith(" - Topic") || sub.name.contains("VEVO", ignoreCase = true)
            }.map { it.channelId }.toSet()
            library.allFeedItems()
                .filter { it.channelId in channelIds && it.channelId !in hiddenChannels }
                .sortedByDescending { it.publishedAt }
                .take(60)
                .map { SongInfo(it.videoId, it.title, it.channelId, it.channelName, 0) }
        } else {
            emptyList()
        }
        val discover = SmartRule.rankDiscover(seeds, related, extra, heard, excluded, SmartRule.knownArtists(signals))

        // ---- plan ----
        val downloaded = smartItems.filter { it.isDone && OfflineStorage.exists(context, it.location) }.map { it.videoId }.toSet()
        val plan = SmartRule.plan(yourMusic, discover, budget, downloaded)

        // Songs that dropped out (and unfinished smart rows that are no longer wanted).
        val wanted = plan.all.map { it.song.videoId }.toSet()
        smartItems.filter { it.videoId in plan.remove || (it.videoId !in wanted && it.videoId !in downloaded) }
            .forEach { removeSmart(context, it) }
        plan.keep.forEachIndexed { index, r ->
            offline.updateSmartRank(OfflineItemEntity.idFor(r.song.videoId, true), r.section.name, index)
        }

        // ---- download new songs, best first ----
        val downloader = OfflineDownloader(context, offline, songLengthRange = SmartRule.SONG_LENGTH)
        var added = 0
        plan.download.forEachIndexed { index, r ->
            if (isStopped()) return@forEachIndexed
            val id = OfflineItemEntity.idFor(r.song.videoId, true)
            val existing = offline.getItem(id)
            offline.upsertItem(
                (existing ?: newSmartItem(r, now)).copy(
                    status = OfflineStatus.QUEUED.name,
                    smartSection = r.section.name,
                    smartRank = plan.keep.size + index
                )
            )
            onProgress(r.song.title.ifBlank { "Song" }, index.toFloat() / plan.download.size)
            val outcome = try {
                downloader.run(id, isStopped) { _, _ -> }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                DownloadOutcome.FAILED
            }
            if (outcome == DownloadOutcome.DONE) added++
            if (outcome == DownloadOutcome.SKIPPED) offline.insertRemoved(com.example.data.SmartRemovedEntity(r.song.videoId, now))
        }

        val total = offline.smartItems().count { it.isDone }
        val summary = "$total songs · $added new"
        prefsRepo.setSmartResult(now, summary)
        summary
    }

    /** Deletes a smart download's file and row (the algorithm's choice; not a user removal). */
    private suspend fun removeSmart(context: Context, item: OfflineItemEntity) {
        val dao = StreamShieldDatabase.getInstance(context).offlineDao()
        OfflineDownloader.deleteFiles(context, item)
        dao.deleteItem(item.id)
        // The cover image is shared with a regular download of the same video, if there is one.
        if (dao.allItems().none { it.videoId == item.videoId }) OfflineStorage.deleteThumbnail(context, item.videoId)
    }

    private fun newSmartItem(r: RankedSong, now: Long) = OfflineItemEntity(
        id = OfflineItemEntity.idFor(r.song.videoId, true),
        videoId = r.song.videoId,
        title = r.song.title,
        channelId = r.song.channelId,
        channelName = r.song.channelName,
        durationSec = r.song.durationSec,
        isMusic = true,
        audioOnly = true,
        targetHeight = 0,
        qualityLabel = "",
        origin = OfflineOrigin.SMART.name,
        status = OfflineStatus.QUEUED.name,
        totalBytes = SmartRule.estimatedBytes(r.song.durationSec),
        downloadedBytes = 0L,
        location = "",
        mimeType = "",
        error = "",
        createdAt = now,
        completedAt = 0L,
        smartSection = r.section.name,
        smartRank = 0
    )

    /** ".../channel/UCxxxx" -> "UCxxxx" (empty for other URL shapes). */
    private fun channelIdFromUrl(url: String): String =
        Regex("/channel/(UC[A-Za-z0-9_-]{22})").find(url)?.groupValues?.get(1).orEmpty()
}

/** Runs [SmartDownloads.refresh] under WorkManager (daily, Wi-Fi + charging). */
class SmartDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SmartDownloads.refresh(applicationContext, { isStopped }) { title, fraction ->
                try {
                    setForeground(foreground(title, fraction))
                } catch (_: Exception) {
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Updating smart downloads", 0f)

    private fun foreground(title: String, fraction: Float): ForegroundInfo =
        DownloadNotifications.foregroundInfo(applicationContext, NOTIFICATION_ID, "Smart downloads: $title", fraction)

    companion object {
        private const val NOTIFICATION_ID = 7302
    }
}
