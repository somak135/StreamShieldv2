package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.BuiltInPlaylist
import com.example.data.PlaylistSummary
import com.example.data.PlaylistVideo
import com.example.data.WatchedVideo
import com.example.data.formatAgo
import com.example.ui.DownloadTarget
import com.example.ui.LibraryRoute
import com.example.ui.theme.ShieldCoral
import com.example.ui.components.EmptyState
import com.example.ui.components.MenuAction
import com.example.ui.components.SectionHeader
import com.example.ui.components.VideoCard
import com.example.ui.components.VideoRow
import com.example.ui.components.VideoThumbnail
import kotlinx.coroutines.flow.Flow

/** Library tab: history, Watch later, Liked, playlists, settings. */
@Composable
fun LibraryScreen(
    route: LibraryRoute,
    history: List<WatchedVideo>,
    playlists: List<PlaylistSummary>,
    /** Started but not finished videos (moved here from Home, as in the YouTube app's You tab). */
    continueWatching: List<WatchedVideo> = emptyList(),
    historyPaused: Boolean,
    playlistItems: (Long) -> Flow<List<PlaylistVideo>>,
    onNavigate: (LibraryRoute) -> Unit,
    onOpenVideo: (videoId: String, startSec: Int) -> Unit,
    onDeleteHistoryEntry: (String) -> Unit,
    onClearHistory: () -> Unit,
    onToggleHistoryPaused: (Boolean) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRemoveFromPlaylist: (Long, String) -> Unit,
    onMovePlaylistItem: (List<PlaylistVideo>, Int, Int) -> Unit,
    onOpenSettings: () -> Unit,
    downloadsSummary: String,
    onDownload: (DownloadTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    when (route) {
        LibraryRoute.Root -> LibraryRoot(
            history = history,
            continueWatching = continueWatching,
            playlists = playlists,
            downloadsSummary = downloadsSummary,
            onNavigate = onNavigate,
            onOpenVideo = onOpenVideo,
            onCreatePlaylist = onCreatePlaylist,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        // Shown by the activity (DownloadsScreen); nothing to draw here.
        LibraryRoute.Downloads -> Unit
        LibraryRoute.History -> HistoryList(
            history = history,
            historyPaused = historyPaused,
            onBack = { onNavigate(LibraryRoute.Root) },
            onOpenVideo = onOpenVideo,
            onDelete = onDeleteHistoryEntry,
            onClear = onClearHistory,
            onTogglePaused = onToggleHistoryPaused,
            onDownload = onDownload,
            modifier = modifier
        )
        is LibraryRoute.Playlist -> {
            val summary = playlists.firstOrNull { it.id == route.id }
            val items by remember(route.id) { playlistItems(route.id) }.collectAsState(initial = emptyList())
            PlaylistDetail(
                summary = summary,
                items = items,
                onBack = { onNavigate(LibraryRoute.Root) },
                onOpenVideo = onOpenVideo,
                onRename = { name -> onRenamePlaylist(route.id, name) },
                onDelete = { onDeletePlaylist(route.id) },
                onRemove = { videoId -> onRemoveFromPlaylist(route.id, videoId) },
                onMove = onMovePlaylistItem,
                onDownload = onDownload,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun LibraryRoot(
    history: List<WatchedVideo>,
    continueWatching: List<WatchedVideo>,
    playlists: List<PlaylistSummary>,
    downloadsSummary: String,
    onNavigate: (LibraryRoute) -> Unit,
    onOpenVideo: (String, Int) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier
) {
    var creating by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("library_settings")) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(LibraryRoute.Downloads) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("library_downloads"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.DownloadForOffline, contentDescription = null, tint = ShieldCoral)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Downloads", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(downloadsSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item { SectionHeader("Continue watching") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(continueWatching, key = { "continue_" + it.videoId }) { video ->
                        VideoCard(
                            videoId = video.videoId,
                            title = video.title,
                            subtitle = video.channelName,
                            durationSec = video.durationSec,
                            progress = video.watchedFraction,
                            width = 180.dp,
                            onClick = { onOpenVideo(video.videoId, video.positionSec) }
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("History") {
                TextButton(onClick = { onNavigate(LibraryRoute.History) }) { Text("View all") }
            }
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "Videos you watch appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(history.take(15), key = { it.videoId }) { video ->
                        VideoCard(
                            videoId = video.videoId,
                            title = video.title,
                            subtitle = video.channelName,
                            durationSec = video.durationSec,
                            progress = video.watchedFraction,
                            width = 150.dp,
                            onClick = { onOpenVideo(video.videoId, if (video.watchedFraction < 0.9f) video.positionSec else 0) }
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("Playlists") {
                IconButton(onClick = { creating = true }, modifier = Modifier.testTag("library_new_playlist")) {
                    Icon(Icons.Default.Add, contentDescription = "New playlist")
                }
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(playlist = playlist, onClick = { onNavigate(LibraryRoute.Playlist(playlist.id)) })
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                onCreatePlaylist(name)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    val icon: ImageVector = when (playlist.builtIn) {
        BuiltInPlaylist.WATCH_LATER.name -> Icons.Default.WatchLater
        BuiltInPlaylist.LIKED.name -> Icons.Default.ThumbUp
        else -> Icons.AutoMirrored.Filled.PlaylistPlay
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val first = playlist.firstVideoId
        if (first != null) {
            VideoThumbnail(videoId = first, durationSec = 0, modifier = Modifier.width(110.dp))
        } else {
            Column(modifier = Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  ${playlist.name}", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "${playlist.itemCount} video${if (playlist.itemCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TitleBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun HistoryList(
    history: List<WatchedVideo>,
    historyPaused: Boolean,
    onBack: () -> Unit,
    onOpenVideo: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onTogglePaused: (Boolean) -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    modifier: Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        TitleBar("History", onBack) {
            IconButton(onClick = { confirmClear = true }, enabled = history.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear history")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pause history", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = historyPaused, onCheckedChange = onTogglePaused, modifier = Modifier.testTag("history_pause_switch"))
        }
        if (history.isEmpty()) {
            EmptyState(Icons.Default.History, "No history", "Videos you watch appear here.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(history, key = { it.videoId }) { video ->
                VideoRow(
                    videoId = video.videoId,
                    title = video.title,
                    subtitle = listOfNotNull(
                        video.channelName.takeIf { it.isNotBlank() },
                        formatAgo(video.lastWatchedAt)
                    ).joinToString(" • "),
                    durationSec = video.durationSec,
                    progress = video.watchedFraction,
                    onClick = { onOpenVideo(video.videoId, if (video.watchedFraction < 0.9f) video.positionSec else 0) },
                    menu = listOf(
                        MenuAction("Download", Icons.Default.Download) {
                            onDownload(DownloadTarget(video.videoId, video.title, video.channelName, video.channelId, video.durationSec, video.isMusic))
                        },
                        MenuAction("Remove from history", Icons.Default.Delete) { onDelete(video.videoId) }
                    )
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("This removes all watched videos and resume positions from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PlaylistDetail(
    summary: PlaylistSummary?,
    items: List<PlaylistVideo>,
    onBack: () -> Unit,
    onOpenVideo: (String, Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (List<PlaylistVideo>, Int, Int) -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    modifier: Modifier
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val editable = summary?.builtIn == null
    Column(modifier = modifier.fillMaxSize()) {
        TitleBar(summary?.name ?: "Playlist", onBack) {
            if (editable && summary != null) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete playlist") }
            }
        }
        if (items.isEmpty()) {
            EmptyState(
                Icons.Default.VideoLibrary,
                "Nothing here yet",
                "Open a video and use Save to add it to this list."
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(items, key = { _, item -> item.videoId }) { index, video ->
                val menu = buildList {
                    add(MenuAction("Download", Icons.Default.Download) {
                        onDownload(
                            DownloadTarget(
                                video.videoId, video.title, video.channelName, video.channelId, video.durationSec,
                                isMusic = summary?.kind == "MUSIC"
                            )
                        )
                    })
                    if (index > 0) add(MenuAction("Move up", Icons.Default.ArrowUpward) { onMove(items, index, -1) })
                    if (index < items.lastIndex) add(MenuAction("Move down", Icons.Default.ArrowDownward) { onMove(items, index, 1) })
                    add(MenuAction("Remove", Icons.Default.Delete) { onRemove(video.videoId) })
                }
                VideoRow(
                    videoId = video.videoId,
                    title = video.title,
                    subtitle = video.channelName,
                    durationSec = video.durationSec,
                    onClick = { onOpenVideo(video.videoId, 0) },
                    menu = menu
                )
            }
        }
    }
    if (renaming && summary != null) {
        NameDialog("Rename playlist", summary.name, "Rename", onConfirm = {
            onRename(it)
            renaming = false
        }, onDismiss = { renaming = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("The videos themselves are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
