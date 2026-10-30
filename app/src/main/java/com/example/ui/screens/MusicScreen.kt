package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.WatchedVideo
import com.example.recommend.MusicShelves
import com.example.recommend.MusicSong
import com.example.recommend.MusicSource
import com.example.recommend.SongStat
import com.example.recommend.YtmHomeState
import com.example.recommend.YtmItem
import com.example.recommend.YtmKind
import com.example.recommend.YtmShelf
import com.example.ui.DownloadTarget
import com.example.ui.components.AppTopBar
import com.example.ui.components.ChipRow
import com.example.ui.components.EmptyState
import com.example.ui.components.MenuAction
import com.example.ui.components.OverflowMenu
import com.example.ui.components.SearchScreen
import com.example.ui.theme.ShieldCoral
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The Music tab's home, laid out like the YouTube Music Android app: a top bar with search and
 * YouTube Music, mood chips, then shelves. First the app's own, from the songs played here:
 * Speed dial (3 x 3 tiles, swipe for more), Quick picks (the music "For you", see
 * MusicForYouRule), Mixed for you (radios), Forgotten favorites, New releases from your artists.
 * Then YouTube Music's own home shelves (signed out; YouTubeMusicHome), more as it is scrolled.
 * A mood chip shows YouTube Music's home for that mood. Songs play in YouTube Music, whose page
 * then fills the tab (Back, or the tab, returns here).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicScreen(
    history: List<WatchedVideo>,
    forYou: List<MusicSong>,
    forYouRefreshing: Boolean,
    forYouNote: String,
    /** Changes with every new "For you" list. */
    forYouGeneration: Int,
    ytm: YtmHomeState,
    /** A video (not a song) is playing: opening YouTube Music's home stops it. */
    videoPlaying: Boolean,
    onSearch: (String) -> Unit,
    onOpenYouTubeMusic: () -> Unit,
    onPlaySong: (videoId: String) -> Unit,
    onPlayRadio: (videoId: String) -> Unit,
    onOpenArtist: (channelId: String, name: String) -> Unit,
    onOpenYtmItem: (YtmItem) -> Unit,
    onSelectChip: (params: String) -> Unit,
    onLoadMoreYtm: () -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    onNotInterested: (MusicSong) -> Unit,
    onSeen: (Collection<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val now = remember(history, forYouGeneration) { System.currentTimeMillis() }
    val songs = remember(history) {
        history.filter { it.isMusic }.map {
            SongStat(it.videoId, it.title, it.channelName, it.channelId, it.durationSec, it.watchedFraction, it.lastWatchedAt, it.playCount)
        }
    }
    val speedDial = remember(songs, now) { MusicShelves.speedDial(songs, now) }
    val radios = remember(songs, now) { MusicShelves.radios(songs, now) }
    val forgotten = remember(songs, now) { MusicShelves.forgottenFavorites(songs, now) }
    val quickPicks = remember(forYou) { forYou.take(QUICK_PICKS) }
    val newReleases = remember(forYou) {
        forYou.filter { it.source == MusicSource.ARTIST && it.publishedAt > 0 }.sortedByDescending { it.publishedAt }.take(20)
    }
    val filtered = ytm.selected.isNotBlank()

    val listState = rememberLazyListState()
    val moreYtm by rememberUpdatedState(onLoadMoreYtm)
    val canLoadMore by rememberUpdatedState(ytm.hasMore && !ytm.loadingMore && !ytm.loading)
    LaunchedEffect(listState) {
        // Near the end, and YouTube Music has more: load it (also when the first shelves were
        // too few to scroll, as soon as they are there).
        snapshotFlow {
            val info = listState.layoutInfo
            canLoadMore && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { load -> if (load) moreYtm() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "music_top") {
                AppTopBar("Music") {
                    IconButton(onClick = { searching = true }, modifier = Modifier.testTag("music_search")) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenYouTubeMusic, modifier = Modifier.testTag("music_open_ytm")) {
                        Icon(Icons.Default.Explore, contentDescription = "YouTube Music")
                    }
                }
            }
            if (videoPlaying) {
                item(key = "music_video_note") {
                    Text(
                        "A video is playing: a song or YouTube Music's home stops it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            if (ytm.chips.isNotEmpty()) {
                item(key = "music_chips") {
                    ChipRow(
                        titles = ytm.chips.map { it.title },
                        selected = ytm.chips.indexOfFirst { it.params == ytm.selected },
                        onSelect = { i -> onSelectChip(ytm.chips[i].params) }
                    )
                }
            }

            if (!filtered) {
                if (speedDial.size >= 3) {
                    item(key = "speed_dial") {
                        Column {
                            ShelfHeader("Speed dial")
                            SpeedDial(speedDial, onPlaySong, onOpenArtist)
                        }
                    }
                }
                if (forYouNote.isNotBlank()) {
                    item(key = "music_note") {
                        Text(
                            forYouNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                if (quickPicks.isNotEmpty()) {
                    item(key = "quick_picks_$forYouGeneration") {
                        Column {
                            ShelfHeader("Quick picks", trailing = if (forYouRefreshing) "Updating…" else "")
                            SongColumns(
                                rows = quickPicks.map { s ->
                                    SongRowData(
                                        id = s.videoId,
                                        image = songImage(s.videoId),
                                        zoom = SONG_ZOOM,
                                        title = s.title,
                                        subtitle = artistName(s.artist),
                                        menu = listOf(
                                            MenuAction("Download", Icons.Default.Download) {
                                                onDownload(DownloadTarget(s.videoId, s.title, s.artist, s.channelId, s.durationSec, isMusic = true))
                                            },
                                            MenuAction("Not interested", Icons.Default.ThumbDown) { onNotInterested(s) }
                                        ),
                                        onClick = { onPlaySong(s.videoId) }
                                    )
                                },
                                onVisible = onSeen
                            )
                        }
                    }
                } else if (songs.isEmpty() || !forYouRefreshing) {
                    item(key = "music_empty") {
                        EmptyState(
                            icon = if (forYouRefreshing) Icons.Default.AutoAwesome else Icons.Default.MusicNote,
                            title = if (forYouRefreshing) "Finding songs for you…" else "Your shelves fill up as you listen",
                            message = "Speed dial, Quick picks and mixes are built from the songs you play here " +
                                "(at least half of each). Pull down to refresh."
                        )
                    }
                }
                if (radios.isNotEmpty()) {
                    item(key = "mixed_for_you") {
                        Column {
                            ShelfHeader("Mixed for you")
                            TileRow(radios.map { s ->
                                TileData(s.videoId, songImage(s.videoId), SONG_ZOOM, "${s.title} radio", artistName(s.artist), false, false) { onPlayRadio(s.videoId) }
                            })
                        }
                    }
                }
                if (forgotten.size >= 3) {
                    item(key = "forgotten") {
                        Column {
                            ShelfHeader("Forgotten favorites")
                            TileRow(forgotten.map { s ->
                                TileData(s.videoId, songImage(s.videoId), SONG_ZOOM, s.title, artistName(s.artist), false, false) { onPlaySong(s.videoId) }
                            })
                        }
                    }
                }
                if (newReleases.isNotEmpty()) {
                    item(key = "new_releases_yours") {
                        Column {
                            ShelfHeader("New releases from your artists")
                            TileRow(newReleases.map { s ->
                                TileData(s.videoId, songImage(s.videoId), SONG_ZOOM, s.title, artistName(s.artist), false, false) { onPlaySong(s.videoId) }
                            })
                        }
                    }
                }
                if (ytm.shelves.isNotEmpty()) {
                    item(key = "ytm_divider") {
                        Text(
                            "FROM YOUTUBE MUSIC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp)
                        )
                    }
                }
            }

            ytmShelves(ytm.shelves, onOpenYtmItem)
            if (ytm.loading || ytm.loadingMore) item(key = "ytm_loading") { Loading() }
            if (ytm.error.isNotBlank()) {
                item(key = "ytm_error") {
                    Text(
                        ytm.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        if (searching) {
            BackHandler { searching = false }
            SearchScreen(
                placeholder = "Search songs, albums, artists",
                recent = emptyList(),
                onSearch = { q ->
                    searching = false
                    onSearch(q)
                },
                onClose = { searching = false }
            )
        }
    }
}

private const val QUICK_PICKS = 20
/** Song pictures are YouTube's 4:3 video thumbnails; zoomed so the square shows the cover. */
private const val SONG_ZOOM = 1.34f

private fun songImage(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/** "Artist - Topic" (YouTube's auto-generated music channels) shown as "Artist". */
private fun artistName(name: String): String = name.trim().removeSuffix(" - Topic")

@Composable
private fun ShelfHeader(title: String, strapline: String = "", trailing: String = "") {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)) {
        if (strapline.isNotBlank()) {
            Text(strapline.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (trailing.isNotBlank()) Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A square (or round, or 16:9) picture; [zoom] crops letterboxed video thumbnails. */
@Composable
private fun Cover(url: String, size: Dp, shape: Shape, zoom: Float, wide: Boolean = false) {
    Box(
        modifier = Modifier
            .width(size)
            .aspectRatio(if (wide) 16f / 9f else 1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                }
        )
    }
}

// ---- Speed dial: pages of 3 x 3 tiles --------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDial(
    tiles: List<com.example.recommend.SpeedDialTile>,
    onPlaySong: (String) -> Unit,
    onOpenArtist: (String, String) -> Unit
) {
    val pages = tiles.chunked(9)
    val pager = rememberPagerState { pages.size }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tile = (maxWidth - 32.dp - 16.dp) / 3
        Column {
            HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 16.dp) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    pages[page].chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { t ->
                                val s = t.song
                                Column(
                                    modifier = Modifier
                                        .width(tile)
                                        .clickable {
                                            if (t.isArtist) onOpenArtist(s.channelId, s.artist) else onPlaySong(s.videoId)
                                        }
                                ) {
                                    Cover(songImage(s.videoId), tile, if (t.isArtist) CircleShape else RoundedCornerShape(6.dp), SONG_ZOOM)
                                    Text(
                                        if (t.isArtist) artistName(s.artist) else s.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (pages.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(pages.size) { i ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (i == pager.currentPage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        )
                    }
                }
            }
        }
    }
}

// ---- song rows in columns of 4 (Quick picks) ------------------------------------------------

private class SongRowData(
    val id: String,
    val image: String,
    val zoom: Float,
    val title: String,
    val subtitle: String,
    val menu: List<MenuAction>,
    val onClick: () -> Unit
)

@Composable
private fun SongColumns(rows: List<SongRowData>, onVisible: ((Collection<String>) -> Unit)? = null) {
    val columns = rows.chunked(4)
    val state = rememberLazyListState()
    val visibleCallback by rememberUpdatedState(onVisible)
    val currentColumns by rememberUpdatedState(columns)
    LaunchedEffect(state) {
        // Rows on screen count as shown (the music "For you" fades songs passed over).
        val reported = HashSet<String>()
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                val ids = indices.flatMap { currentColumns.getOrNull(it).orEmpty() }.map { it.id }.filter { reported.add(it) }
                if (ids.isNotEmpty()) visibleCallback?.invoke(ids)
            }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnWidth = maxWidth * 0.86f
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(columns.size) { c ->
                Column(modifier = Modifier.width(columnWidth)) {
                    columns[c].forEach { r -> SongRow(r) }
                }
            }
        }
    }
}

@Composable
private fun SongRow(r: SongRowData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = r.onClick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(r.image, 50.dp, RoundedCornerShape(4.dp), r.zoom)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.subtitle.isNotBlank()) {
                Text(
                    r.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (r.menu.isNotEmpty()) OverflowMenu(r.menu)
    }
}

// ---- tiles (albums, playlists, mixes, artists) ---------------------------------------------

private class TileData(
    val id: String,
    val image: String,
    val zoom: Float,
    val title: String,
    val subtitle: String,
    val round: Boolean,
    val wide: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun TileRow(tiles: List<TileData>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tiles.size) { i ->
            val t = tiles[i]
            val width = if (t.wide) 256.dp else 150.dp
            Column(modifier = Modifier.width(width).clickable(onClick = t.onClick)) {
                Cover(t.image, width, if (t.round) CircleShape else RoundedCornerShape(6.dp), t.zoom, t.wide)
                Text(
                    t.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (t.subtitle.isNotBlank()) {
                    Text(
                        t.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ---- YouTube Music's own shelves --------------------------------------------------------------

private fun LazyListScope.ytmShelves(shelves: List<YtmShelf>, onOpen: (YtmItem) -> Unit) {
    shelves.forEachIndexed { index, shelf ->
        item(key = "ytm_${index}_${shelf.title}") {
            Column {
                ShelfHeader(shelf.title, shelf.strapline)
                if (shelf.rows) {
                    SongColumns(shelf.items.map { item ->
                        SongRowData(item.videoId.ifBlank { item.browseId }, item.thumbnailUrl, 1f, item.title, item.subtitle, emptyList()) { onOpen(item) }
                    })
                } else {
                    TileRow(shelf.items.map { item ->
                        TileData(
                            id = item.videoId.ifBlank { item.browseId.ifBlank { item.playlistId } },
                            image = item.thumbnailUrl,
                            zoom = 1f,
                            title = item.title,
                            subtitle = item.subtitle,
                            round = item.kind == YtmKind.ARTIST,
                            wide = item.wide
                        ) { onOpen(item) }
                    })
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ShieldCoral)
    }
}
