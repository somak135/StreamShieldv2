package com.example.download

/** File container of a stream / of the saved file. MP4 audio is the .m4a format. */
enum class Container { MP4, WEBM }

data class VideoCandidate(
    val url: String,
    val height: Int,
    val fps: Int,
    /** e.g. "avc1.64001f", "vp9", "av01..." (lower case). */
    val codec: String,
    /** Bits per second (0 if unknown). */
    val bitrate: Int,
    val container: Container,
    /** False for the few streams that already contain sound ("progressive", e.g. 360p MP4). */
    val videoOnly: Boolean
)

data class AudioCandidate(
    val url: String,
    val bitrate: Int,
    val codec: String,
    val container: Container,
    /** The original language track (not a dubbed or descriptive one). */
    val original: Boolean
)

/** What to download for one file: a video stream and/or an audio stream, and the result format. */
data class DownloadPlan(
    val video: VideoCandidate?,
    val audio: AudioCandidate?,
    val container: Container,
    val label: String
) {

    fun estimatedBytes(durationSec: Long): Long {
        val bitsPerSec = (video?.bitrate ?: 0).toLong() + (audio?.bitrate ?: 0).toLong()
        return if (durationSec > 0 && bitsPerSec > 0) bitsPerSec * durationSec / 8 else 0L
    }
}

/**
 * Picks streams. Android can merge H.264 video with AAC (.m4a) audio into an MP4, and VP9 video
 * with Opus audio into a WebM; those are the only combinations offered. Streams with sound
 * built in (usually 360p) are the fallback.
 */
object StreamChoice {

    private fun isAvc(v: VideoCandidate) = v.videoOnly && v.container == Container.MP4 && v.codec.startsWith("avc")
    private fun isVp9(v: VideoCandidate) = v.videoOnly && v.container == Container.WEBM &&
        (v.codec.startsWith("vp9") || v.codec.startsWith("vp09"))

    /** Best audio in [container]: original track first, then the highest bitrate. */
    fun bestAudio(audios: List<AudioCandidate>, container: Container): AudioCandidate? =
        audios.filter { it.container == container }
            .sortedWith(compareByDescending<AudioCandidate> { it.original }.thenByDescending { it.bitrate })
            .firstOrNull()

    /** Audio only: AAC (.m4a, plays everywhere) if available, else Opus (.webm). */
    fun audioPlan(audios: List<AudioCandidate>): DownloadPlan? {
        val m4a = bestAudio(audios, Container.MP4)
        if (m4a != null) return DownloadPlan(null, m4a, Container.MP4, audioLabel(m4a))
        val opus = bestAudio(audios, Container.WEBM) ?: return null
        return DownloadPlan(null, opus, Container.WEBM, audioLabel(opus))
    }

    /** Video heights that can be downloaded with sound, highest first. */
    fun availableHeights(videos: List<VideoCandidate>, audios: List<AudioCandidate>): List<Int> =
        options(videos, audios).map { it.video!!.height }.distinct().sortedDescending()

    /**
     * The video plan for [targetHeight]: the highest available height not above it (or the
     * lowest available if all are above). At equal height: H.264 + AAC, then VP9 + Opus, then a
     * stream with sound built in; among those, the highest bitrate.
     */
    fun videoPlan(videos: List<VideoCandidate>, audios: List<AudioCandidate>, targetHeight: Int): DownloadPlan? {
        val all = options(videos, audios)
        if (all.isEmpty()) return null
        val fitting = all.filter { it.video!!.height <= targetHeight }
        val pool = if (fitting.isNotEmpty()) {
            val best = fitting.maxOf { it.video!!.height }
            fitting.filter { it.video!!.height == best }
        } else {
            val lowest = all.minOf { it.video!!.height }
            all.filter { it.video!!.height == lowest }
        }
        return pool.sortedWith(
            compareBy<DownloadPlan> { kindRank(it) }
                .thenBy { isHdr(it.video!!) }
                .thenByDescending { it.video!!.bitrate }
        ).first()
    }

    /** A video download for the progressive fallback (merging failed): sound built in. */
    fun progressivePlan(videos: List<VideoCandidate>, targetHeight: Int): DownloadPlan? {
        val progressive = videos.filter { !it.videoOnly }
        if (progressive.isEmpty()) return null
        val fitting = progressive.filter { it.height <= targetHeight }
        val pick = (if (fitting.isNotEmpty()) fitting.maxByOrNull { it.height } else progressive.minByOrNull { it.height })
            ?: return null
        return DownloadPlan(pick, null, pick.container, videoLabel(pick))
    }

    private fun options(videos: List<VideoCandidate>, audios: List<AudioCandidate>): List<DownloadPlan> {
        val m4a = bestAudio(audios, Container.MP4)
        val opus = bestAudio(audios, Container.WEBM)
        val plans = mutableListOf<DownloadPlan>()
        if (m4a != null) videos.filter { isAvc(it) }.forEach { plans.add(DownloadPlan(it, m4a, Container.MP4, videoLabel(it))) }
        if (opus != null) videos.filter { isVp9(it) }.forEach { plans.add(DownloadPlan(it, opus, Container.WEBM, videoLabel(it))) }
        videos.filter { !it.videoOnly }.forEach { plans.add(DownloadPlan(it, null, it.container, videoLabel(it))) }
        return plans
    }

    /** VP9 profile 2/3 ("vp09.02...", "vp09.03..."): HDR / 10-bit, which many phones cannot play. */
    private fun isHdr(v: VideoCandidate): Boolean = v.codec.startsWith("vp09.02") || v.codec.startsWith("vp09.03")

    private fun kindRank(plan: DownloadPlan): Int = when {
        plan.audio == null -> 2
        plan.container == Container.MP4 -> 0
        else -> 1
    }

    fun videoLabel(v: VideoCandidate): String = "${v.height}p" + if (v.fps > 30) v.fps.toString() else ""

    fun audioLabel(a: AudioCandidate): String {
        val kbps = a.bitrate / 1000
        return if (kbps > 0) "Audio · $kbps kbps" else "Audio"
    }
}
