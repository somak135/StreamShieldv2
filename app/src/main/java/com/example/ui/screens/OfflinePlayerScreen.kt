package com.example.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.WebVideoState
import com.example.offline.OfflinePlayerController
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral

/**
 * Full-screen player for downloads (works offline): the video, or the cover for a song, with
 * Media3's controls (play/pause, seek, previous/next). In picture-in-picture only the video shows.
 */
@Composable
fun OfflinePlayerScreen(
    controller: OfflinePlayerController,
    video: WebVideoState,
    isInPipMode: Boolean,
    onMinimize: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
    /** The sleep timer button ("23 min" / "end" while it runs). */
    sleepLabel: String = "",
    onOpenSleepTimer: () -> Unit = {}
) {
    BackHandler(enabled = !isInPipMode, onBack = onMinimize)
    Surface(color = Color.Black, modifier = modifier.fillMaxSize().testTag("offline_player")) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isInPipMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMinimize, modifier = Modifier.testTag("offline_minimize")) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            video.title.ifBlank { "Download" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DownloadDone, contentDescription = null, tint = EmeraldSafe, modifier = Modifier.padding(end = 4.dp))
                            Text(
                                video.channel.ifBlank { "Downloaded" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = onOpenSleepTimer, modifier = Modifier.testTag("offline_sleep_timer")) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = "Sleep timer",
                            tint = if (sleepLabel.isNotBlank()) ShieldCoral else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (video.videoWidth > 0) {
                        IconButton(onClick = onEnterPip) {
                            Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Pop out")
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = {
                        controller.playerView.also { (it.parent as? ViewGroup)?.removeView(it) }
                    },
                    update = { view -> view.useController = !isInPipMode },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
