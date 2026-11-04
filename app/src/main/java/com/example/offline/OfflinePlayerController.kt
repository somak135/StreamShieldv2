package com.example.offline

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.OfflineItemEntity
import com.example.data.WebVideoState
import com.example.download.OfflineStorage
import com.example.service.PlaybackCommand
import com.example.service.PlaybackCommands
import com.example.ui.PlaybackSource
import com.example.ui.StreamShieldViewModel
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays downloaded files (no network). Reports its state to the view model in the same shape as
 * the web player, so the mini-player, notification, lock screen, PiP and history work the same,
 * and takes the media buttons while it is the active player.
 */
class OfflinePlayerController(
    private val activity: ComponentActivity,
    private val viewModel: StreamShieldViewModel
) {
    val player: ExoPlayer = ExoPlayer.Builder(activity)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    /** Created once and moved between hosts (like the WebViews). */
    // Screen-on is handled by the activity (only while a video plays), not by this view.
    val playerView: PlayerView = PlayerView(activity).apply {
        player = this@OfflinePlayerController.player
    }

    private var queue: List<OfflineItemEntity> = emptyList()
    private var released = false
    private var positionJob: Job? = null
    /** Paused at the end of a song because the app asked so (back to YouTube Music next). */
    private var pausedAtEnd = false

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                pausedAtEnd = !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            }

            // Position updates (mini-player bar, history) only while something plays.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                positionJob?.cancel()
                positionJob = if (isPlaying) {
                    activity.lifecycleScope.launch {
                        while (true) {
                            delay(1_000L)
                            publish()
                        }
                    }
                } else null
            }

            // A file that is gone (deleted by the smart-download refresh or a file manager):
            // skip to the next one instead of stopping.
            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        activity.lifecycleScope.launch {
            viewModel.offlinePlayRequests.collect { request -> play(request.items, request.index, request.startMs) }
        }
        // Downloaded songs playing while the connection was gone: once it is back, stop at the
        // end of the song (YouTube Music continues then).
        activity.lifecycleScope.launch {
            viewModel.offlinePauseAtEnd.collect { pause -> player.pauseAtEndOfMediaItems = pause }
        }
        activity.lifecycleScope.launch {
            viewModel.playbackSource.collect { source ->
                if (source != PlaybackSource.OFFLINE && player.isPlaying) player.pause()
            }
        }
        activity.lifecycleScope.launch {
            PlaybackCommands.flow.collect { command ->
                if (viewModel.playbackSource.value != PlaybackSource.OFFLINE) return@collect
                when (command) {
                    PlaybackCommand.PLAY -> player.play()
                    PlaybackCommand.PAUSE, PlaybackCommand.STOP -> player.pause()
                    PlaybackCommand.TOGGLE -> if (player.isPlaying) player.pause() else play()
                    PlaybackCommand.NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                    PlaybackCommand.PREVIOUS -> previous()
                }
            }
        }
        activity.lifecycleScope.launch {
            viewModel.preferences.collect { prefs ->
                player.setPlaybackSpeed(prefs.playbackSpeed)
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        pausedAtEnd = false
        player.play()
    }

    fun play(items: List<OfflineItemEntity>, index: Int, startMs: Long = 0L) {
        if (released || items.isEmpty()) return
        activity.lifecycleScope.launch {
            // Cover images are read from disk off the main thread.
            val mediaItems = withContext(Dispatchers.IO) { items.map { mediaItem(it) } }
            if (released) return@launch
            queue = items
            pausedAtEnd = false
            player.setMediaItems(mediaItems, index.coerceIn(0, items.lastIndex), startMs.coerceAtLeast(0L))
            player.prepare()
            player.play()
        }
    }

    private fun previous() {
        if (player.currentPosition > 5_000L || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    private fun mediaItem(item: OfflineItemEntity): MediaItem {
        val thumb = OfflineStorage.thumbnailFile(activity, item.videoId)
        val artwork = if (thumb.exists()) runCatching { thumb.readBytes() }.getOrNull() else null
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.channelName)
            .apply { if (artwork != null) setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            .build()
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(OfflineStorage.playableUri(item.location))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun publish() {
        if (released) return
        val item = queue.getOrNull(player.currentMediaItemIndex) ?: return
        val durationMs = player.duration
        val durationSec = if (durationMs != C.TIME_UNSET && durationMs > 0) (durationMs / 1000).toInt() else item.durationSec
        val size = player.videoSize
        viewModel.onOfflineState(
            WebVideoState(
                videoId = item.videoId,
                url = "offline:" + item.id,
                title = item.title,
                channel = item.channelName,
                durationSec = durationSec,
                isPlaying = player.isPlaying,
                videoWidth = size.width,
                videoHeight = size.height,
                channelId = item.channelId,
                lengthSec = durationSec,
                category = if (item.isMusic) "Music" else "",
                positionSec = (player.currentPosition / 1000).toInt(),
                ended = player.playbackState == Player.STATE_ENDED || pausedAtEnd
            )
        )
    }

    fun release() {
        if (released) return
        released = true
        positionJob?.cancel()
        playerView.player = null
        player.release()
    }
}
