package com.example.recommend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The topics of the videos watched in the app (YouTube category and the creator's keywords, as
 * the page reports them once a video plays), newest first, for Home's topic chips. Kept in
 * `home_topics.json`; cleared with the watch history.
 */
class HomeTopics(private val context: Context) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val entries = ArrayList<TopicVideo>()
    private var loaded = false

    @Synchronized
    fun all(): List<TopicVideo> {
        load()
        return entries.toList()
    }

    /** Called on the WebView's bridge thread (one small write per watched video). */
    @Synchronized
    fun record(video: TopicVideo) {
        load()
        entries.removeAll { it.videoId == video.videoId }
        entries.add(0, video)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save()
    }

    @Synchronized
    fun remove(videoId: String) {
        load()
        if (entries.removeAll { it.videoId == videoId }) save()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        loaded = true
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val kw = o.optJSONArray("kw")
                entries += TopicVideo(
                    videoId = o.optString("id"),
                    channelKey = o.optString("ch"),
                    channelName = o.optString("chn"),
                    category = o.optString("cat"),
                    keywords = if (kw == null) emptyList() else (0 until kw.length()).map { kw.optString(it) },
                    at = o.optLong("at")
                )
            }
        } catch (_: Exception) {
            entries.clear()
        }
    }

    private fun save() {
        try {
            val array = JSONArray()
            for (e in entries) {
                array.put(
                    JSONObject()
                        .put("id", e.videoId)
                        .put("ch", e.channelKey)
                        .put("chn", e.channelName)
                        .put("cat", e.category)
                        .put("kw", JSONArray(e.keywords))
                        .put("at", e.at)
                )
            }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val FILE_NAME = "home_topics.json"
        const val MAX_ENTRIES = 400

        /** The page's report: {id, cat, kw: [...], ch: channel id, chn: channel name}. */
        fun parse(json: String, now: Long): TopicVideo? = try {
            val o = JSONObject(json)
            val id = o.optString("id")
            val kw = o.optJSONArray("kw")
            val channelId = o.optString("ch")
            val channelName = o.optString("chn")
            if (id.isBlank()) null else TopicVideo(
                videoId = id,
                channelKey = channelId.ifBlank { channelName.trim().lowercase() },
                channelName = channelName,
                category = o.optString("cat"),
                keywords = if (kw == null) emptyList() else (0 until kw.length()).map { kw.optString(it) }.filter { it.isNotBlank() }.take(30),
                at = now
            )
        } catch (_: Exception) {
            null
        }
    }
}
