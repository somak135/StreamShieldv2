package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.FeedItemEntity
import com.example.data.SearchHistoryEntity
import com.example.data.WatchedVideo
import com.example.data.formatAgo
import com.example.data.formatCount
import com.example.recommend.ForYouRule
import com.example.recommend.ForYouVideo
import com.example.ui.DownloadTarget
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyState
import com.example.ui.components.FeedVideoCard
import com.example.ui.components.MenuAction
import com.example.ui.components.SearchScreen
import com.example.ui.components.TopTabs
import com.example.ui.theme.ShieldCoral
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Home, laid out like the YouTube Android app (October 2026): a top bar with search, tabs under
 * it (Home · Subs · Recently uploaded · New to you; swipe between them) and full-width video
 * cards. Home is "For you" (see ForYouRule), which loads more as it is scrolled; Subs is the
 * subscriptions' uploads; Recently uploaded is For you and subscription videos of the last 7
 * days, newest first; New to you is For you without channels already watched or subscribed.
 * Continue watching and history are in Library, as in the app.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val pager = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()
    val now = remember(forYouGeneration, feed.size) { System.currentTimeMillis() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar("StreamShield") {
                IconButton(onClick = { searching = true }, modifier = Modifier.testTag("home_search")) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
            TopTabs(TABS, pager.currentPage, { i -> scope.launch { pager.animateScrollToPage(i) } })
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> ForYouPage(
                        forYou, forYouRefreshing, forYouNote, forYouGeneration, forYouExtending, forYouEnd,
                        onOpenVideo, onOpenChannel, onDownload, onNotInterested, onForYouOpened, onForYouSeen, onLoadMore
                    )
                    1 -> SubsPage(feed, feedRefreshing, hasSubscriptions, now, onOpenVideo, onOpenChannel, onOpenSubscriptions, onDownload)
                    2 -> {
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
                    else -> {
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
                }
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
    }
}

private val TABS = listOf("Home", "Subs", "Recently uploaded", "New to you")
private const val RECENT_DAYS = 7
private const val DAY_MS = 24L * 60 * 60 * 1000

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
