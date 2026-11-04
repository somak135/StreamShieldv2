package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.FeedItemEntity
import com.example.data.SearchHistoryEntity
import com.example.data.WatchedVideo
import com.example.data.formatAgo
import com.example.data.formatCount
import com.example.recommend.ExploreEntry
import com.example.recommend.ForYouRule
import com.example.recommend.ForYouVideo
import com.example.recommend.HomeChip
import com.example.recommend.HomeChipKind
import com.example.recommend.HomeChipRule
import com.example.recommend.PastVideo
import com.example.recommend.YtPage
import com.example.ui.ChipFeedState
import com.example.ui.DownloadTarget
import com.example.ui.components.AppTopBar
import com.example.ui.components.ChipRow
import com.example.ui.components.EmptyState
import com.example.ui.components.FeedVideoCard
import com.example.ui.components.MenuAction
import com.example.ui.components.SearchScreen
import com.example.ui.theme.ShieldCoral
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Home, laid out like the YouTube Android app (November 2026): a top bar with search, a row of
 * chips under it (Explore, then the chips of HomeChipRule: All · Subs · topic pages · your
 * topics · Recently uploaded · Watched · New to you) and full-width video cards. All is "For
 * you" (see ForYouRule), which loads more as it is scrolled; Subs is the subscriptions' uploads;
 * a topic page (Music, Gaming, ...) is YouTube's own page; a topic is For you and YouTube's
 * results for it; Recently uploaded is For you and subscription videos of the last 7 days,
 * newest first; Watched is videos to watch again; New to you is For you without channels already
 * watched or subscribed. Explore opens YouTube's topic pages (in the app) and its pages without
 * plain videos (Movies & TV, Charts; on YouTube). Continue watching and history are in
 * Library, as in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    forYou: List<ForYouVideo>,
    forYouRefreshing: Boolean,
    forYouNote: String,
    /** Changes with every new "For you" list (Home shows its first page again). */
    forYouGeneration: Int,
    forYouExtending: Boolean,
    forYouEnd: Boolean,
    feed: List<FeedItemEntity>,
    feedRefreshing: Boolean,
    hasSubscriptions: Boolean,
    subscribedChannels: Set<String>,
    history: List<WatchedVideo>,
    recentSearches: List<SearchHistoryEntity>,
    chips: List<HomeChip>,
    selectedChip: String,
    chipFeed: ChipFeedState,
    onSelectChip: (HomeChip) -> Unit,
    onExplore: (ExploreEntry) -> Unit,
    onHomeShown: () -> Unit,
    onSearch: (String) -> Unit,
    onDeleteSearch: (String) -> Unit,
    onOpenVideo: (videoId: String, startSec: Int) -> Unit,
    onOpenChannel: (channelId: String) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    onNotInterested: (ForYouVideo) -> Unit,
    onForYouOpened: (String) -> Unit,
    onForYouSeen: (Collection<String>) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var exploring by rememberSaveable { mutableStateOf(false) }
    val now = remember(forYouGeneration, feed.size) { System.currentTimeMillis() }
    LaunchedEffect(Unit) { onHomeShown() }
    val selected = chips.firstOrNull { it.key == selectedChip } ?: chips.firstOrNull() ?: HomeChip(HomeChipKind.ALL, "All")

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar("StreamShield") {
                IconButton(onClick = { searching = true }, modifier = Modifier.testTag("home_search")) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
            ChipRow(
                titles = chips.map { it.label },
                selected = chips.indexOfFirst { it.key == selected.key },
                onSelect = { i -> chips.getOrNull(i)?.let(onSelectChip) },
                leading = {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { exploring = true }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("home_explore")
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = "Explore", modifier = Modifier.size(22.dp))
                    }
                }
            )
            when (selected.kind) {
                HomeChipKind.ALL -> ForYouPage(
                    forYou, forYouRefreshing, forYouNote, forYouGeneration, forYouExtending, forYouEnd,
                    onOpenVideo, onOpenChannel, onDownload, onNotInterested, onForYouOpened, onForYouSeen, onLoadMore
                )
                HomeChipKind.SUBS -> SubsPage(feed, feedRefreshing, hasSubscriptions, now, onOpenVideo, onOpenChannel, onOpenSubscriptions, onDownload)
                HomeChipKind.RECENT -> {
                    val cutoff = now - RECENT_DAYS * DAY_MS
                    val cards = remember(forYou, feed, now) {
                        (forYou.filter { it.publishedAt >= cutoff }.map { card(it, now) } +
                            feed.filter { !it.isShort && it.publishedAt >= cutoff }.map { card(it, now) })
                            .distinctBy { it.videoId }
                            .sortedByDescending { it.publishedAt }
                    }
                    CardList(
                        cards = cards,
                        empty = { EmptyState(Icons.Default.NewReleases, "No new videos", "Videos of the last $RECENT_DAYS days from For you and your subscriptions show up here.") },
                        forYou = forYou, onOpenVideo = onOpenVideo, onOpenChannel = onOpenChannel,
                        onDownload = onDownload, onNotInterested = onNotInterested, onForYouOpened = onForYouOpened
                    )
                }
                HomeChipKind.WATCHED -> {
                    val cards = remember(history, now) {
                        HomeChipRule.watched(history.map { it.toPast() }, now).map { p ->
                            val times = if (p.playCount >= 2) "watched ${p.playCount} times" else "watched"
                            Card(
                                p.videoId, p.title, p.channelId, p.channelName,
                                listOf(p.channelName, times, formatAgo(p.lastWatchedAt, now)).filter { it.isNotBlank() }.joinToString(" · "),
                                p.durationSec, p.lastWatchedAt
                            )
                        }
                    }
                    CardList(
                        cards = cards,
                        empty = { EmptyState(Icons.Default.History, "Nothing to watch again yet", "Videos you finished or played twice show up here, from the next day on.") },
                        forYou = forYou, onOpenVideo = onOpenVideo, onOpenChannel = onOpenChannel,
                        onDownload = onDownload, onNotInterested = onNotInterested, onForYouOpened = onForYouOpened
                    )
                }
                HomeChipKind.NEW -> {
                    val known = remember(history, subscribedChannels) {
                        history.flatMap { listOf(it.channelId, it.channelName.trim().lowercase()) }.filter { it.isNotBlank() }.toSet() + subscribedChannels
                    }
                    val cards = remember(forYou, known, now) {
                        forYou.filter { it.channelId !in known && it.channelName.trim().lowercase() !in known }.map { card(it, now) }
                    }
                    CardList(
                        cards = cards,
                        empty = { EmptyState(Icons.Default.AutoAwesome, "Nothing new yet", "Videos in For you from channels you have not watched yet show up here.") },
                        forYou = forYou, onOpenVideo = onOpenVideo, onOpenChannel = onOpenChannel,
                        onDownload = onDownload, onNotInterested = onNotInterested, onForYouOpened = onForYouOpened
                    )
                }
                HomeChipKind.TOPIC, HomeChipKind.PAGE -> ChipFeedPage(selected, chipFeed, onOpenVideo, onOpenChannel, onDownload)
            }
        }
        if (searching) {
            BackHandler { searching = false }
            SearchScreen(
                placeholder = "Search YouTube or paste a link",
                recent = recentSearches.map { it.query },
                onSearch = { q ->
                    searching = false
                    onSearch(q)
                },
                onClose = { searching = false },
                onRemove = onDeleteSearch
            )
        }
        if (exploring) {
            ExploreSheet(
                onPick = { entry ->
                    exploring = false
                    onExplore(entry)
                },
                onDismiss = { exploring = false }
            )
        }
    }
}

private const val RECENT_DAYS = 7
private const val DAY_MS = 24L * 60 * 60 * 1000

private fun WatchedVideo.toPast() =
    PastVideo(videoId, title, channelId, channelName, durationSec, watchedFraction, playCount, lastWatchedAt, isMusic)

/** What a feed card shows, from For you or the subscription feed. */
private data class Card(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val meta: String,
    val durationSec: Int,
    val publishedAt: Long
)

private fun meta(channel: String, views: Long, age: String) = listOfNotNull(
    channel.takeIf { it.isNotBlank() },
    views.takeIf { it > 0 }?.let { "${formatCount(it)} views" },
    age.takeIf { it.isNotBlank() }
).joinToString(" · ")

private fun card(v: ForYouVideo, now: Long) = Card(
    v.videoId, v.title, v.channelId, v.channelName,
    meta(v.channelName, v.viewCount, if (v.publishedAt > 0) formatAgo(v.publishedAt, now) else v.publishedText),
    v.durationSec, v.publishedAt
)

private fun card(f: FeedItemEntity, now: Long) = Card(
    f.videoId, f.title, f.channelId, f.channelName,
    meta(f.channelName, f.viewCount, formatAgo(f.publishedAt, now)),
    0, f.publishedAt
)

@Composable
private fun ForYouPage(
    forYou: List<ForYouVideo>,
    refreshing: Boolean,
    note: String,
    generation: Int,
    extending: Boolean,
    end: Boolean,
    onOpenVideo: (String, Int) -> Unit,
    onOpenChannel: (String) -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    onNotInterested: (ForYouVideo) -> Unit,
    onOpened: (String) -> Unit,
    onSeen: (Collection<String>) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()
    // Shown page by page; at the end of what is loaded the list asks for more.
    var shown by remember(generation) { mutableIntStateOf(ForYouRule.FIRST_PAGE) }
    var waitingForMore by remember(generation) { mutableStateOf(false) }
    val currentList by rememberUpdatedState(forYou)
    val canLoadMore by rememberUpdatedState(!end && !extending)
    val loadMore by rememberUpdatedState(onLoadMore)
    val seenCallback by rememberUpdatedState(onSeen)
    LaunchedEffect(listState, generation) {
        val reported = HashSet<String>()
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                (info.key as? String)?.takeIf { it.startsWith(FOR_YOU_KEY) }?.removePrefix(FOR_YOU_KEY)
            }
        }
            .distinctUntilChanged()
            .collect { visible ->
                // On screen = shown (counted when the next list is made, unless opened).
                val fresh = visible.filter { reported.add(it) }
                if (fresh.isNotEmpty()) seenCallback(fresh)
                val list = currentList
                val last = visible.maxOfOrNull { id -> list.indexOfFirst { it.videoId == id } } ?: -1
                if (last >= minOf(shown, list.size) - 3) {
                    if (shown < list.size) {
                        shown = minOf(list.size, shown + ForYouRule.PAGE)
                    } else if (canLoadMore) {
                        waitingForMore = true
                        loadMore()
                    }
                }
            }
    }
    LaunchedEffect(forYou.size, generation) {
        if (waitingForMore && forYou.size > shown) {
            shown = minOf(forYou.size, shown + ForYouRule.PAGE)
            waitingForMore = false
        }
    }
    LaunchedEffect(generation) { listState.scrollToItem(0) }

    val now = remember(generation) { System.currentTimeMillis() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        if (refreshing && forYou.isNotEmpty()) item(key = "for_you_refreshing") { Spinner("Finding videos for you…") }
        if (note.isNotBlank()) {
            item(key = "for_you_note") {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
        if (forYou.isEmpty()) {
            item {
                EmptyState(
                    icon = if (refreshing) Icons.Default.AutoAwesome else Icons.Default.Subscriptions,
                    title = if (refreshing) "Finding videos for you…" else "Nothing here yet",
                    message = "Built from what you watch and the channels you subscribe to (Subscriptions tab: " +
                        "import from YouTube, or Subscribe on any video). Pull down to refresh."
                )
            }
        } else {
            items(forYou.take(shown), key = { FOR_YOU_KEY + it.videoId }) { v ->
                val c = card(v, now)
                FeedVideoCard(
                    videoId = c.videoId,
                    title = c.title,
                    channelName = c.channelName,
                    meta = c.meta,
                    durationSec = c.durationSec,
                    onClick = {
                        onOpened(v.videoId)
                        onOpenVideo(v.videoId, 0)
                    },
                    onChannelClick = v.channelId.takeIf { it.startsWith("UC") }?.let { id -> { onOpenChannel(id) } },
                    menu = listOf(
                        MenuAction("Download", Icons.Default.Download) {
                            onDownload(DownloadTarget(v.videoId, v.title, v.channelName, v.channelId, v.durationSec))
                        },
                        MenuAction("Not interested", Icons.Default.ThumbDown) { onNotInterested(v) }
                    )
                )
            }
            if (extending || (end && shown >= forYou.size)) {
                item(key = "for_you_footer") {
                    if (extending) Spinner("Loading more…")
                    else Text(
                        "You're all caught up. Pull down for a fresh mix.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SubsPage(
    feed: List<FeedItemEntity>,
    refreshing: Boolean,
    hasSubscriptions: Boolean,
    now: Long,
    onOpenVideo: (String, Int) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onDownload: (DownloadTarget) -> Unit
) {
    val cards = remember(feed, now) { feed.filter { !it.isShort }.sortedByDescending { it.publishedAt }.map { card(it, now) } }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        if (refreshing) item(key = "subs_refreshing") { Spinner("Checking your subscriptions…") }
        if (cards.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Subscriptions,
                    title = if (hasSubscriptions) "No uploads yet" else "No subscriptions yet",
                    message = if (hasSubscriptions) "New videos from your channels show up here. Pull down to check."
                    else "Subscribe on any video or channel, or import your subscriptions from YouTube."
                ) {
                    TextButton(onClick = onOpenSubscriptions) { Text("Manage subscriptions", color = ShieldCoral) }
                }
            }
        } else {
            item(key = "subs_manage") {
                Row(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenSubscriptions) { Text("All channels", color = ShieldCoral) }
                }
            }
            cardItems(cards, emptyList(), onOpenVideo, onOpenChannel, onDownload, null, null)
        }
    }
}

@Composable
private fun CardList(
    cards: List<Card>,
    empty: @Composable () -> Unit,
    forYou: List<ForYouVideo>,
    onOpenVideo: (String, Int) -> Unit,
    onOpenChannel: (String) -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    onNotInterested: (ForYouVideo) -> Unit,
    onForYouOpened: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        if (cards.isEmpty()) item { empty() }
        else cardItems(cards, forYou, onOpenVideo, onOpenChannel, onDownload, onNotInterested, onForYouOpened)
    }
}

/** Feed cards; videos that are in For you get "Not interested" and count as opened from it. */
private fun LazyListScope.cardItems(
    cards: List<Card>,
    forYou: List<ForYouVideo>,
    onOpenVideo: (String, Int) -> Unit,
    onOpenChannel: (String) -> Unit,
    onDownload: (DownloadTarget) -> Unit,
    onNotInterested: ((ForYouVideo) -> Unit)?,
    onForYouOpened: ((String) -> Unit)?
) {
    items(cards, key = { "card_" + it.videoId }) { c ->
        val fromForYou = forYou.firstOrNull { it.videoId == c.videoId }
        FeedVideoCard(
            videoId = c.videoId,
            title = c.title,
            channelName = c.channelName,
            meta = c.meta,
            durationSec = c.durationSec,
            onClick = {
                if (fromForYou != null) onForYouOpened?.invoke(c.videoId)
                onOpenVideo(c.videoId, 0)
            },
            onChannelClick = c.channelId.takeIf { it.startsWith("UC") }?.let { id -> { onOpenChannel(id) } },
            menu = listOfNotNull(
                MenuAction("Download", Icons.Default.Download) {
                    onDownload(DownloadTarget(c.videoId, c.title, c.channelName, c.channelId, c.durationSec))
                },
                if (fromForYou != null && onNotInterested != null) MenuAction("Not interested", Icons.Default.ThumbDown) { onNotInterested(fromForYou) } else null
            )
        )
    }
}

@Composable
private fun Spinner(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ShieldCoral)
        Text("  $label", style = MaterialTheme.typography.bodySmall)
    }
}

/** Key prefix of "For you" cards (also how on-screen cards are recognised). */
private const val FOR_YOU_KEY = "foryou_"

/** A topic or topic-page chip's list (loaded by the view model, see ChipFeedState). */
@Composable
private fun ChipFeedPage(
    chip: HomeChip,
    state: ChipFeedState,
    onOpenVideo: (String, Int) -> Unit,
    onOpenChannel: (String) -> Unit,
    onDownload: (DownloadTarget) -> Unit
) {
    val feed = if (state.key == chip.key) state else ChipFeedState(chip.key, loading = true)
    val listState = rememberLazyListState()
    LaunchedEffect(chip.key) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        if (feed.loading && feed.videos.isNotEmpty()) item(key = "chip_loading") { Spinner("Loading ${chip.label}…") }
        if (feed.note.isNotBlank()) {
            item(key = "chip_note") {
                Text(
                    feed.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
        if (feed.videos.isEmpty()) {
            item(key = "chip_empty") {
                val source = if (chip.kind == HomeChipKind.PAGE) "From YouTube's ${chip.label} page."
                else "Videos about ${chip.label} from For you and YouTube's search."
                EmptyState(
                    icon = chip.page?.let(::pageIcon) ?: Icons.Default.Explore,
                    title = if (feed.loading) "Loading ${chip.label}…" else "Nothing here yet",
                    message = if (feed.loading) source else "$source Pull down to try again."
                )
            }
        } else {
            items(feed.videos, key = { "chip_" + it.videoId }) { v ->
                FeedVideoCard(
                    videoId = v.videoId,
                    title = v.title,
                    channelName = v.channelName,
                    meta = listOf(v.channelName, v.meta).filter { it.isNotBlank() }.joinToString(" · "),
                    durationSec = v.durationSec,
                    badge = if (v.live) "LIVE" else "",
                    onClick = { onOpenVideo(v.videoId, 0) },
                    onChannelClick = v.channelId.takeIf { it.startsWith("UC") }?.let { id -> { onOpenChannel(id) } },
                    menu = if (v.live) emptyList() else listOf(
                        MenuAction("Download", Icons.Default.Download) {
                            onDownload(DownloadTarget(v.videoId, v.title, v.channelName, v.channelId, v.durationSec))
                        }
                    )
                )
            }
        }
    }
}

private fun pageIcon(page: YtPage): ImageVector = when (page) {
    YtPage.MUSIC -> Icons.Default.MusicNote
    YtPage.GAMING -> Icons.Default.SportsEsports
    YtPage.LIVE -> Icons.Default.LiveTv
    YtPage.NEWS -> Icons.Default.Article
    YtPage.PODCASTS -> Icons.Default.Mic
    YtPage.SPORTS -> Icons.Default.EmojiEvents
    YtPage.LEARNING -> Icons.Default.School
    YtPage.FASHION -> Icons.Default.Checkroom
}

private fun exploreIcon(entry: ExploreEntry): ImageVector = entry.page?.let(::pageIcon) ?: when (entry.label) {
    "Movies & TV" -> Icons.Default.Movie
    else -> Icons.Default.BarChart
}

/** Explore, as in the YouTube app: its topic pages, and the pages that open on YouTube. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreSheet(onPick: (ExploreEntry) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Explore",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            HomeChipRule.EXPLORE.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry) }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .testTag("explore_" + entry.label),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(exploreIcon(entry), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(20.dp))
                    Text(entry.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (entry.page == null) {
                        Text("on YouTube", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
