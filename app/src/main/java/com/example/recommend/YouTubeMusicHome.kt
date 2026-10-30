package com.example.recommend

import android.webkit.CookieManager
import com.example.download.NewPipeSupport
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.Locale

/** What a YouTube Music home item opens. */
enum class YtmKind { SONG, VIDEO, EPISODE, ALBUM, PLAYLIST, ARTIST, PODCAST, OTHER }

/** One tile or row of a YouTube Music home shelf. */
data class YtmItem(
    val kind: YtmKind,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val videoId: String = "",
    val playlistId: String = "",
    /** Browse id of an album / playlist / artist / podcast page. */
    val browseId: String = "",
    /** 16:9 picture (videos) instead of a square one. */
    val wide: Boolean = false
)

/** A shelf: "Quick picks", "Throwbacks", ... [rows]: songs listed in columns of 4 rows. */
data class YtmShelf(val title: String, val strapline: String, val items: List<YtmItem>, val rows: Boolean)

/** A mood / genre chip at the top of the home page ("Relax", "Workout", ...). */
data class YtmChip(val title: String, val params: String, val selected: Boolean)

data class YtmPage(
    val chips: List<YtmChip>,
    val shelves: List<YtmShelf>,
    /** Token for the next shelves, or "". */
    val continuation: String,
    val visitorData: String
)

/**
 * YouTube Music's own home page (`FEmusic_home`), as the YouTube Music website loads it
 * (InnerTube client WEB_REMIX), without an account: the first shelves with the mood chips, more
 * shelves page by page, and a chip's filtered home. The WebView's YouTube Music cookies are sent
 * along, so the shelves are those of the same (signed-out) visitor that plays music in the app.
 *
 * The response shape was taken from the live site (October 2026): shelves are
 * `musicCarouselShelfRenderer`s holding `musicTwoRowItemRenderer` tiles,
 * `musicResponsiveListItemRenderer` song rows or `musicMultiRowListItemRenderer` podcast
 * episodes; further shelves need the visitor id the first response returns.
 */
object YouTubeMusicHome {
    private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private const val HOME = "FEmusic_home"

    /** The first page; [chipParams]: a chip's filter ("" = the whole home). */
    fun fetch(chipParams: String = ""): YtmPage {
        val body = JSONObject().put("context", context("")).put("browseId", HOME)
        if (chipParams.isNotBlank()) body.put("params", chipParams)
        return parse(post(body, ""))
    }

    /** The shelves after [page] (its [YtmPage.continuation] must not be blank). */
    fun more(page: YtmPage): YtmPage {
        val body = JSONObject().put("context", context(page.visitorData)).put("continuation", page.continuation)
        val next = parse(post(body, page.visitorData))
        return next.copy(chips = page.chips, visitorData = next.visitorData.ifBlank { page.visitorData })
    }

    private fun context(visitorData: String): JSONObject {
        val locale = Locale.getDefault()
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion())
            .put("hl", locale.language.ifBlank { "en" })
            .put("gl", locale.country.ifBlank { "US" })
        if (visitorData.isNotBlank()) client.put("visitorData", visitorData)
        return JSONObject().put("client", client)
    }

    /**
     * The website's client version, as NewPipe Extractor finds it (checks its built-in one, else
     * reads YouTube Music's service worker); a recent known one if that fails.
     */
    private fun clientVersion(): String = try {
        NewPipeSupport.ensureInit()
        YoutubeParsingHelper.getYoutubeMusicClientVersion()
    } catch (_: Exception) {
        FALLBACK_CLIENT_VERSION
    }

    /** Seen on music.youtube.com on 30 Oct 2026. */
    private const val FALLBACK_CLIENT_VERSION = "1.20261027.10.00"

    private fun post(body: JSONObject, visitorData: String): JSONObject {
        NewPipeSupport.ensureInit()
        val version = body.optJSONObject("context")?.optJSONObject("client")?.optString("clientVersion").orEmpty()
        val headers = HashMap<String, List<String>>()
        headers["Origin"] = listOf("https://music.youtube.com")
        headers["Referer"] = listOf("https://music.youtube.com/")
        headers["X-YouTube-Client-Name"] = listOf("67")
        headers["X-YouTube-Client-Version"] = listOf(version)
        if (visitorData.isNotBlank()) headers["X-Goog-Visitor-Id"] = listOf(visitorData)
        webViewCookies()?.let { headers["Cookie"] = listOf(it) }
        val response = NewPipe.getDownloader().postWithContentTypeJson(BROWSE_URL, headers, body.toString().toByteArray())
        if (response.responseCode() != 200) throw IllegalStateException("HTTP ${response.responseCode()}")
        return JSONObject(response.responseBody())
    }

    private fun webViewCookies(): String? = try {
        CookieManager.getInstance().getCookie("https://music.youtube.com")?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    // ---- parsing ------------------------------------------------------------------------------

    fun parse(root: JSONObject): YtmPage {
        val section = root.optJSONObject("continuationContents")?.optJSONObject("sectionListContinuation")
            ?: root.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
        val visitor = root.optJSONObject("responseContext")?.optString("visitorData").orEmpty()
        if (section == null) return YtmPage(emptyList(), emptyList(), "", visitor)
        val chips = section.optJSONObject("header")?.optJSONObject("chipCloudRenderer")?.optJSONArray("chips")
            .objects().mapNotNull { chip ->
                val c = chip.optJSONObject("chipCloudChipRenderer") ?: return@mapNotNull null
                val params = c.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("params").orEmpty()
                val title = text(c.optJSONObject("text"))
                if (title.isBlank()) null else YtmChip(title, params, c.optBoolean("isSelected"))
            }
        val shelves = section.optJSONArray("contents").objects().mapNotNull { shelf(it) }
        val continuation = section.optJSONArray("continuations")?.optJSONObject(0)?.let { c ->
            c.keys().asSequence().mapNotNull { c.optJSONObject(it)?.optString("continuation") }.firstOrNull { it.isNotBlank() }
        }.orEmpty()
        return YtmPage(chips, shelves, continuation, visitor)
    }

    private fun shelf(entry: JSONObject): YtmShelf? {
        val s = entry.optJSONObject("musicCarouselShelfRenderer")
            ?: entry.optJSONObject("musicImmersiveCarouselShelfRenderer") ?: return null
        val header = s.optJSONObject("header")?.let {
            it.optJSONObject("musicCarouselShelfBasicHeaderRenderer") ?: it.optJSONObject("musicImmersiveCarouselShelfBasicHeaderRenderer")
        }
        val title = text(header?.optJSONObject("title"))
        val strapline = text(header?.optJSONObject("strapline"))
        val contents = s.optJSONArray("contents").objects()
        val items = contents.mapNotNull { item(it) }
        if (title.isBlank() || items.isEmpty()) return null
        val rows = contents.any { it.has("musicResponsiveListItemRenderer") || it.has("musicMultiRowListItemRenderer") }
        return YtmShelf(title, strapline, items, rows)
    }

    private fun item(entry: JSONObject): YtmItem? {
        entry.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
            val wide = r.optString("aspectRatio").contains("16_9")
            return fromEndpoint(r.optJSONObject("navigationEndpoint"), text(r.optJSONObject("title")), text(r.optJSONObject("subtitle")), thumbnail(r), wide)
        }
        entry.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
            val columns = r.optJSONArray("flexColumns").objects().map {
                it.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            }
            val title = text(columns.getOrNull(0))
            val subtitle = columns.drop(1).map { text(it) }.filter { it.isNotBlank() }.joinToString(" • ")
            val firstRun = columns.getOrNull(0)?.optJSONArray("runs")?.optJSONObject(0)
            val endpoint = firstRun?.optJSONObject("navigationEndpoint") ?: r.optJSONObject("navigationEndpoint")
            val videoId = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            val parsed = fromEndpoint(endpoint, title, subtitle, thumbnail(r), wide = false)
            return when {
                parsed != null -> parsed
                videoId.isNotBlank() -> YtmItem(YtmKind.SONG, title, subtitle, thumbnail(r), videoId = videoId)
                else -> null
            }
        }
        entry.optJSONObject("musicMultiRowListItemRenderer")?.let { r ->
            val watch = r.optJSONObject("onTap")?.optJSONObject("watchEndpoint") ?: return null
            val videoId = watch.optString("videoId")
            if (videoId.isBlank()) return null
            val subtitle = listOf(text(r.optJSONObject("secondTitle")), text(r.optJSONObject("subtitle"))).filter { it.isNotBlank() }.joinToString(" • ")
            return YtmItem(YtmKind.EPISODE, text(r.optJSONObject("title")), subtitle, thumbnail(r), videoId = videoId, playlistId = watch.optString("playlistId"))
        }
        return null
    }

    private fun fromEndpoint(endpoint: JSONObject?, title: String, subtitle: String, thumb: String, wide: Boolean): YtmItem? {
        if (endpoint == null || title.isBlank()) return null
        endpoint.optJSONObject("watchEndpoint")?.let { w ->
            val videoId = w.optString("videoId")
            if (videoId.isBlank()) return null
            val kind = if (wide) YtmKind.VIDEO else YtmKind.SONG
            return YtmItem(kind, title, subtitle, thumb, videoId = videoId, playlistId = w.optString("playlistId"), wide = wide)
        }
        endpoint.optJSONObject("watchPlaylistEndpoint")?.let { w ->
            val list = w.optString("playlistId")
            if (list.isBlank()) return null
            return YtmItem(YtmKind.PLAYLIST, title, subtitle, thumb, playlistId = list, wide = wide)
        }
        endpoint.optJSONObject("browseEndpoint")?.let { b ->
            val id = b.optString("browseId")
            if (id.isBlank()) return null
            val pageType = b.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
            val kind = when {
                pageType.endsWith("_ALBUM") || pageType.endsWith("_AUDIOBOOK") -> YtmKind.ALBUM
                pageType.endsWith("_PLAYLIST") -> YtmKind.PLAYLIST
                pageType.endsWith("_ARTIST") || pageType.endsWith("_USER_CHANNEL") -> YtmKind.ARTIST
                pageType.contains("PODCAST") -> YtmKind.PODCAST
                else -> YtmKind.OTHER
            }
            return YtmItem(kind, title, subtitle, thumb, browseId = id, wide = wide)
        }
        return null
    }

    /** The largest picture of an item; small list pictures are asked for at tile size. */
    private fun thumbnail(item: JSONObject): String {
        val holder = item.optJSONObject("thumbnailRenderer") ?: item.optJSONObject("thumbnail") ?: return ""
        val thumbs = holder.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: holder.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: return ""
        val best = thumbs.objects().maxByOrNull { it.optInt("width") } ?: return ""
        val url = best.optString("url")
        return if (best.optInt("width") in 1 until 226) SIZE_SUFFIX.replace(url, "=w226-h226") else url
    }

    private val SIZE_SUFFIX = Regex("=w\\d+-h\\d+")

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        return o.optJSONArray("runs").objects().joinToString("") { it.optString("text") }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    /** The page in YouTube Music that an item opens (in the player page). */
    fun urlFor(item: YtmItem, base: String): String = when {
        item.videoId.isNotBlank() && item.playlistId.isNotBlank() -> "$base/watch?v=${item.videoId}&list=${item.playlistId}"
        item.videoId.isNotBlank() -> "$base/watch?v=${item.videoId}"
        item.kind == YtmKind.PLAYLIST && item.browseId.startsWith("VL") -> "$base/playlist?list=${item.browseId.removePrefix("VL")}"
        item.kind == YtmKind.PLAYLIST && item.playlistId.isNotBlank() -> "$base/playlist?list=${item.playlistId}"
        item.kind == YtmKind.ARTIST && item.browseId.startsWith("UC") -> "$base/channel/${item.browseId}"
        item.browseId.isNotBlank() -> "$base/browse/${item.browseId}"
        else -> base
    }
}

/** The music home's lower part: YouTube Music's own shelves (see [YouTubeMusicHome]). */
data class YtmHomeState(
    /** The home's mood chips (from the unfiltered home). */
    val chips: List<YtmChip> = emptyList(),
    /** The selected chip's params; "" = the whole home. */
    val selected: String = "",
    val shelves: List<YtmShelf> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String = "",
    val loadedAt: Long = 0L
)
