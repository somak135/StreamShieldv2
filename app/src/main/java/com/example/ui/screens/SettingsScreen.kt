package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Backup
import com.example.data.BackupFolder
import com.example.data.formatAgo
import com.example.data.ShieldPreferencesState
import com.example.data.formatCount
import com.example.data.formatSpeedBadge
import com.example.ui.BlockCounts
import com.example.ui.AutoplayDiagnostics
import com.example.ui.StartDiagnostics
import com.example.download.SmartDownloads
import androidx.compose.material.icons.filled.AutoAwesome
import java.util.Locale
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.ShieldCoral

@Composable
fun SettingsScreen(
    preferences: ShieldPreferencesState,
    blockCounts: BlockCounts,
    lastStart: StartDiagnostics?,
    pipStats: PipStats,
    recentBlocked: List<String>,
    onClose: () -> Unit,
    /** Play/pause taps the page missed and the app applied (this session), and what was on top. */
    tapFixes: Pair<Int, String> = 0 to "",
    /** Autoplay while the app was hidden (this session). */
    autoplayDiagnostics: AutoplayDiagnostics = AutoplayDiagnostics(),
    onToggleAdBlock: (Boolean) -> Unit,
    onToggleSponsorSkip: (Boolean) -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onToggleAutoPip: (Boolean) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onToggleDarkMode: (Boolean) -> Unit,
    onToggleAmoledBlack: (Boolean) -> Unit,
    onResetCounts: () -> Unit,
    onToggleAutoplay: (Boolean) -> Unit,
    onToggleShowStartTime: (Boolean) -> Unit,
    onSetSmartSize: (Int) -> Unit,
    onRefreshSmart: () -> Unit,
    onResetDownloadQuality: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onToggleAutoBackup: (Boolean) -> Unit,
    onChooseBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onImportTakeoutHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to browser")
                    }
                    Text("Settings", style = MaterialTheme.typography.titleLarge)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SettingsSectionCard(title = "Ad shield") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatBlock(value = formatCount(blockCounts.ads), label = "ads blocked", color = ShieldCoral)
                            StatBlock(value = formatCount(blockCounts.requests), label = "ad & tracker requests", color = CyberCyan)
                        }
                        TextButton(
                            onClick = onResetCounts,
                            modifier = Modifier.align(Alignment.End).testTag("reset_counts_button")
                        ) { Text("Reset counters") }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingToggleRow(
                            icon = Icons.Default.Shield,
                            iconTint = ShieldCoral,
                            title = "Block ads & trackers",
                            subtitle = "Removes video ads before they load, hides ad slots and blocks tracking requests",
                            checked = preferences.adBlockEnabled,
                            onCheckedChange = onToggleAdBlock,
                            testTag = "setting_adblock_switch"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingToggleRow(
                            icon = Icons.Default.FastForward,
                            iconTint = CyberCyan,
                            title = "Skip sponsor segments",
                            subtitle = "Skips sponsor reads, self-promotion and subscribe reminders (SponsorBlock)",
                            checked = preferences.sponsorSkipEnabled,
                            onCheckedChange = onToggleSponsorSkip,
                            testTag = "setting_sponsor_skip_switch"
                        )
                    }
                }

                item {
                    DiagnosticsCard(
                        lastStart = lastStart,
                        pipStats = pipStats,
                        tapFixes = tapFixes,
                        autoplay = autoplayDiagnostics,
                        recentBlocked = recentBlocked,
                        showStartTime = preferences.showStartTime,
                        onToggleShowStartTime = onToggleShowStartTime
                    )
                }

                item {
                    SettingsSectionCard(title = "Playback") {
                        SettingToggleRow(
                            icon = Icons.Default.Headphones,
                            iconTint = EmeraldSafe,
                            title = "Background play",
                            subtitle = "Keep playing with the screen off or in another app, with lock-screen controls",
                            checked = preferences.backgroundPlayEnabled,
                            onCheckedChange = onToggleBackgroundPlay,
                            testTag = "setting_bg_play_switch"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingToggleRow(
                            icon = Icons.Default.PictureInPictureAlt,
                            iconTint = CyberCyan,
                            title = "Picture-in-picture on Home",
                            subtitle = "Pop the playing video into a floating window when you leave the app",
                            checked = preferences.autoPipOnLeave,
                            onCheckedChange = onToggleAutoPip,
                            testTag = "setting_auto_pip_switch"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingToggleRow(
                            icon = Icons.Default.SkipNext,
                            iconTint = EmeraldSafe,
                            title = "Autoplay",
                            subtitle = "Play the next video or song when one ends, also with the screen off and in picture-in-picture. When off, only playlists, mixes and YouTube Music's queue continue in the background",
                            checked = preferences.autoplayEnabled,
                            onCheckedChange = onToggleAutoplay,
                            testTag = "setting_autoplay_switch"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSpeedSheet() }
                                .padding(vertical = 6.dp)
                                .testTag("setting_open_speed_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = ShieldCoral, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Playback speed", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (preferences.rememberSpeedAcrossVideos) "Kept for every video" else "Resets to 1.0x on each new video",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = formatSpeedBadge(preferences.playbackSpeed),
                                style = MaterialTheme.typography.titleMedium,
                                color = ShieldCoral,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                item {
                    SettingsSectionCard(title = "Appearance") {
                        SettingToggleRow(
                            icon = Icons.Default.DarkMode,
                            iconTint = ShieldCoral,
                            title = "Dark mode",
                            subtitle = "Dark app chrome (YouTube follows its own theme setting)",
                            checked = preferences.darkMode,
                            onCheckedChange = onToggleDarkMode,
                            testTag = "setting_dark_mode_switch"
                        )
                        if (preferences.darkMode) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            SettingToggleRow(
                                icon = Icons.Default.Contrast,
                                iconTint = CyberCyan,
                                title = "Pure black",
                                subtitle = "True black backgrounds for OLED screens",
                                checked = preferences.amoledBlackMode,
                                onCheckedChange = onToggleAmoledBlack,
                                testTag = "setting_amoled_switch"
                            )
                        }
                    }
                }

                item {
                    DownloadsCard(
                        preferences = preferences,
                        onSetSmartSize = onSetSmartSize,
                        onRefreshSmart = onRefreshSmart,
                        onResetDownloadQuality = onResetDownloadQuality
                    )
                }

                item {
                    SettingsSectionCard(title = "Backup & restore") {
                        Text(
                            text = "No account needed: subscriptions, playlists, likes, history, searches and settings " +
                                "are saved in one file. Import it on a new phone to continue where you left off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f).testTag("backup_export")) {
                                Text("Export")
                            }
                            OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f).testTag("backup_import")) {
                                Text("Import")
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        SettingToggleRow(
                            icon = Icons.Default.Backup,
                            iconTint = CyberCyan,
                            title = "Automatic daily backup",
                            subtitle = if (preferences.autoBackupFolder.isBlank()) "Pick a folder once (for example a Google Drive folder)"
                            else "To ${BackupFolder.displayName(preferences.autoBackupFolder)}" +
                                (if (preferences.lastAutoBackupAt > 0) " \u2022 last ${formatAgo(preferences.lastAutoBackupAt)}" else "") +
                                " \u2022 keeps the newest 7",
                            checked = preferences.autoBackupEnabled && preferences.autoBackupFolder.isNotBlank(),
                            onCheckedChange = { on ->
                                if (on && preferences.autoBackupFolder.isBlank()) onChooseBackupFolder() else onToggleAutoBackup(on)
                            },
                            testTag = "backup_auto_switch"
                        )
                        if (preferences.autoBackupFolder.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onChooseBackupFolder) { Text("Change folder") }
                                TextButton(onClick = onBackupNow) { Text("Back up now") }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        ListEntryRow(
                            icon = Icons.Default.History,
                            title = "Import YouTube history",
                            subtitle = "watch-history.json from Google Takeout (JSON format) seeds history and recommendations",
                            onClick = onImportTakeoutHistory,
                            trailing = null
                        )
                    }
                }
            }
        }
    }
}

/** Smart download size, refresh, and the remembered download qualities. */
@Composable
private fun DownloadsCard(
    preferences: ShieldPreferencesState,
    onSetSmartSize: (Int) -> Unit,
    onRefreshSmart: () -> Unit,
    onResetDownloadQuality: (Boolean) -> Unit
) {
    SettingsSectionCard(title = "Downloads") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ShieldCoral, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Smart downloads (music)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "Songs picked from what you listen to (plus some new ones), kept inside the app for offline listening. " +
                "Updates once a day on Wi-Fi while charging.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        SmartDownloads.SIZE_OPTIONS_MB.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                row.forEach { mb ->
                    val selected = preferences.smartSizeMb == mb
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) ShieldCoral.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) ShieldCoral else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSetSmartSize(mb) }
                            .testTag("smart_size_$mb")
                    ) {
                        Text(
                            sizeLabel(mb),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            buildString {
                if (preferences.smartSizeMb > 0) {
                    append("≈ ${SmartDownloads.estimatedSongs(preferences.smartSizeMb)} songs (M4A, about 1 MB per minute)")
                } else {
                    append("Off")
                }
                if (preferences.lastSmartRefreshAt > 0) append(" · last update ${formatAgo(preferences.lastSmartRefreshAt)}")
                if (preferences.smartSummary.isNotBlank()) append(": ${preferences.smartSummary}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (preferences.smartSizeMb > 0) {
            TextButton(onClick = onRefreshSmart, modifier = Modifier.testTag("smart_refresh_now")) {
                Text("Refresh now (on Wi-Fi)")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        QualityLine("Video download quality", preferences.downloadVideoQuality) { onResetDownloadQuality(false) }
        QualityLine("Song download quality", preferences.downloadMusicQuality) { onResetDownloadQuality(true) }
        Text(
            "Downloads are saved to Movies/StreamShield and Music/StreamShield.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun QualityLine(title: String, remembered: Int, onReset: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    remembered < 0 -> "Ask each time"
                    remembered == 0 -> "Audio only (remembered)"
                    else -> "${remembered}p (remembered)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (remembered >= 0) TextButton(onClick = onReset) { Text("Ask again") }
    }
}

private fun sizeLabel(mb: Int): String = when {
    mb <= 0 -> "Off"
    mb >= 1024 -> "${mb / 1024} GB"
    else -> "$mb MB"
}

/** Playback interruptions seen in picture-in-picture (shown in Diagnostics). */
data class PipStats(val refusedPauses: Int, val systemPauses: Int, val stalls: Int)

/** What happened while the last video started: helps tell ad-related delays from other ones. */
@Composable
private fun DiagnosticsCard(
    lastStart: StartDiagnostics?,
    pipStats: PipStats,
    tapFixes: Pair<Int, String>,
    autoplay: AutoplayDiagnostics,
    recentBlocked: List<String>,
    showStartTime: Boolean,
    onToggleShowStartTime: (Boolean) -> Unit
) {
    SettingsSectionCard(title = "Diagnostics") {
        SettingToggleRow(
            icon = Icons.Default.Speed,
            iconTint = ShieldCoral,
            title = "Show start time",
            subtitle = "A short note each time a video starts: how long it took and how many ads were removed",
            checked = showStartTime,
            onCheckedChange = onToggleShowStartTime,
            testTag = "setting_show_start_time_switch"
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        if (lastStart == null) {
            Text(
                text = "Play a video to see how long it took to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = lastStart.title.ifBlank { "Last video" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            // A long gap between these two means the player waited; a slow first one means the
            // page / network was slow.
            DiagnosticLine(
                "Video data loaded",
                if (lastStart.loadMs >= 0) String.format(Locale.US, "%.1f s", lastStart.loadMs / 1000.0) else "?"
            )
            DiagnosticLine("Playback started", String.format(Locale.US, "%.1f s", lastStart.startMs / 1000.0))
            DiagnosticLine("Ad shield", if (lastStart.adBlockEnabled) "on" else "off")
            DiagnosticLine("Ads removed before playback", lastStart.adsPruned.toString())
            DiagnosticLine(
                "Time in ad state",
                if (lastStart.adMs > 0) String.format(Locale.US, "%.1f s", lastStart.adMs / 1000.0) else "none"
            )
            DiagnosticLine("17 s player timers skipped", lastStart.timersBoosted.toString())
        }
        if (pipStats.refusedPauses > 0 || pipStats.systemPauses > 0 || pipStats.stalls > 0) {
            DiagnosticLine("PiP: page pauses refused", pipStats.refusedPauses.toString())
            DiagnosticLine("PiP: system pauses", pipStats.systemPauses.toString())
            DiagnosticLine("PiP: stalls (waiting for data)", pipStats.stalls.toString())
        }
        if (tapFixes.first > 0) {
            DiagnosticLine("Play/pause taps the page missed (fixed)", tapFixes.first.toString())
            DiagnosticLine("On top of the button", tapFixes.second.ifBlank { "?" })
        }
        if (autoplay.method.isNotBlank()) {
            val how = when (autoplay.method) {
                "page" -> "opened the next video"
                "queue" -> "YouTube Music's next track"
                "player" -> "the page's next track"
                "none" -> "nothing to play next"
                else -> autoplay.method
            }
            val at = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(autoplay.methodAt))
            DiagnosticLine("Autoplay in the background ($at)", how)
        }
        if (autoplay.gapResult.isNotBlank()) {
            DiagnosticLine("Next video after one ended in the background", autoplay.gapResult)
        }
        if (recentBlocked.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Recently blocked requests",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            recentBlocked.take(8).forEach { entry ->
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatBlock(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ShieldCoral,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
