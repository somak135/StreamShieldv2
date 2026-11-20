package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.download.DownloadCatalog
import com.example.download.QualityOption
import com.example.ui.DownloadSheetState
import com.example.ui.theme.ShieldCoral

/**
 * "Download" sheet: audio only or a video quality, with an estimated size, and "Remember my
 * choice" (for songs and for videos separately; Settings can reset it). Songs pre-select audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualitySheet(
    state: DownloadSheetState,
    onConfirm: (QualityOption, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var rememberChoice by remember { mutableStateOf(false) }
    val defaultHeight = if (state.target.isMusic) 0 else 720
    var selectedHeight by remember(state.options) {
        val heights = state.options.map { it.height }
        val initial = when {
            defaultHeight in heights -> defaultHeight
            defaultHeight == 0 -> heights.firstOrNull() ?: 0
            else -> heights.filter { it in 1..defaultHeight }.maxOrNull() ?: heights.lastOrNull() ?: 0
        }
        mutableIntStateOf(initial)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                state.target.title.ifBlank { "This video" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Finding qualities…", style = MaterialTheme.typography.bodyMedium)
                }
                state.error != null -> Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                else -> {
                    state.options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedHeight = option.height }
                                .padding(vertical = 2.dp)
                                .testTag("download_option_${option.height}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedHeight == option.height, onClick = { selectedHeight = option.height })
                            Icon(
                                if (option.height == 0) Icons.Default.Headphones else Icons.Default.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            val size = DownloadCatalog.formatSize(option.sizeBytes)
                            if (size.isNotEmpty()) {
                                Text("≈ $size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberChoice = !rememberChoice }
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                        Text(
                            if (state.target.isMusic) "Remember my choice for songs" else "Remember my choice for videos",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                val option = state.options.firstOrNull { it.height == selectedHeight }
                Button(
                    onClick = { if (option != null) onConfirm(option, rememberChoice) },
                    enabled = option != null && !state.loading,
                    modifier = Modifier.testTag("download_confirm")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download")
                }
            }
        }
    }
}

/** Above the tabs while a song plays in YouTube Music: download it. */
@Composable
fun NowPlayingDownloadStrip(
    title: String,
    onDownload: () -> Unit,
    /** The sleep timer button at the end ("23 min" / "end" while it runs). */
    sleepLabel: String = "",
    onSleep: (() -> Unit)? = null
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDownload)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("music_download_strip"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = ShieldCoral, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Download “${title.ifBlank { "this song" }}”",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onSleep != null) {
                if (sleepLabel.isNotBlank()) {
                    Text(if (sleepLabel == "end") "end" else sleepLabel, style = MaterialTheme.typography.labelSmall, color = ShieldCoral)
                }
                IconButton(onClick = onSleep, modifier = Modifier.size(36.dp).testTag("music_sleep_timer")) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = "Sleep timer",
                        tint = if (sleepLabel.isNotBlank()) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
