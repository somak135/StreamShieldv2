package com.example.recommend

import com.example.download.NewPipeSupport
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.Locale

/**
 * YouTube's topic pages ([YtPage]: Music, Gaming, Live, ...) as the YouTube website loads them
 * (InnerTube client WEB), without an account and without cookies: one request per page, which
 * holds all its shelves (no further pages).
 *
 * Shapes seen on the live site (3 Nov 2026): Live, News, Sports, Learning, Fashion and the
 * Podcasts episodes are `videoRenderer`s; Gaming has `gridVideoRenderer`s and
 * `videoCardRenderer`s; Music has `lockupViewModel`s (contentType LOCKUP_CONTENT_TYPE_VIDEO, the
 * channel in the first metadata row, "views | age" in the second, the length in a thumbnail
 * badge). Stations, playlists, podcast shows, games and upcoming streams are skipped.
 */
object YouTubeDestinations {
    private const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"
    /** Seen on www.youtube.com on 3 Nov 2026. */
    private const val FALLBACK_CLIENT_VERSION = "2.20261030.01.00"
    private const val MAX_VIDEOS = 120
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Blocking network call: the page's videos, in the page's order. */
    fun fetch(page: YtPage): List<ChipVideo> {
        val locale = Locale.getDefault()
        val version = clientVersion()
        val client = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", version)
            .put("hl", locale.language.ifBlank { "en" })
            .put("gl", locale.country.ifBlank { "US" })
        val body = JSONObject().put("context", JSONObject().put("client", client)).put("browseId", page.browseId)
        if (page.params.isNotBlank()) body.put("params", page.params)
        val headers = HashMap<String, List<String>>()
        headers["Origin"] = listOf("https://www.youtube.com")
        headers["Referer"] = listOf("https://www.youtube.com/")
        headers["X-YouTube-Client-Name"] = listOf("1")
        headers["X-YouTube-Client-Version"] = listOf(version)
        val response = NewPipe.getDownloader().postWithContentTypeJson(BROWSE_URL, headers, body.toString().toByteArray())
        if (response.responseCode() != 200) throw IllegalStateException("HTTP ${response.responseCode()}")
        return parse(JSONObject(response.responseBody()))
    }

    /** The website's client version, as NewPipe Extractor finds it; a recent known one if that fails. */
    private fun clientVersion(): String = try {
        NewPipeSupport.ensureInit()
        YoutubeParsingHelper.getClientVersion()
    } catch (_: Exception) {
        FALLBACK_CLIENT_VERSION
    }

    // ---- parsing ------------------------------------------------------------------------------

    private val VIDEO_RENDERERS = setOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "videoCardRenderer")
    /** Not looked into: Shorts shelves, games, and the lockups handled above. */
    private val SKIPPED = setOf("reelShelfRenderer", "reelItemRenderer", "shortsLockupViewModel", "gameCardRenderer", "lockupViewModel")

    /** All playable videos of a browse response, each once, in order (no Shorts, nothing upcoming). */
    fun parse(root: JSONObject, max: Int = MAX_VIDEOS): List<ChipVideo> {
        val out = LinkedHashMap<String, ChipVideo>()
        fun walk(node: Any?, depth: Int) {
            if (depth > 64 || out.size >= max) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = node.opt(key)
                        val video = when (key) {
                            in VIDEO_RENDERERS -> (value as? JSONObject)?.let(::fromRenderer)
                            "lockupViewModel" -> (value as? JSONObject)?.let(::fromLockup)
                            else -> null
                        }
                        if (video != null) {
                            if (!video.isShort && out.size < max) out.putIfAbsent(video.videoId, video)
                        } else if (key !in VIDEO_RENDERERS && key !in SKIPPED) {
                            walk(value, depth + 1)
                        }
                    }
                }
            }
        }
        walk(root, 0)
        return out.values.toList()
    }

    private fun fromRenderer(r: JSONObject): ChipVideo? {
        val id = r.optString("videoId")
        if (!VIDEO_ID.matches(id) || r.has("upcomingEventData")) return null
        val status = r.optJSONArray("thumbnailOverlays").objects()
            .firstNotNullOfOrNull { it.optJSONObject("thumbnailOverlayTimeStatusRenderer") }
        val style = status?.optString("style").orEmpty()
        if (style == "UPCOMING") return null
        val run = listOf("ownerText", "shortBylineText", "longBylineText", "bylineText")
            .firstNotNullOfOrNull { r.optJSONObject(it)?.optJSONArray("runs")?.optJSONObject(0) }
        val views = text(r.optJSONObject("shortViewCountText")).ifBlank { text(r.optJSONObject("viewCountText")) }
        val age = text(r.optJSONObject("publishedTimeText"))
        val meta = listOf(views, age).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { text(r.optJSONObject("metadataText")) }
        val live = style == "LIVE" || views.contains("watching") ||
            r.optJSONArray("badges").objects().any { it.optJSONObject("metadataBadgeRenderer")?.optString("style").orEmpty().contains("LIVE") }
        val length = text(r.optJSONObject("lengthText")).ifBlank { text(status?.optJSONObject("text")) }
        return ChipVideo(
            videoId = id,
            title = text(r.optJSONObject("title")),
            channelId = run?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty(),
            channelName = run?.optString("text").orEmpty(),
            durationSec = if (live) 0 else HomeChipRule.parseDuration(length),
            meta = meta,
            live = live,
            isShort = style == "SHORTS"
        )
    }

    private fun fromLockup(l: JSONObject): ChipVideo? {
        if (l.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = l.optString("contentId")
        if (!VIDEO_ID.matches(id)) return null
        val md = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val rows = md.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows").objects()
            .map { it.optJSONArray("metadataParts").objects().mapNotNull { p -> p.optJSONObject("text") } }
        val first = rows.getOrNull(0)?.firstOrNull()
        val channelId = first?.optJSONArray("commandRuns")?.optJSONObject(0)?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        val meta = rows.drop(1).flatten().map { it.optString("content") }.filter { it.isNotBlank() }.joinToString(" · ")
        val badge = findBadge(l.optJSONObject("contentImage"), 0)
        val badgeText = badge?.optString("text").orEmpty()
        val badgeStyle = badge?.optString("badgeStyle").orEmpty()
        if (badgeStyle.contains("UPCOMING")) return null
        val live = badgeStyle.contains("LIVE") || badgeText.equals("LIVE", ignoreCase = true) || meta.contains("watching")
        return ChipVideo(
            videoId = id,
            title = md.optJSONObject("title")?.optString("content").orEmpty(),
            channelId = channelId,
            channelName = first?.optString("content").orEmpty(),
            durationSec = if (live) 0 else HomeChipRule.parseDuration(badgeText),
            meta = meta,
            live = live
        )
    }

    /** The first thumbnail badge (length, LIVE) under a lockup's picture. */
    private fun findBadge(node: Any?, depth: Int): JSONObject? {
        if (depth > 12) return null
        when (node) {
            is JSONObject -> {
                node.optJSONObject("thumbnailBadgeViewModel")?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) findBadge(node.opt(keys.next()), depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findBadge(node.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        return o.optJSONArray("runs").objects().joinToString("") { it.optString("text") }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
