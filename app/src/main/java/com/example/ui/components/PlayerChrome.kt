package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.PlaylistSummary
import com.example.data.WebVideoState
import com.example.ui.CurrentVideoActions
import com.example.ui.SLEEP_END_OF_VIDEO
import com.example.ui.screens.NameDialog
import com.example.ui.theme.ShieldCoral

/** Bar above the bottom navigation while a video is loaded in the (hidden) player page. */
@Composable
fun MiniPlayer(
    video: WebVideoState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** While the sleep timer runs ("23 min" / "end"), shown under the title; a tap opens it. */
    sleepLabel: String = "",
    onSleepClick: () -> Unit = {}
) {
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().testTag("mini_player")) {
        Column {
            val duration = video.bestDurationSec
            if (duration > 0) {
                LinearProgressIndicator(
                    progress = { (video.positionSec.toFloat() / duration).coerceIn(0f, 1f) },
                    color = ShieldCoral,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VideoThumbnail(videoId = video.videoId, durationSec = 0, modifier = Modifier.width(96.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(video.title.ifBlank { "Playing" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sleepLabel.isNotBlank()) {
                        Row(
                            modifier = Modifier.clickable(onClick = onSleepClick).testTag("mini_player_sleep"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bedtime, contentDescription = null, tint = ShieldCoral, modifier = Modifier.size(12.dp))
                            Text(
                                " " + sleepText(sleepLabel, video.isMusic),
                                style = MaterialTheme.typography.bodySmall,
                                color = ShieldCoral,
                                maxLines = 1
                            )
                        }
                    } else {
                        Text(
                            video.channel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onTogglePlay, modifier = Modifier.testTag("mini_player_toggle")) {
                    Icon(if (video.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (video.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = onNext, modifier = Modifier.testTag("mini_player_next")) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag("mini_player_close")) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }
    }
}

/** "Pauses in 23 min" / "Pauses at the end of this song" from the view model's sleep label. */
fun sleepText(label: String, music: Boolean): String = when {
    label.isBlank() -> "Off"
    label == "end" -> "Pauses at the end of this ${if (music) "song" else "video"}"
    else -> "Pauses in $label"
}

/** The sleep timer, as in the apps: pause after a while, or at the end of what is playing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(label: String, music: Boolean, onSelect: (minutes: Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "Sleep timer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Text(
                sleepText(label, music),
                style = MaterialTheme.typography.bodySmall,
                color = if (label.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else ShieldCoral,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            listOf(15, 30, 45, 60).forEach { m ->
                ListItem(
                    headlineContent = { Text(if (m == 60) "1 hour" else "$m minutes") },
                    leadingContent = { Icon(Icons.Default.Bedtime, contentDescription = null) },
                    modifier = Modifier.clickable { onSelect(m) }.testTag("sleep_$m")
                )
            }
            ListItem(
                headlineContent = { Text("End of this ${if (music) "song" else "video"}") },
                leadingContent = { Icon(Icons.Default.Bedtime, contentDescription = null) },
                modifier = Modifier.clickable { onSelect(SLEEP_END_OF_VIDEO) }.testTag("sleep_end")
            )
            if (label.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Turn off") },
                    leadingContent = { Icon(Icons.Default.TimerOff, contentDescription = null) },
                    modifier = Modifier.clickable { onSelect(0) }.testTag("sleep_off")
                )
            }
        }
    }
}

/** Library actions for the video open in the player (YouTube's own buttons need a login). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoActionsSheet(
    actions: CurrentVideoActions,
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onTogglePlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onToggleNotInterested: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(actions.title.ifBlank { "This video" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (actions.channelName.isNotBlank()) {
                        Text(actions.channelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                ActionRow(
                    icon = Icons.Default.Subscriptions,
                    label = if (actions.subscribed) "Unsubscribe from ${actions.channelName.ifBlank { "channel" }}"
                    else "Subscribe to ${actions.channelName.ifBlank { "channel" }}",
                    highlighted = actions.subscribed,
                    enabled = actions.channelId.isNotBlank(),
                    onClick = onToggleSubscribe,
                    testTag = "action_subscribe"
                )
            }
            item {
                ActionRow(
                    icon = if (actions.liked) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                    label = if (actions.liked) "Liked" else "Like",
                    highlighted = actions.liked,
                    onClick = onToggleLike,
                    testTag = "action_like"
                )
            }
            item {
                ActionRow(
                    icon = if (actions.inWatchLater) Icons.Default.WatchLater else Icons.Outlined.WatchLater,
                    label = if (actions.inWatchLater) "In Watch later" else "Save to Watch later",
                    highlighted = actions.inWatchLater,
                    onClick = onToggleWatchLater,
                    testTag = "action_watch_later"
                )
            }
            item {
                ActionRow(icon = Icons.Default.Download, label = "Download", onClick = onDownload, testTag = "action_download")
            }
            item {
                ActionRow(icon = Icons.Default.Share, label = "Share", onClick = onShare, testTag = "action_share")
            }
            item {
                ActionRow(
                    icon = Icons.Default.NotInterested,
                    label = if (actions.notInterested) "Marked not interested (undo)" else "Not interested",
                    highlighted = actions.notInterested,
                    onClick = onToggleNotInterested,
                    testTag = "action_not_interested"
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(modifier = Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                    Text("  Save to playlist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = "New playlist") }
                }
            }
            items(playlists.filter { it.builtIn == null }, key = { it.id }) { playlist ->
                val inList = playlist.id in actions.playlistIds
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    leadingContent = {
                        Icon(if (inList) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, contentDescription = null, tint = if (inList) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { onTogglePlaylist(playlist.id) }
                )
            }
        }
    }
    if (creating) {
        NameDialog("New playlist", "", "Create", onConfirm = {
            onCreatePlaylist(it)
            creating = false
        }, onDismiss = { creating = false })
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    highlighted: Boolean = false,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = {
            Text(
                label,
                color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant else if (highlighted) ShieldCoral else MaterialTheme.colorScheme.onSurface
            )
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = if (highlighted) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    )
}
