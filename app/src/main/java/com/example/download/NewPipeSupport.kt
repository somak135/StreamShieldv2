package com.example.download

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.TimeUnit

/**
 * NewPipe Extractor (the library NewPipe uses) finds a video's downloadable streams without an
 * account. This file holds its one-time setup, the HTTP client it uses, and the conversion of its
 * stream lists into the plain candidates [StreamChoice] picks from.
 */
object NewPipeSupport {
    /** Desktop Firefox, as NewPipe's own downloader sends for stream requests. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var initialized = false

    fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkHttpExtractorDownloader(http))
                initialized = true
            }
        }
    }

    fun isVisionOsUrl(url: String): Boolean =
        try {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url)
        } catch (_: Exception) {
            false
        }

    fun visionOsUserAgent(): String = YoutubeParsingHelper.getVisionOsUserAgent(null)

    /** Blocking network call; run on an IO thread. */
    fun streamInfo(videoId: String): StreamInfo {
        ensureInit()
        return StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
    }

    fun videoCandidates(info: StreamInfo): List<VideoCandidate> {
        val all = info.videoOnlyStreams.orEmpty() + info.videoStreams.orEmpty()
        return all.mapNotNull { s ->
            if (!s.isUrl || s.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return@mapNotNull null
            val container = when (s.format) {
                MediaFormat.MPEG_4 -> Container.MP4
                MediaFormat.WEBM -> Container.WEBM
                else -> return@mapNotNull null
            }
            val height = if (s.height > 0) s.height else heightFromResolution(s.getResolution().orEmpty())
            if (height <= 0) return@mapNotNull null
            VideoCandidate(
                url = s.content,
                height = height,
                fps = s.fps,
                codec = s.codec.orEmpty().lowercase(),
                bitrate = s.bitrate,
                container = container,
                videoOnly = s.isVideoOnly()
            )
        }
    }

    fun audioCandidates(info: StreamInfo): List<AudioCandidate> =
        info.audioStreams.orEmpty().mapNotNull { a ->
            if (!a.isUrl || a.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return@mapNotNull null
            val format = a.format
            val container = when {
                format == MediaFormat.M4A -> Container.MP4
                format == MediaFormat.WEBMA || format == MediaFormat.WEBMA_OPUS -> Container.WEBM
                else -> return@mapNotNull null
            }
            val type = a.audioTrackType
            AudioCandidate(
                url = a.content,
                bitrate = if (a.bitrate > 0) a.bitrate else a.averageBitrate * 1000,
                codec = a.codec.orEmpty().lowercase(),
                container = container,
                original = type == null || type == AudioTrackType.ORIGINAL
            )
        }

    /** Related videos (YouTube's up-next list) as songs: plain videos only, no live streams. */
    fun relatedSongs(info: StreamInfo): List<RelatedSong> =
        info.relatedItems.orEmpty().mapNotNull { item ->
            val stream = item as? StreamInfoItem ?: return@mapNotNull null
            if (stream.streamType != StreamType.VIDEO_STREAM && stream.streamType != StreamType.AUDIO_STREAM) return@mapNotNull null
            val id = videoIdFromUrl(stream.url) ?: return@mapNotNull null
            RelatedSong(
                videoId = id,
                title = stream.name.orEmpty(),
                channelName = stream.uploaderName.orEmpty(),
                channelUrl = stream.uploaderUrl.orEmpty(),
                durationSec = stream.duration.toInt()
            )
        }

    /**
     * Related videos (YouTube's up-next list) for the Home tab's "For you": plain videos and
     * finished live streams, no live streams.
     */
    fun relatedVideos(info: StreamInfo): List<RelatedVideo> =
        info.relatedItems.orEmpty().mapNotNull { item -> (item as? StreamInfoItem)?.let(::toRelatedVideo) }

    /**
     * Blocking network call: YouTube's video results for [query] (first page), for the Home
     * tab's "For you" (recent searches).
     */
    fun searchVideos(query: String): List<RelatedVideo> {
        ensureInit()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        return SearchInfo.getInfo(service, handler).relatedItems.orEmpty()
            .mapNotNull { item -> (item as? StreamInfoItem)?.let(::toRelatedVideo) }
    }

    /** A plain video or finished live stream as a [RelatedVideo]; null for live streams. */
    private fun toRelatedVideo(stream: StreamInfoItem): RelatedVideo? {
        if (stream.streamType != StreamType.VIDEO_STREAM && stream.streamType != StreamType.POST_LIVE_STREAM) return null
        val id = videoIdFromUrl(stream.url) ?: return null
        val uploadedAt = try {
            stream.uploadDate?.instant?.toEpochMilli() ?: 0L
        } catch (_: Exception) {
            0L
        }
        return RelatedVideo(
            videoId = id,
            title = stream.name.orEmpty(),
            channelId = channelIdFromUrl(stream.uploaderUrl),
            channelName = stream.uploaderName.orEmpty(),
            durationSec = stream.duration.toInt().coerceAtLeast(0),
            viewCount = stream.viewCount.coerceAtLeast(0L),
            uploadedAt = uploadedAt,
            uploadedText = stream.textualUploadDate.orEmpty(),
            isShort = stream.isShortFormContent || stream.url.orEmpty().contains("/shorts/")
        )
    }

    /** Blocking network call: the channel id (UC...) of a channel page such as youtube.com/@name. */
    fun resolveChannelId(url: String): String? {
        ensureInit()
        val id = ChannelInfo.getInfo(url).id
        return id?.takeIf { CHANNEL_ID.matches(it) }
    }

    fun channelIdFromUrl(url: String?): String =
        CHANNEL_IN_URL.find(url.orEmpty())?.groupValues?.get(1).orEmpty()

    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val CHANNEL_IN_URL = Regex("/channel/(UC[A-Za-z0-9_-]{22})")

    fun videoIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return VIDEO_ID.find(url)?.groupValues?.get(1)
    }

    private val VIDEO_ID = Regex("(?:v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})")

    private fun heightFromResolution(resolution: String): Int =
        Regex("^(\\d+)p").find(resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** A related video from YouTube's up-next list (for smart downloads' Discover section). */
data class RelatedSong(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelUrl: String,
    val durationSec: Int
)

/** A video from YouTube's up-next list (for the Home tab's "For you"). 0 = unknown. */
data class RelatedVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int,
    val viewCount: Long,
    val uploadedAt: Long,
    val uploadedText: String,
    val isShort: Boolean
)

/** NewPipe Extractor's HTTP layer, on OkHttp (as in NewPipe's DownloaderImpl). */
class OkHttpExtractorDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(request.url())
            .addHeader("User-Agent", NewPipeSupport.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val text = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString()
            )
        }
    }
}
