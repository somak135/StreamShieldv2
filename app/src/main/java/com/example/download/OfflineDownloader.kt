package com.example.download

import android.content.Context
import com.example.data.OfflineDao
import com.example.data.OfflineItemEntity
import com.example.data.OfflineOrigin
import com.example.data.OfflineStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException

/** How one download attempt ended. */
enum class DownloadOutcome { DONE, RETRY, FAILED, SKIPPED }

/**
 * Downloads one offline item: finds its streams, fetches them, merges / remuxes them and saves
 * the result (public folder for manual downloads, inside the app for smart downloads).
 * Blocking; run on an IO thread.
 */
class OfflineDownloader(
    private val context: Context,
    private val dao: OfflineDao,
    /** Smart downloads only keep songs of this length (seconds); manual downloads take anything. */
    private val songLengthRange: IntRange? = null
) {
    private class Progress {
        @Volatile var done: Long = 0L
        @Volatile var total: Long = 0L
    }

    suspend fun run(
        itemId: String,
        isStopped: () -> Boolean,
        onProgress: suspend (title: String, fraction: Float) -> Unit
    ): DownloadOutcome {
        val item = dao.getItem(itemId) ?: return DownloadOutcome.FAILED
        if (item.isDone && OfflineStorage.exists(context, item.location)) return DownloadOutcome.DONE
        dao.updateStatus(itemId, OfflineStatus.DOWNLOADING.name, "")
        val tempBase = tempBaseFor(context, item)
        try {
            var info = NewPipeSupport.streamInfo(item.videoId)
            val duration = info.duration
            if (songLengthRange != null && duration > 0 && duration.toInt() !in songLengthRange) {
                dao.deleteItem(itemId)
                return DownloadOutcome.SKIPPED
            }
            var plan = choosePlan(info, item) ?: throw IOException("No downloadable stream for this video")
            val title = info.name.orEmpty().ifBlank { item.title }
            val channel = info.uploaderName.orEmpty().ifBlank { item.channelName }

            var parts = Parts(null, null)
            val progress = Progress()
            var lastReport = 0L
            val estimated = plan.estimatedBytes(duration)
            suspend fun report(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastReport < 750) return
                lastReport = now
                val total = if (progress.total > 0) progress.total else estimated
                dao.updateProgress(itemId, OfflineStatus.DOWNLOADING.name, progress.done, total)
                onProgress(title, if (total > 0) (progress.done.toFloat() / total).coerceIn(0f, 1f) else 0f)
            }

            // Fetch (re-resolving if a stream link expired on the way), reporting progress meanwhile.
            coroutineScope {
                val reporter = launch {
                    while (true) {
                        delay(750)
                        report(force = true)
                    }
                }
                try {
                    var attempt = 0
                    while (true) {
                        try {
                            parts = fetchAll(plan, tempBase, progress, isStopped)
                            break
                        } catch (e: ExpiredUrlException) {
                            attempt++
                            if (attempt > 2) throw e
                            info = NewPipeSupport.streamInfo(item.videoId)
                            plan = choosePlan(info, item) ?: throw IOException("No downloadable stream for this video")
                        }
                    }
                } finally {
                    reporter.cancel()
                }
            }
            report(force = true)

            // Merge / remux into the final file.
            val output = File(tempBase.path + "-out")
            var container = plan.container
            var label = plan.label
            val videoFile = parts.video
            val audioFile = parts.audio
            val result: File = when {
                videoFile != null && audioFile != null -> try {
                    MediaRemuxer.remux(listOf(videoFile, audioFile), output, plan.container)
                    output
                } catch (e: IOException) {
                    // Could not merge on this phone: fall back to a stream with sound built in.
                    val fallback = StreamChoice.progressivePlan(NewPipeSupport.videoCandidates(info), item.targetHeight)
                        ?: throw e
                    val single = fetchAll(fallback, tempBase, Progress(), isStopped).video ?: throw e
                    container = fallback.container
                    label = fallback.label
                    single
                }
                audioFile != null && plan.container == Container.MP4 -> try {
                    // YouTube's audio is a fragmented MP4; a plain .m4a works in every player.
                    MediaRemuxer.remux(listOf(audioFile), output, Container.MP4)
                    output
                } catch (_: IOException) {
                    audioFile
                }
                audioFile != null -> audioFile
                videoFile != null -> videoFile
                else -> throw IOException("Nothing was downloaded")
            }
            if (isStopped()) throw CancellationException("Download stopped")

            val audioOnly = item.audioOnly
            val location = if (item.origin == OfflineOrigin.SMART.name) {
                OfflineStorage.saveSmart(context, result, item.videoId, container)
            } else {
                OfflineStorage.savePublic(context, result, title, container, audioOnly)
            }
            if (dao.getItem(itemId) == null) {
                // Deleted while it was being fetched: do not leave a file nothing refers to.
                OfflineStorage.delete(context, location)
                cleanTemp(tempBase)
                throw CancellationException("Download deleted")
            }
            OfflineStorage.saveThumbnail(context, item.videoId)
            dao.markDone(
                id = itemId,
                location = location,
                mimeType = OfflineStorage.mimeType(container, audioOnly),
                qualityLabel = label,
                size = result.length(),
                completedAt = System.currentTimeMillis(),
                title = title,
                channelName = channel,
                durationSec = if (duration > 0) duration.toInt() else item.durationSec
            )
            onProgress(title, 1f)
            cleanTemp(tempBase)
            return DownloadOutcome.DONE
        } catch (e: CancellationException) {
            // Stopped (user deleted it, or the conditions for the work are gone): resume later.
            withContext(NonCancellable) {
                if (dao.getItem(itemId) != null) dao.updateStatus(itemId, OfflineStatus.QUEUED.name, "")
            }
            throw e
        } catch (e: IOException) {
            dao.updateStatus(itemId, OfflineStatus.FAILED.name, DownloadErrors.describe(e))
            return DownloadOutcome.RETRY
        } catch (e: Exception) {
            // Extraction errors (age-restricted, unavailable, YouTube changes): not worth retrying.
            dao.updateStatus(itemId, OfflineStatus.FAILED.name, DownloadErrors.describe(e))
            cleanTemp(tempBase)
            return DownloadOutcome.FAILED
        }
    }

    private fun choosePlan(info: StreamInfo, item: OfflineItemEntity): DownloadPlan? {
        val audios = NewPipeSupport.audioCandidates(info)
        return if (item.audioOnly) {
            StreamChoice.audioPlan(audios)
        } else {
            StreamChoice.videoPlan(NewPipeSupport.videoCandidates(info), audios, item.targetHeight)
        }
    }

    private class Parts(val video: File?, val audio: File?)

    /**
     * Downloads the plan's streams into temp files named after the stream (itag and size), so a
     * resumed download only continues a file of the same stream; leftovers of other streams
     * (another quality, an expired link resolved to a different stream) are removed.
     */
    private fun fetchAll(plan: DownloadPlan, tempBase: File, progress: Progress, isStopped: () -> Boolean): Parts {
        val videoFile = plan.video?.let { partFile(tempBase, "v", it.url) }
        val audioFile = plan.audio?.let { partFile(tempBase, "a", it.url) }
        val keep = setOfNotNull(videoFile?.name, audioFile?.name)
        tempBase.parentFile?.listFiles()?.forEach { f ->
            val isPart = f.name.startsWith(tempBase.name + "-v-") || f.name.startsWith(tempBase.name + "-a-")
            if (isPart && f.name !in keep) f.delete()
        }
        val parts = listOfNotNull(plan.video?.let { it.url to videoFile!! }, plan.audio?.let { it.url to audioFile!! })
        val totals = LongArray(parts.size) { -1L }
        val done = LongArray(parts.size)
        parts.forEachIndexed { i, (url, file) ->
            totals[i] = RangeFetcher.contentLengthFromUrl(url)
            done[i] = if (file.exists()) file.length() else 0L
        }
        parts.forEachIndexed { i, (url, file) ->
            RangeFetcher.fetch(url, file, isStopped) { d, t ->
                done[i] = d
                if (t > 0) totals[i] = t
                progress.done = done.sum()
                progress.total = if (totals.all { it > 0 }) totals.sum() else 0L
            }
        }
        return Parts(videoFile, audioFile)
    }

    private fun cleanTemp(tempBase: File) = deleteTemp(tempBase)

    companion object {
        /** Manual and smart downloads of the same song use separate temp files. */
        private fun tempBaseFor(context: Context, item: OfflineItemEntity): File {
            val prefix = if (item.origin == OfflineOrigin.SMART.name) "s_" else "m_"
            return File(OfflineStorage.tempDir(context), prefix + item.id.replace(':', '_'))
        }

        private fun partFile(tempBase: File, kind: String, url: String): File {
            val uri = try { android.net.Uri.parse(url) } catch (_: Exception) { null }
            val itag = uri?.getQueryParameter("itag").orEmpty()
            val clen = uri?.getQueryParameter("clen").orEmpty()
            val key = (itag + "_" + clen).ifBlank { url.hashCode().toString() }
            return File(tempBase.path + "-" + kind + "-" + key)
        }

        private fun deleteTemp(tempBase: File) {
            tempBase.parentFile?.listFiles()?.forEach { f ->
                if (f.name.startsWith(tempBase.name + "-")) f.delete()
            }
        }

        /** Removes a download's file and leftovers. */
        fun deleteFiles(context: Context, item: OfflineItemEntity) {
            OfflineStorage.delete(context, item.location)
            deleteTemp(tempBaseFor(context, item))
        }
    }
}
