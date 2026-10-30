package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.example.R
import com.example.data.formatDuration
import com.example.ui.theme.ShieldCoral

// Pieces laid out like the YouTube and YouTube Music Android apps (full-width feed cards, the
// top bar, the tabs under it, the search screen).

/** Top bar: the app's mark and [title] on the left, [actions] on the right. */
@Composable
fun AppTopBar(title: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The launcher icon's foreground layer has wide margins; the shield fills about 2/3.
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(44.dp)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** Text tabs under the top bar, the selected one underlined (YouTube's Home · Subs · …). */
@Composable
fun TopTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            Column(
                modifier = Modifier
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp)
                    .testTag("top_tab_$i"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                )
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (on) 24.dp else 0.dp)
                        .background(ShieldCoral, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** Chips row (YouTube Music's moods, …); [selected] is highlighted. */
@Composable
fun ChipRow(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(
                        if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * A feed card as in the YouTube app: the thumbnail across the whole width (720p picture, the
 * 480 one if a video has none), then the channel's letter avatar, a two-line title, "channel ·
 * views · age" and the overflow menu.
 */
@Composable
fun FeedVideoCard(
    videoId: String,
    title: String,
    channelName: String,
    meta: String,
    durationSec: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onChannelClick: (() -> Unit)? = null,
    menu: List<MenuAction> = emptyList()
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = "https://i.ytimg.com/vi/$videoId/hq720.jpg",
                error = rememberAsyncImagePainter("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            val duration = formatDuration(durationSec)
            if (duration.isNotEmpty()) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(modifier = if (onChannelClick != null) Modifier.clickable(onClick = onChannelClick) else Modifier) {
                ChannelAvatar(channelName.ifBlank { title }, size = 36.dp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { "YouTube video" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (menu.isNotEmpty()) OverflowMenu(menu) else Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

/**
 * Full-screen search as in the apps: Back, the query field (focused), then recent searches.
 * [recent] may be empty; [onRemove] null hides the remove buttons.
 */
@Composable
fun SearchScreen(
    placeholder: String,
    recent: List<String>,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onRemove: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .testTag("search_screen_input")
            )
        }
        recent.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearch(entry) }
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    entry,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
                if (onRemove != null) {
                    IconButton(onClick = { onRemove(entry) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove search", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
