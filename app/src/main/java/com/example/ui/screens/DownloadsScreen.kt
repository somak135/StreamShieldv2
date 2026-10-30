package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.OfflineItemEntity
import com.example.data.OfflineStatus
import com.example.data.formatAgo
import com.example.data.formatDuration
import com.example.data.thumbnailUrl
import com.example.download.DownloadCatalog
import com.example.download.OfflineStorage
import com.example.download.SmartDownloads
import com.example.ui.components.EmptyState
import com.example.ui.components.MenuAction
import com.example.ui.components.OverflowMenu
import com.example.ui.components.SectionHeader
import com.example.ui.theme.ShieldCoral

/** Library → Downloads: your downloads, then smart downloads (songs chosen for you). */
@Composable
fun DownloadsScreen(
    items: List<OfflineItemEntity>,
    smartSizeMb: Int,
    smartSummary: String,
    lastSmartRefreshAt: Long,
    onBack: () -> Unit,
    onPlay: (OfflineItemEntity) -> Unit,
    onDelete: (OfflineItemEntity) -> Unit,
    onRetry: (OfflineItemEntity) -> Unit,
    onRefreshSmart: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manual = items.filter { !it.isSmart }
    val smart = items.filter { it.isSmart }.sortedBy { it.smartRank }
    val smartDone = smart.filter { it.isDone }
    val smartBytes = smartDone.sumOf { it.totalBytes }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Download settings") }
            }
        }

        if (manual.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Download,
                    title = "No downloads yet",
                    message = "Tap Download under a video, in a video's ⋮ menu, or above the tabs while a song plays. Files are saved in Movies/StreamShield and Music/StreamShield."
                )
            }
        } else {
            items(manual, key = { it.id }) { item ->
                OfflineRow(item = item, onPlay = onPlay, onDelete = onDelete, onRetry = onRetry)
            }
        }

        item {
            SectionHeader("Smart downloads") {
                IconButton(onClick = onRefreshSmart, modifier = Modifier.testTag("smart_refresh")) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh now")
                }
            }
        }
        item {
            val status = if (smartSizeMb <= 0) {
                "Off. Turn on in Settings → Downloads."
            } else buildString {
                append("${smartDone.size} songs · ${DownloadCatalog.formatSize(smartBytes).ifBlank { "0 MB" }} of ")
                append(DownloadCatalog.formatSize(smartSizeMb * 1024L * 1024L))
                append(" (≈ ${SmartDownloads.estimatedSongs(smartSizeMb)} songs). ")
                append("Updates daily on Wi-Fi while charging")
                if (lastSmartRefreshAt > 0) append(" · last ${formatAgo(lastSmartRefreshAt)}")
                if (smartSummary.isNotBlank()) append(" ($smartSummary)")
                append(".")
            }
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ShieldCoral, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (smartSizeMb > 0 && smart.isEmpty()) {
            item {
                Text(
                    "Songs you listen to will appear here after the next refresh (play a few songs first).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        items(smart, key = { it.id }) { item ->
            OfflineRow(item = item, onPlay = onPlay, onDelete = onDelete, onRetry = onRetry)
        }
        if (smart.isNotEmpty()) {
            item {
                TextButton(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Change smart download size", color = ShieldCoral)
                }
            }
        }
    }
}

@Composable
private fun OfflineRow(
    item: OfflineItemEntity,
    onPlay: (OfflineItemEntity) -> Unit,
    onDelete: (OfflineItemEntity) -> Unit,
    onRetry: (OfflineItemEntity) -> Unit
) {
    val context = LocalContext.current
    // File checks off every recomposition (the list updates often while a download runs).
    val savedThumb = remember(item.videoId, item.isDone) {
        OfflineStorage.thumbnailFile(context, item.videoId).takeIf { it.exists() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isDone) { onPlay(item) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("offline_item"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(if (item.audioOnly) 64.dp else 120.dp)
                .aspectRatio(if (item.audioOnly) 1f else 16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = savedThumb ?: thumbnailUrl(item.videoId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { "Video" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val detail = when (item.status) {
                OfflineStatus.DONE.name -> listOfNotNull(
                    item.channelName.takeIf { it.isNotBlank() },
                    item.qualityLabel.takeIf { it.isNotBlank() },
                    formatDuration(item.durationSec).takeIf { it.isNotBlank() },
                    DownloadCatalog.formatSize(item.totalBytes).takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                OfflineStatus.DOWNLOADING.name -> {
                    val size = DownloadCatalog.formatSize(item.totalBytes)
                    "Downloading ${(item.progress * 100).toInt()} %" + if (size.isNotEmpty()) " of $size" else ""
                }
                OfflineStatus.QUEUED.name -> if (item.isSmart) "Waiting for Wi-Fi and charger" else "Waiting…"
                else -> "Failed: " + item.error.ifBlank { "unknown error" }
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == OfflineStatus.FAILED.name) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.status == OfflineStatus.DOWNLOADING.name) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    color = ShieldCoral,
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
        val actions = buildList {
            if (item.status == OfflineStatus.FAILED.name) add(MenuAction("Retry", Icons.Default.Refresh) { onRetry(item) })
            add(MenuAction(if (item.isDone) "Delete download" else "Cancel download", Icons.Default.Delete) { onDelete(item) })
        }
        OverflowMenu(actions)
    }
}
