package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AutoBackupWorker
import com.example.data.BackupFolder
import com.example.data.BackupManager
import com.example.data.BackupSummary
import com.example.data.ImportMode
import com.example.data.BuiltInPlaylist
import com.example.data.FeedItemEntity
import com.example.data.FeedbackType
import com.example.data.LibraryRepository
import com.example.data.PlaylistKind
import com.example.data.PlaylistSummary
import com.example.data.PlaylistVideo
import com.example.data.SearchHistoryEntity
import com.example.data.ShieldPreferencesState
import com.example.data.StreamShieldDatabase
import com.example.data.SubscriptionEntity
import com.example.data.SubscriptionRefresher
import com.example.data.TakeoutSubscriptionsParser
import com.example.data.UserPreferencesRepository
import com.example.data.WatchedVideo
import com.example.data.WebVideoState
import com.example.data.isYouTubeVideoPage
import com.example.data.extractYouTubeVideoId
import com.example.data.watchUrl
import com.example.data.youTubeLinkOrNull
import com.example.data.OfflineItemEntity
import com.example.data.OfflineOrigin
import com.example.data.OfflineStatus
import com.example.data.PlayEventEntity
import com.example.data.SmartRemovedEntity
import com.example.download.DownloadCatalog
import com.example.download.DownloadErrors
import com.example.download.DownloadWorker
import com.example.download.OfflineDownloader
import com.example.download.OfflineStorage
import com.example.download.QualityOption
import com.example.download.SmartDownloads
import com.example.download.NewPipeSupport
import com.example.recommend.ForYouRepository
import com.example.recommend.ForYouVideo
import com.example.recommend.MusicForYouRepository
import com.example.recommend.MusicSong
import com.example.recommend.YouTubeMusicHome
import com.example.recommend.YtmHomeState
import com.example.recommend.YtmItem
import com.example.recommend.YtmPage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.example.engine.SponsorBlockClient
import kotlinx.coroutines.channels.Channel
import com.example.service.BackgroundPlaybackService
import com.example.service.PlaybackCommand
import com.example.service.PlaybackCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val HOME_URL = "https://m.youtube.com"
const val MUSIC_HOME_URL = "https://music.youtube.com"
/** YouTube Music's own home shelves are loaded again after this (or on pull-down). */
private const val YTM_HOME_TTL_MS = 60L * 60 * 1000
/** Pages of YouTube Music's home shelves loaded as the music home is scrolled. */
private const val YTM_MAX_PAGES = 6

data class BrowserUiState(
    val url: String = HOME_URL,
    val isLoading: Boolean = false
)

data class BlockCounts(val ads: Long = 0L, val requests: Long = 0L)

data class SponsorSegments(val videoId: String, val json: String)

/** How the most recent video started; shown in Settings to diagnose slow starts. */
data class StartDiagnostics(
    val title: String,
    val loadMs: Int,
    val startMs: Int,
    val adMs: Int,
    val adsPruned: Int,
    val adBlockEnabled: Boolean,
    /** YouTube's 17 s player timers that were shortened (uBlock Origin rule). */
    val timersBoosted: Int = 0
)

/**
 * Bottom navigation tabs. They stay on screen for every browsing page (native screens, YouTube
 * search results / channels / playlists, YouTube Music); only a YouTube video page hides them.
 */
enum class AppTab { HOME, MUSIC, SUBSCRIPTIONS, LIBRARY }

/** Which player the media buttons, mini-player and notification belong to. */
enum class PlaybackSource { WEB, OFFLINE }

/** The browse page (search results, channels, playlists) shown inside a tab. */
data class BrowsePageState(val url: String = "", val loading: Boolean = false)

/** A video or song the user wants to download (what the screen knows about it). */
data class DownloadTarget(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String = "",
    val durationSec: Int = 0,
    val isMusic: Boolean = false
)

/** The quality sheet: options load from YouTube when it opens. */
data class DownloadSheetState(
    val target: DownloadTarget,
    val loading: Boolean = true,
    val options: List<QualityOption> = emptyList(),
    val error: String? = null
)

/** Downloaded items to play, starting at [index]. */
data class OfflinePlayRequest(val items: List<OfflineItemEntity>, val index: Int)

/** Largest gap between two progress writes that still counts as listening (seconds). */
private const val MAX_LISTEN_STEP_SEC = 45

/** The opening-video placeholder goes away after this at most, or this long after the page loaded. */
private const val PENDING_MAX_MS = 5_000L
private const val PENDING_AFTER_LOAD_MS = 2_500L
/** After the new video's page reports itself: time for YouTube to draw it, then it shows. */
private const val PENDING_AFTER_PAGE_MS = 1_200L

/** A refresh spinner on a web page stops after this at most. */
private const val WEB_REFRESH_MAX_MS = 12_000L

private val CHANNEL_ID_PATTERN = Regex("^UC[A-Za-z0-9_-]{22}$")

/** Listening-log operations, applied in order by one consumer. */
private sealed interface PlayLogOp {
    data class Start(val videoId: String, val at: Long, val position: Int, val duration: Int, val isMusic: Boolean) : PlayLogOp
    data class Progress(val videoId: String, val position: Int, val duration: Int, val ended: Boolean) : PlayLogOp
}

/** Screens inside the Library tab. */
sealed interface LibraryRoute {
    data object Root : LibraryRoute
    data object History : LibraryRoute
    data object Downloads : LibraryRoute
    data class Playlist(val id: Long) : LibraryRoute
}

/** Which web page a pull-down gesture happened in. */
enum class PullSource { PLAYER, BROWSE }

/** A pull-down in progress on a web page ([distance] in CSS px, about dp). */
data class WebPull(val source: PullSource, val mode: String, val distance: Float)

/** A video the player is opening: shown as a placeholder (thumbnail, title) until it starts. */
data class PendingVideo(val videoId: String, val title: String, val channel: String)

/** A channel as YouTube's page knows it (Subscribe button): id and/or path (/@name). */
data class PageChannel(val id: String, val path: String, val name: String)

/** "Unsubscribe from ...?" for a Subscribed button tapped on a YouTube page. */
data class UnsubscribeRequest(val channelId: String, val name: String)

/** Taps on the page's play/pause button that the page missed (this session), for diagnostics. */
data class TapFixInfo(val count: Int = 0, val lastTop: String = "")

/**
 * Autoplay while the app was hidden (this session), for diagnostics: how the page last moved on
 * ([WebVideoState.autoAdvance], at [methodAt] in wall-clock ms), and how long the gap after the
 * last video that ended in the background lasted ([gapResult], e.g. "started after 6.2 s").
 */
data class AutoplayDiagnostics(val method: String = "", val methodAt: Long = 0L, val gapResult: String = "")

/**
 * How long, after a video ends while the app is hidden, the notification keeps showing "playing"
 * (foreground service and wake lock) while the next video loads.
 */
const val AUTOPLAY_GAP_HOLD_MS = 30_000L

/** Pull distance (dp) at which a release refreshes; the indicator moves at half the finger. */
const val PULL_REFRESH_THRESHOLD_DP = 72f
const val PULL_DRAG_RATE = 0.5f

/** Subscription feed refresh status shown in the Subscriptions tab. */
data class FeedRefreshState(val running: Boolean = false, val lastMessage: String? = null)

/** A backup file that was picked for import, waiting for Merge / Replace. */
data class PendingImport(val text: String, val summary: BackupSummary)

/** What the native actions sheet knows about the video currently open in the player. */
data class CurrentVideoActions(
    val videoId: String = "",
    val title: String = "",
    val channelId: String = "",
    val channelName: String = "",
    val subscribed: Boolean = false,
    val liked: Boolean = false,
    val inWatchLater: Boolean = false,
    val notInterested: Boolean = false,
    val playlistIds: Set<Long> = emptySet()
)

@OptIn(ExperimentalCoroutinesApi::class)
class StreamShieldViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StreamShieldDatabase.getInstance(application)
    private val prefsRepo = UserPreferencesRepository(application)
    private val sponsorBlock = SponsorBlockClient()
    private val library = LibraryRepository(database.libraryDao())
    private val refresher = SubscriptionRefresher(library)
    private val backup = BackupManager(database, prefsRepo)
    private val offlineDao = database.offlineDao()

    // Eager: the page script reads the current config synchronously at document start.
    val preferences: StateFlow<ShieldPreferencesState> = prefsRepo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ShieldPreferencesState())

    // ---- local library ----
    val history: StateFlow<List<WatchedVideo>> = library.history()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Started but not finished videos (not music), newest first. */
    val continueWatching: StateFlow<List<WatchedVideo>> = history
        .map { list -> list.filter { !it.isMusic && it.durationSec > 0 && it.watchedFraction in 0.05f..0.9f }.take(15) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<SubscriptionEntity>> = library.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val feed: StateFlow<List<FeedItemEntity>> = library.feed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistSummary>> = library.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentSearches: StateFlow<List<SearchHistoryEntity>> = library.recentSearches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Requests for the activity to load a URL in the WebView (the controller lives there).
    private val _openUrlRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openUrlRequests: SharedFlow<String> = _openUrlRequests

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    private val _feedRefresh = MutableStateFlow(FeedRefreshState())
    val feedRefresh: StateFlow<FeedRefreshState> = _feedRefresh.asStateFlow()

    // ---- navigation ----
    private val _currentTab = MutableStateFlow(AppTab.HOME)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    /**
     * True while the YouTube page is shown instead of the native tab screens. A video page then
     * fills the screen with the player controls; any other page (search results, channel...)
     * keeps the app's tabs under it. False shows the native screens (and the mini-player).
     */
    private val _playerExpanded = MutableStateFlow(false)
    val playerExpanded: StateFlow<Boolean> = _playerExpanded.asStateFlow()

    /**
     * The page the WebView shows or is about to show: set as soon as the app asks for a page (so the
     * screen layout does not flash the previous page's layout) and updated on every navigation.
     */
    private val _webPageUrl = MutableStateFlow(HOME_URL)
    val webPageUrl: StateFlow<String> = _webPageUrl.asStateFlow()

    /**
     * The tab currently showing the browse page instead of its own screen (null: none). Opening
     * a search or channel puts the browse page into the current tab; tapping that tab again, or
     * Back from the first page, returns to the tab's own screen.
     */
    private val _browseTab = MutableStateFlow<AppTab?>(null)
    val browseTab: StateFlow<AppTab?> = _browseTab.asStateFlow()

    private val _browsePage = MutableStateFlow(BrowsePageState())
    val browsePage: StateFlow<BrowsePageState> = _browsePage.asStateFlow()

    // Requests for the activity to load a URL in the browse page.
    private val _browseRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val browseRequests: SharedFlow<String> = _browseRequests

    private val _playbackSource = MutableStateFlow(PlaybackSource.WEB)
    val playbackSource: StateFlow<PlaybackSource> = _playbackSource.asStateFlow()

    // ---- downloads ----
    val offlineItems: StateFlow<List<OfflineItemEntity>> = offlineDao.observeItems()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _downloadSheet = MutableStateFlow<DownloadSheetState?>(null)
    val downloadSheet: StateFlow<DownloadSheetState?> = _downloadSheet.asStateFlow()

    private val _offlinePlayRequests = MutableSharedFlow<OfflinePlayRequest>(extraBufferCapacity = 2)
    val offlinePlayRequests: SharedFlow<OfflinePlayRequest> = _offlinePlayRequests

    /** The offline player fills the screen (true) or shows as the mini-player (false). */
    private val _offlineExpanded = MutableStateFlow(false)
    val offlineExpanded: StateFlow<Boolean> = _offlineExpanded.asStateFlow()

    private val playLog = Channel<PlayLogOp>(Channel.UNLIMITED)

    private val _libraryRoute = MutableStateFlow<LibraryRoute>(LibraryRoute.Root)
    val libraryRoute: StateFlow<LibraryRoute> = _libraryRoute.asStateFlow()

    private val _showActionsSheet = MutableStateFlow(false)
    val showActionsSheet: StateFlow<Boolean> = _showActionsSheet.asStateFlow()

    /** Video id the user closed the mini-player for; it stays hidden until another video plays. */
    private val _miniPlayerDismissedFor = MutableStateFlow("")
    val miniPlayerDismissedFor: StateFlow<String> = _miniPlayerDismissedFor.asStateFlow()

    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    private val _videoState = MutableStateFlow(WebVideoState())
    val videoState: StateFlow<WebVideoState> = _videoState.asStateFlow()

    val playlistItemsFlow: (Long) -> kotlinx.coroutines.flow.Flow<List<PlaylistVideo>> = { id -> library.playlistVideos(id) }

    private val currentVideoId = _videoState.map { it.videoId }.distinctUntilChanged()

    val currentVideoActions: StateFlow<CurrentVideoActions> = combine(
        _videoState,
        library.subscribedIds,
        currentVideoId.flatMapLatest { id -> if (id.isBlank()) flowOf(emptyList()) else library.playlistsContaining(id) },
        library.feedbackTargets(FeedbackType.NOT_INTERESTED),
        playlists
    ) { video, subscribed, containing, notInterested, lists ->
        val likedId = lists.firstOrNull { it.builtIn == BuiltInPlaylist.LIKED.name }?.id
        val laterId = lists.firstOrNull { it.builtIn == BuiltInPlaylist.WATCH_LATER.name }?.id
        val ids = containing.toSet()
        CurrentVideoActions(
            videoId = video.videoId,
            title = video.title,
            channelId = video.channelId,
            channelName = video.channel,
            subscribed = video.channelId.isNotBlank() && video.channelId in subscribed,
            liked = likedId != null && likedId in ids,
            inWatchLater = laterId != null && laterId in ids,
            notInterested = video.videoId in notInterested,
            playlistIds = ids
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CurrentVideoActions())

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _showSpeedSheet = MutableStateFlow(false)
    val showSpeedSheet: StateFlow<Boolean> = _showSpeedSheet.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    /** The app is on screen (between the activity's onStart and onStop). */
    private val _appVisible = MutableStateFlow(true)
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
        if (visible && _currentTab.value == AppTab.HOME) refreshForYou(force = false)
        if (visible && _currentTab.value == AppTab.MUSIC && !_musicPageShown.value) {
            refreshMusicForYou(force = false)
            loadYtmHome(force = false)
        }
        if (!visible) viewModelScope.launch(Dispatchers.IO) {
            forYou.persist()
            musicForYou.persist()
        }
    }

    // ---- For you (Home) ----
    private val forYou = ForYouRepository(application)
    val forYouState: StateFlow<ForYouRepository.State> = forYou.state
    private val _homeRefreshing = MutableStateFlow(false)
    val homeRefreshing: StateFlow<Boolean> = _homeRefreshing.asStateFlow()
    private var homeRefreshJob: Job? = null

    // ---- Music tab: the app's music home (For you, recently played) or YouTube Music's page ----
    private val musicForYou = MusicForYouRepository(application)
    val musicForYouState: StateFlow<MusicForYouRepository.State> = musicForYou.state
    private val _musicRefreshing = MutableStateFlow(false)
    val musicRefreshing: StateFlow<Boolean> = _musicRefreshing.asStateFlow()
    private var musicRefreshJob: Job? = null
    /** The Music tab shows YouTube Music's page (true) or the app's music home (false). */
    private val _musicPageShown = MutableStateFlow(false)
    val musicPageShown: StateFlow<Boolean> = _musicPageShown.asStateFlow()
    /** YouTube Music pages to open without stopping a song that plays (see BrowserController). */
    private val _musicPageRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val musicPageRequests: SharedFlow<String> = _musicPageRequests
    /** YouTube Music's own home shelves, under the music home's own ones. */
    private val _ytmHome = MutableStateFlow(YtmHomeState())
    val ytmHome: StateFlow<YtmHomeState> = _ytmHome.asStateFlow()
    private var ytmPage: YtmPage? = null
    private var ytmPages = 0
    private var ytmJob: Job? = null

    // ---- pull-down on web pages ----
    private val _webPull = MutableStateFlow<WebPull?>(null)
    val webPull: StateFlow<WebPull?> = _webPull.asStateFlow()
    private val _webRefreshing = MutableStateFlow<PullSource?>(null)
    val webRefreshing: StateFlow<PullSource?> = _webRefreshing.asStateFlow()
    private val _reloadRequests = MutableSharedFlow<PullSource>(extraBufferCapacity = 2)
    val reloadRequests: SharedFlow<PullSource> = _reloadRequests
    private var refreshSawLoading = false
    private var refreshTimeoutJob: Job? = null

    /** What a pull-down does on the player page: minimize a video, refresh YouTube Music. */
    val playerPullMode: StateFlow<String> by lazy {
        combine(_playerExpanded, _webPageUrl, _browserState, _currentTab, _musicPageShown) { expanded, pageUrl, browser, tab, musicPage ->
            when {
                expanded && isYouTubeVideoPage(pageUrl) -> "minimize"
                !expanded && tab == AppTab.MUSIC && musicPage && browser.url.startsWith(MUSIC_HOME_URL) -> "refresh"
                else -> "off"
            }
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, "off")
    }

    // ---- opening a video: placeholder until it plays ----
    private val _pendingVideo = MutableStateFlow<PendingVideo?>(null)
    val pendingVideo: StateFlow<PendingVideo?> = _pendingVideo.asStateFlow()
    private var pendingVideoJob: Job? = null
    /** When the placeholder's current clear job fires (uptime ms); later requests do not delay it. */
    private var pendingClearAt = Long.MAX_VALUE

    // ---- Subscribe buttons on YouTube pages ----
    private val _unsubscribeRequest = MutableStateFlow<UnsubscribeRequest?>(null)
    val unsubscribeRequest: StateFlow<UnsubscribeRequest?> = _unsubscribeRequest.asStateFlow()
    // ---- YouTube's Like / Dislike buttons (kept in the app) ----
    @Volatile
    private var likedSnapshot: Set<String> = emptySet()
    @Volatile
    private var dislikedSnapshot: Set<String> = emptySet()
    private val _likesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Likes or dislikes changed: the page's Like buttons show the new state. */
    val likesChanged: SharedFlow<Unit> = _likesChanged
    private var lastLikeTap = 0L

    private val _subscriptionsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** The pages re-check their Subscribe buttons when this fires. */
    val subscriptionsChanged: SharedFlow<Unit> = _subscriptionsChanged
    /** Read on the pages' JavaScript bridge threads. */
    @Volatile
    private var subscribedSnapshot: Set<String> = emptySet()
    /** "/@name" (lowercase) -> channel id, learned when a handle is resolved. */
    private val channelHandles = ConcurrentHashMap<String, String>()

    // ---- diagnostics ----
    private val _tapFixes = MutableStateFlow(TapFixInfo())
    val tapFixes: StateFlow<TapFixInfo> = _tapFixes.asStateFlow()
    private var pageTapFixes = 0

    private val _autoplayDiagnostics = MutableStateFlow(AutoplayDiagnostics())
    val autoplayDiagnostics: StateFlow<AutoplayDiagnostics> = _autoplayDiagnostics.asStateFlow()

    private val _lastStart = MutableStateFlow<StartDiagnostics?>(null)
    val lastStart: StateFlow<StartDiagnostics?> = _lastStart.asStateFlow()

    // Most recent blocked requests (host + path), newest first.
    private val _recentBlocked = MutableStateFlow<List<String>>(emptyList())
    val recentBlocked: StateFlow<List<String>> = _recentBlocked.asStateFlow()

    private val _sponsorSegments = MutableStateFlow<SponsorSegments?>(null)
    val sponsorSegments: StateFlow<SponsorSegments?> = _sponsorSegments.asStateFlow()

    // Block counters: increments stay in memory and are flushed to DataStore in batches.
    private val pendingCounts = MutableStateFlow(BlockCounts())
    val blockCounts: StateFlow<BlockCounts> = combine(preferences, pendingCounts) { prefs, pending ->
        BlockCounts(prefs.adsBlockedCount + pending.ads, prefs.requestsBlockedCount + pending.requests)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BlockCounts())
    private var flushJob: Job? = null
    private val flushMutex = Mutex()
    private val progressMutex = Mutex()

    private var bannerJob: Job? = null
    private var sponsorJob: Job? = null
    private var bgSessionActive = false
    private var lastBgSignature = ""

    init {
        viewModelScope.launch(Dispatchers.IO) {
            SmartDownloads.schedule(getApplication(), prefsRepo.preferencesFlow.first().smartSizeMb)
        }
        viewModelScope.launch(Dispatchers.IO) { consumePlayLog() }
        viewModelScope.launch(Dispatchers.IO) {
            if (prefsRepo.preferencesFlow.first().autoBackupEnabled) AutoBackupWorker.schedule(getApplication(), true)
        }
        viewModelScope.launch(Dispatchers.IO) {
            library.ensureBuiltInPlaylists()
            // Refresh subscriptions on start if the last refresh is older than an hour. The newest
            // refresh decides: a channel whose feed keeps failing never gets a new time.
            val subs = library.allSubscriptions()
            val newest = subs.maxOfOrNull { it.lastRefreshedAt } ?: Long.MAX_VALUE
            if (subs.isNotEmpty() && System.currentTimeMillis() - newest > 60 * 60_000L) refreshFeeds()
        }
        viewModelScope.launch {
            PlaybackCommands.flow.collect { command ->
                // The user paused or stopped from the notification: no more "playing" between videos.
                if (command == PlaybackCommand.PAUSE || command == PlaybackCommand.STOP ||
                    command == PlaybackCommand.TOGGLE
                ) {
                    endAutoplayGap(GapEnd.USER)
                }
                if (command == PlaybackCommand.STOP) {
                    bgSessionActive = false
                    lastBgSignature = ""
                    servicePlaying = false
                }
            }
        }
        viewModelScope.launch {
            library.subscribedIds.collect { ids ->
                subscribedSnapshot = ids
                _subscriptionsChanged.tryEmit(Unit)
            }
        }
        viewModelScope.launch {
            playlists
                .map { lists -> lists.firstOrNull { it.builtIn == BuiltInPlaylist.LIKED.name }?.id }
                .distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else library.playlistVideos(id) }
                .collect { videos ->
                    likedSnapshot = videos.mapTo(HashSet()) { it.videoId }
                    _likesChanged.tryEmit(Unit)
                }
        }
        viewModelScope.launch {
            library.feedbackTargets(FeedbackType.DISLIKED).collect { ids ->
                dislikedSnapshot = ids.toHashSet()
                _likesChanged.tryEmit(Unit)
            }
        }
        viewModelScope.launch(Dispatchers.IO) { loadChannelHandles() }
        viewModelScope.launch(Dispatchers.IO) {
            forYou.load()
            refreshForYou(force = false)
        }
        viewModelScope.launch(Dispatchers.IO) { musicForYou.load() }
    }

    // ---------------------------------------------------------------------------------------
    // Navigation & page state
    // ---------------------------------------------------------------------------------------

    fun onPageChanged(url: String, loading: Boolean) {
        val previousUrl = _browserState.value.url
        _browserState.value = BrowserUiState(url = url, isLoading = loading)
        if (url != previousUrl) {
            _webPageUrl.value = url
            handleSearchPage(url)
        }
        if (_webRefreshing.value == PullSource.PLAYER) onRefreshLoading(loading)
        val pending = _pendingVideo.value
        if (pending != null && !loading && extractYouTubeVideoId(url) == pending.videoId) {
            // The page is there; if the video does not start on its own, show the page anyway.
            schedulePendingClear(PENDING_AFTER_LOAD_MS)
        }
    }

    /** YouTube logo (or an in-page link to YouTube's home page): the app's Home tab instead. */
    fun onHomeLogoTapped() {
        if (_currentTab.value == AppTab.MUSIC && !_playerExpanded.value && _musicPageShown.value &&
            _browserState.value.url.startsWith(MUSIC_HOME_URL)
        ) return
        _browseTab.value = null
        _playerExpanded.value = false
        _showActionsSheet.value = false
        _currentTab.value = AppTab.HOME
    }

    /** Page changes in the browse page (loading bar, searches typed into YouTube's search box). */
    fun onBrowsePageChanged(url: String, loading: Boolean) {
        val previous = _browsePage.value.url
        _browsePage.value = BrowsePageState(url, loading)
        if (url != previous) handleSearchPage(url)
        if (_webRefreshing.value == PullSource.BROWSE) onRefreshLoading(loading)
    }

    private var lastSearchRedirect = ""

    /**
     * Searches typed into YouTube's or YouTube Music's own search box land on a results page.
     * If the "query" is really a pasted link, open the link instead; otherwise remember the query
     * (search history feeds recommendations).
     */
    private fun handleSearchPage(url: String) {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return }
        val query = when {
            uri.host == "music.youtube.com" && uri.path == "/search" -> uri.getQueryParameter("q")
            uri.path == "/results" -> uri.getQueryParameter("search_query")
            else -> null
        }?.trim().orEmpty()
        if (query.isEmpty()) return
        val link = youTubeLinkOrNull(query)
        if (link != null) {
            if (link != lastSearchRedirect) {
                lastSearchRedirect = link
                openLink(link)
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) { library.recordSearch(query) }
    }

    fun onVideoState(state: WebVideoState) {
        if (_playbackSource.value != PlaybackSource.WEB) {
            // While a download plays, the (paused) web player's reports are not the current video,
            // unless the web page starts playing (e.g. a song tapped in YouTube Music): then it
            // takes over the mini-player, notification and media buttons.
            if (!state.isPlaying || state.videoId.isBlank()) return
            switchToWebPlayback()
        }
        applyVideoState(state)
    }

    private fun applyVideoState(state: WebVideoState) {
        val previous = _videoState.value
        _videoState.value = state

        _pendingVideo.value?.let { pending ->
            if (state.videoId == pending.videoId) {
                // Playing: show the page now. Otherwise the new video's page is there (its script
                // reported it): give YouTube a moment to draw it. (Waiting for "playing" alone
                // left the placeholder up for its full time on a phone.)
                if (state.isPlaying || state.positionSec > 0 || state.ended) clearPendingVideo()
                else schedulePendingClear(PENDING_AFTER_PAGE_MS)
            }
        }
        if (state.tapFixes < pageTapFixes) pageTapFixes = 0   // a new page counts from zero
        if (state.tapFixes > pageTapFixes) {
            _tapFixes.value = TapFixInfo(_tapFixes.value.count + (state.tapFixes - pageTapFixes), state.tapFixTop)
            pageTapFixes = state.tapFixes
        }
        if (state.autoAdvance.isNotBlank() && state.autoAdvanceAt != _autoplayDiagnostics.value.methodAt) {
            _autoplayDiagnostics.update { it.copy(method = state.autoAdvance, methodAt = state.autoAdvanceAt) }
        }

        if (state.startMs >= 0 && (state.videoId != previous.videoId || previous.startMs < 0)) {
            val start = StartDiagnostics(
                title = state.title,
                loadMs = state.loadMs,
                startMs = state.startMs,
                adMs = state.adMs,
                adsPruned = state.adsPruned,
                adBlockEnabled = preferences.value.adBlockEnabled,
                timersBoosted = state.timersBoosted
            )
            _lastStart.value = start
            if (preferences.value.showStartTime && !_isInPipMode.value && !state.isMusicPage) showStartNote(start)
        }

        if (state.videoId.isNotBlank() && state.videoId != previous.videoId) {
            val prefs = preferences.value
            if (!prefs.rememberSpeedAcrossVideos && previous.videoId.isNotBlank() && prefs.playbackSpeed != 1.0f) {
                setPlaybackSpeed(1.0f)
            }
            if (_playbackSource.value == PlaybackSource.WEB) loadSponsorSegments(state.videoId)
        }

        recordProgress(previous, state)

        syncBackgroundService(state)
    }

    // ---------------------------------------------------------------------------------------
    // Watch history (batched: on start, pause, end, video change and at most every 15 s)
    // ---------------------------------------------------------------------------------------

    private var progressVideoId = ""
    private var lastProgressWriteAt = 0L
    private var lastWrittenPosition = -1
    private var metadataWrittenFor = ""

    private fun recordProgress(previous: WebVideoState, state: WebVideoState) {
        if (preferences.value.historyPaused) return
        val id = state.videoId
        if (id.isBlank()) return
        val newVideo = id != progressVideoId
        // A video counts once it has actually played.
        if (newVideo && !state.isPlaying) return

        // Leaving the previous video: store where it was left.
        if (newVideo && progressVideoId.isNotBlank() && previous.videoId == progressVideoId &&
            previous.positionSec != lastWrittenPosition
        ) {
            writeProgress(previous, newSession = false)
        }

        val now = System.currentTimeMillis()
        val pausedOrEnded = (previous.isPlaying && !state.isPlaying) || (state.ended && !previous.ended)
        val due = now - lastProgressWriteAt >= 15_000L
        val metadataArrived = state.channelId.isNotBlank() && metadataWrittenFor != id
        if (!newVideo && !pausedOrEnded && !metadataArrived && !(due && state.positionSec != lastWrittenPosition)) return

        progressVideoId = id
        lastProgressWriteAt = now
        writeProgress(state, newSession = newVideo)
    }

    private fun writeProgress(state: WebVideoState, newSession: Boolean) {
        val duration = state.bestDurationSec
        val position = if (state.ended && duration > 0) duration else state.positionSec
        lastWrittenPosition = state.positionSec
        val writeMetadata = newSession || (state.channelId.isNotBlank() && metadataWrittenFor != state.videoId)
        if (state.channelId.isNotBlank()) metadataWrittenFor = state.videoId
        val snapshot = state
        viewModelScope.launch(Dispatchers.IO) { progressMutex.withLock {
            if (writeMetadata) {
                library.rememberVideo(
                    videoId = snapshot.videoId,
                    title = snapshot.title,
                    channelId = snapshot.channelId,
                    channelName = snapshot.channel,
                    durationSec = duration,
                    viewCount = snapshot.viewCount,
                    isMusic = snapshot.isMusic
                )
            }
            library.recordProgress(snapshot.videoId, position, duration, snapshot.isMusic, newSession)
        } }
        playLog.trySend(
            if (newSession) {
                PlayLogOp.Start(state.videoId, System.currentTimeMillis(), position, duration, state.isMusic)
            } else {
                PlayLogOp.Progress(state.videoId, position, duration, state.ended)
            }
        )
    }

    /**
     * The listening log used by smart downloads: one row per play with how far it got. A single
     * consumer applies the operations in order (start of a play, then its progress updates).
     */
    private suspend fun consumePlayLog() {
        var playId = -1L
        var playVideo = ""
        var lastPosition = 0
        var listened = 0
        for (op in playLog) {
            try {
                when (op) {
                    is PlayLogOp.Start -> {
                        playVideo = op.videoId
                        lastPosition = op.position
                        listened = 0
                        playId = offlineDao.insertPlay(
                            PlayEventEntity(
                                videoId = op.videoId,
                                startedAt = op.at,
                                listenedSec = 0,
                                durationSec = op.duration,
                                completed = false,
                                isMusic = op.isMusic
                            )
                        )
                    }
                    is PlayLogOp.Progress -> {
                        if (op.videoId != playVideo || playId < 0) continue
                        // Progress is written at most every ~15-20 s while playing; a bigger jump
                        // is a seek and does not count as listening.
                        val step = op.position - lastPosition
                        if (step in 1..MAX_LISTEN_STEP_SEC) listened += step
                        lastPosition = op.position
                        val completed = (op.duration > 0 && listened >= 0.8 * op.duration) ||
                            (op.ended && op.duration > 0 && listened >= 0.5 * op.duration)
                        val counted = if (op.duration > 0) minOf(listened, op.duration) else listened
                        offlineDao.updatePlay(playId, counted, op.duration, completed)
                    }
                }
            } catch (_: Exception) {
                // The log is best effort; a failed write must not stop the consumer.
            }
        }
    }

    private fun loadSponsorSegments(videoId: String) {
        sponsorJob?.cancel()
        if (!preferences.value.sponsorSkipEnabled) return
        sponsorJob = viewModelScope.launch {
            val segments = withContext(Dispatchers.IO) { sponsorBlock.fetchSegments(videoId) }
            _sponsorSegments.value = SponsorSegments(videoId, SponsorBlockClient.segmentsToJson(segments))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ad-block counters (called from WebView threads)
    // ---------------------------------------------------------------------------------------

    /** An ad/tracker network request was refused (called on WebView network threads). */
    fun onRequestBlocked(url: String) {
        val entry = url.substringBefore('?').removePrefix("https://").removePrefix("http://").take(90)
        _recentBlocked.update { (listOf(entry) + it).take(12) }
        pendingCounts.update { it.copy(requests = it.requests + 1) }
        scheduleCountFlush()
    }

    /** A whole ad was prevented (removed from a YouTube response, or skipped in the player). */
    fun onAdBlocked() {
        pendingCounts.update { it.copy(ads = it.ads + 1) }
        scheduleCountFlush()
    }

    @Synchronized
    private fun scheduleCountFlush() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            delay(5_000L)
            flushCounts()
        }
    }

    private suspend fun flushCounts() = flushMutex.withLock {
        val snapshot = pendingCounts.value
        if (snapshot.ads == 0L && snapshot.requests == 0L) return@withLock
        prefsRepo.addBlockedCounts(snapshot.ads, snapshot.requests)
        pendingCounts.update { BlockCounts(it.ads - snapshot.ads, it.requests - snapshot.requests) }
    }

    /** Called when the app goes to the background so a process kill loses nothing. */
    fun flushCountsNow() {
        viewModelScope.launch { flushCounts() }
    }

    fun resetBlockCounts() {
        viewModelScope.launch {
            flushMutex.withLock {
                pendingCounts.value = BlockCounts()
                prefsRepo.resetBlockedCounts()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Background playback
    // ---------------------------------------------------------------------------------------

    private fun syncBackgroundService(state: WebVideoState) {
        if (!preferences.value.backgroundPlayEnabled) {
            stopBackgroundSession()
            return
        }
        // Always evaluated (not `isPlaying || hold…`): it is also what ends the gap once the next
        // video plays. Skipped, the gap timed out as OVER and stayed so, and the next video's end
        // took the service out of the foreground, which Android does not let it re-enter while
        // the screen is off.
        val holding = holdThroughAutoplayGap(state)
        val playing = state.isPlaying || holding
        // Nothing to show until something actually plays; after that keep the controls updated.
        if (!playing && !bgSessionActive) return
        val signature = "${state.title}|${state.channel}|${state.durationSec}|$playing"
        if (signature == lastBgSignature) return
        BackgroundPlaybackService.update(
            context = getApplication(),
            title = state.title,
            artist = state.channel,
            durationSec = state.durationSec,
            playing = playing,
            alreadyRunning = bgSessionActive
        )
        bgSessionActive = true
        lastBgSignature = signature
        servicePlaying = playing
    }

    private fun stopBackgroundSession() {
        if (bgSessionActive) BackgroundPlaybackService.stop(getApplication())
        bgSessionActive = false
        lastBgSignature = ""
        servicePlaying = false
        endAutoplayGap(GapEnd.USER)
    }

    // ---- the gap between two videos while the app is hidden ----------------------------------
    // When a video ends with the screen off, the next one takes a few seconds to start (the page
    // opens it, see "autoplay while the app is hidden" in shield.js). Reporting "paused" to the
    // notification service in that gap made it leave the foreground and release its wake lock:
    // Android may then suspend the app before the next video starts, and from the background the
    // service can neither be updated (startService is refused) nor return to the foreground
    // (Android 12+). So for up to AUTOPLAY_GAP_HOLD_MS after a video ends while the app is hidden,
    // the service keeps showing "playing"; the next video playing, a pause or stop from the
    // notification, or the time limit end the gap.
    private enum class Gap { NONE, HOLDING, OVER }
    private enum class GapEnd { PLAYING, USER, TIMEOUT }
    private var gap = Gap.NONE
    private var gapStartedAt = 0L
    private var gapJob: Job? = null
    /** What the notification service was last told (true = playing). */
    private var servicePlaying = false

    private fun holdThroughAutoplayGap(state: WebVideoState): Boolean {
        if (state.isPlaying) {
            endAutoplayGap(GapEnd.PLAYING)
            return false
        }
        if (gap == Gap.NONE && servicePlaying && reachedEnd(state) &&
            _playbackSource.value == PlaybackSource.WEB &&
            (!_appVisible.value || _isInPipMode.value)
        ) {
            gap = Gap.HOLDING
            gapStartedAt = android.os.SystemClock.elapsedRealtime()
            gapJob?.cancel()
            gapJob = viewModelScope.launch {
                delay(AUTOPLAY_GAP_HOLD_MS)
                gapJob = null   // this job: endAutoplayGap must not cancel it
                if (gap == Gap.HOLDING) {
                    endAutoplayGap(GapEnd.TIMEOUT)
                    syncBackgroundService(_videoState.value)
                }
            }
        }
        return gap == Gap.HOLDING
    }

    /** Ended, or paused within the last two seconds (some players pause at the end instead). */
    private fun reachedEnd(state: WebVideoState): Boolean =
        state.ended || (state.durationSec > 0 && state.positionSec >= state.durationSec - 2)

    private fun endAutoplayGap(reason: GapEnd) {
        if (gap == Gap.HOLDING) {
            gapJob?.cancel()
            gapJob = null
            val seconds = (android.os.SystemClock.elapsedRealtime() - gapStartedAt) / 1000.0
            val result = when (reason) {
                GapEnd.PLAYING -> String.format(java.util.Locale.US, "started after %.1f s", seconds)
                GapEnd.USER -> "paused or stopped from the notification"
                GapEnd.TIMEOUT -> "nothing started within ${AUTOPLAY_GAP_HOLD_MS / 1000} s"
            }
            _autoplayDiagnostics.update { it.copy(gapResult = result) }
        }
        // Over until something plays again, so the same ended video does not start a new gap.
        gap = if (reason == GapEnd.PLAYING) Gap.NONE else Gap.OVER
    }

    // ---------------------------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------------------------

    fun setPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    fun openSettings() {
        _showSettings.value = true
    }

    fun closeSettings() {
        _showSettings.value = false
    }

    fun openSpeedSheet() {
        _showSpeedSheet.value = true
    }

    fun dismissSpeedSheet() {
        _showSpeedSheet.value = false
    }

    fun showBanner(message: String) = showBannerFor(message, 2500L)

    @Synchronized
    private fun showBannerFor(message: String, durationMs: Long) {
        bannerJob?.cancel()
        _statusBanner.value = message
        bannerJob = viewModelScope.launch {
            delay(durationMs)
            _statusBanner.value = null
        }
    }

    /**
     * "Started in 1.3 s · data 0.9 s · 3 ads removed": shown when a video starts (Settings →
     * Diagnostics can turn it off). "data" is when the video's data was loaded; a long gap after
     * it means the player waited.
     */
    private fun startTimeNote(start: StartDiagnostics): String = buildString {
        append("Started in ").append(seconds(start.startMs))
        if (start.loadMs >= 0) append(" · data ").append(seconds(start.loadMs))
        append(" · ").append(start.adsPruned).append(if (start.adsPruned == 1) " ad removed" else " ads removed")
        if (start.adMs > 0) append(" · ").append(seconds(start.adMs)).append(" in ad")
        if (start.timersBoosted > 0) append(" · timer skipped")
        if (!start.adBlockEnabled) append(" · ad shield off")
    }

    private fun seconds(ms: Int): String = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    private fun showStartNote(start: StartDiagnostics) = showBannerFor(startTimeNote(start), 4000L)

    fun dismissBanner() {
        _statusBanner.value = null
    }

    // ---------------------------------------------------------------------------------------
    // Preferences
    // ---------------------------------------------------------------------------------------

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch { prefsRepo.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f)) }
    }

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setDarkMode(enabled) }
    }

    fun toggleAmoledBlack(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setAmoledBlackMode(enabled) }
    }

    fun toggleAdBlock(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAdBlockEnabled(enabled)
            showBanner(if (enabled) "Ad shield on" else "Ad shield off (reload to see ads)")
        }
    }

    fun toggleSponsorSkip(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setSponsorSkipEnabled(enabled)
            val videoId = _videoState.value.videoId
            if (enabled && videoId.isNotBlank()) loadSponsorSegments(videoId)
        }
    }

    fun toggleBackgroundPlay(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setBackgroundPlayEnabled(enabled)
            if (enabled) {
                syncBackgroundService(_videoState.value)
            } else {
                stopBackgroundSession()
            }
            showBanner(if (enabled) "Background play on" else "Background play off")
        }
    }

    fun toggleShowStartTime(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setShowStartTime(enabled) }
    }

    fun toggleAutoplay(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setAutoplay(enabled) }
    }

    fun toggleAutoPip(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setAutoPipOnLeave(enabled) }
    }

    fun toggleRememberSpeed(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setRememberSpeed(enabled) }
    }

    // ---------------------------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------------------------

    fun clearWatchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            library.clearHistory()
            offlineDao.clearPlays()
            database.watchHistoryDao().clearAllHistory()
            showBanner("Watch history cleared")
        }
    }

    fun deleteHistoryEntry(videoId: String) {
        viewModelScope.launch(Dispatchers.IO) { library.deleteHistoryEntry(videoId) }
    }

    fun toggleHistoryPaused(paused: Boolean) {
        viewModelScope.launch {
            prefsRepo.setHistoryPaused(paused)
            showBanner(if (paused) "History paused" else "History on")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------

    /**
     * Opens a YouTube page: videos (and YouTube Music) in the player page, everything else
     * (results, channels, playlists) in the browse page.
     */
    fun openInPlayer(url: String) {
        if (!isYouTubeVideoPage(url) && !url.startsWith(MUSIC_HOME_URL)) {
            openBrowse(url)
            return
        }
        switchToWebPlayback()
        _showSettings.value = false
        val id = extractYouTubeVideoId(url)
        if (id != null && isYouTubeVideoPage(url) && id != _videoState.value.videoId) {
            val known = knownVideo(id)
            pendingVideoJob?.cancel()
            pendingClearAt = Long.MAX_VALUE
            _pendingVideo.value = PendingVideo(id, known?.first.orEmpty(), known?.second.orEmpty())
            schedulePendingClear(PENDING_MAX_MS)
        }
        _playerExpanded.value = true
        _webPageUrl.value = url
        _openUrlRequests.tryEmit(url)
    }

    /** Title and channel of a video the app already lists somewhere (For you, feed, history). */
    private fun knownVideo(videoId: String): Pair<String, String>? {
        forYouState.value.items.firstOrNull { it.videoId == videoId }?.let { return it.title to it.channelName }
        feed.value.firstOrNull { it.videoId == videoId }?.let { return it.title to it.channelName }
        history.value.firstOrNull { it.videoId == videoId }?.let { return it.title to it.channelName }
        return null
    }

    /** Hides the placeholder after [afterMs], unless it is already due to go sooner. */
    private fun schedulePendingClear(afterMs: Long) {
        val at = android.os.SystemClock.uptimeMillis() + afterMs
        if (pendingVideoJob?.isActive == true && at >= pendingClearAt) return
        pendingVideoJob?.cancel()
        pendingClearAt = at
        pendingVideoJob = viewModelScope.launch {
            delay(afterMs)
            _pendingVideo.value = null
            pendingClearAt = Long.MAX_VALUE
        }
    }

    private fun clearPendingVideo() {
        pendingVideoJob?.cancel()
        pendingClearAt = Long.MAX_VALUE
        _pendingVideo.value = null
    }

    fun openVideo(videoId: String, startSec: Int = 0) {
        _miniPlayerDismissedFor.value = ""
        openInPlayer(watchUrl(videoId, startSec))
    }

    /** A video tapped in the browse page (or a link): play it in the player page. */
    fun openVideoUrl(url: String) {
        _miniPlayerDismissedFor.value = ""
        val link = youTubeLinkOrNull(url) ?: url
        openInPlayer(link)
    }

    /** Shows [url] in the browse page inside the current tab (Music tab: Home). */
    fun openBrowse(url: String) {
        _showSettings.value = false
        _playerExpanded.value = false
        _showActionsSheet.value = false
        val tab = if (_currentTab.value == AppTab.MUSIC) AppTab.HOME else _currentTab.value
        _currentTab.value = tab
        _browseTab.value = tab
        _browsePage.value = BrowsePageState(url, loading = true)
        _browseRequests.tryEmit(url)
    }

    /** Back from the first browse page: the tab's own screen again. */
    fun closeBrowse() {
        _browseTab.value = null
    }

    /** Search box: a pasted YouTube link opens directly, anything else is a YouTube search. */
    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val link = youTubeLinkOrNull(q)
        if (link != null) {
            openLink(link)
            return
        }
        viewModelScope.launch(Dispatchers.IO) { library.recordSearch(q) }
        openBrowse("$HOME_URL/results?search_query=${Uri.encode(q)}")
    }

    /**
     * Opens a YouTube / YouTube Music link (shared to the app, pasted into a search box): music
     * links go to the Music tab, videos to the player page, other pages to the browse page.
     */
    fun openLink(url: String) {
        _miniPlayerDismissedFor.value = ""
        _showSettings.value = false
        when {
            url.startsWith(MUSIC_HOME_URL) -> {
                switchToWebPlayback()
                _webPageUrl.value = url
                _currentTab.value = AppTab.MUSIC
                _musicPageShown.value = true
                _playerExpanded.value = false
                _openUrlRequests.tryEmit(url)
            }
            isYouTubeVideoPage(url) -> openInPlayer(url)
            else -> openBrowse(url)
        }
    }

    fun deleteSearch(query: String) {
        viewModelScope.launch(Dispatchers.IO) { library.deleteSearch(query) }
    }

    fun selectTab(tab: AppTab) {
        val sameTab = tab == _currentTab.value
        if (sameTab && tab == AppTab.LIBRARY) _libraryRoute.value = LibraryRoute.Root
        // Tapping the tab that shows the browse page again: back to the tab's own screen.
        if (sameTab && _browseTab.value == tab && !_playerExpanded.value) _browseTab.value = null
        _currentTab.value = tab
        _playerExpanded.value = false
        if (tab == AppTab.HOME) refreshForYou(force = false)
        if (tab == AppTab.MUSIC) {
            // The Music tab opens on the app's music home (also when tapped again on YouTube
            // Music's page). YouTube Music itself is loaded only when a song, a search or its
            // home is opened from there, so a paused video is not replaced on the way.
            _musicPageShown.value = false
            refreshMusicForYou(force = false)
            loadYtmHome(force = false)
        }
    }

    /**
     * YouTube Music's own home page. With a song loaded there, its page is shown as it is
     * (loading the home page would stop the song).
     */
    fun openYouTubeMusic() {
        switchToWebPlayback()
        _currentTab.value = AppTab.MUSIC
        _playerExpanded.value = false
        _musicPageShown.value = true
        val video = _videoState.value
        val songLoaded = _browserState.value.url.startsWith(MUSIC_HOME_URL) && video.isMusicPage && video.videoId.isNotBlank()
        if (!songLoaded) _openUrlRequests.tryEmit(MUSIC_HOME_URL)
    }

    /** Back to YouTube Music's page as it is (the song's player): the music mini-player's tap. */
    fun showMusicPage() {
        if (!_browserState.value.url.startsWith(MUSIC_HOME_URL)) {
            openYouTubeMusic()
            return
        }
        _currentTab.value = AppTab.MUSIC
        _playerExpanded.value = false
        _musicPageShown.value = true
    }

    /** From YouTube Music's page back to the app's music home (the song keeps playing). */
    fun showMusicHome() {
        _currentTab.value = AppTab.MUSIC
        _musicPageShown.value = false
    }

    /** A song from the music home: plays in YouTube Music, whose radio continues after it. */
    fun playSong(videoId: String) {
        if (videoId.isBlank()) return
        musicForYou.opened(videoId)
        openLink("$MUSIC_HOME_URL/watch?v=$videoId")
    }

    /** A search from the music home, on YouTube Music's search page (a playing song goes on). */
    fun searchMusic(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        youTubeLinkOrNull(q)?.let { link ->
            openLink(link)
            return
        }
        switchToWebPlayback()
        _currentTab.value = AppTab.MUSIC
        _playerExpanded.value = false
        _musicPageShown.value = true
        _musicPageRequests.tryEmit("$MUSIC_HOME_URL/search?q=" + Uri.encode(q))
    }

    fun expandPlayer() {
        _playerExpanded.value = true
    }

    fun collapsePlayer() {
        _playerExpanded.value = false
        _showActionsSheet.value = false
        clearPendingVideo()
    }

    fun dismissMiniPlayer() {
        _miniPlayerDismissedFor.value = _videoState.value.videoId
    }

    fun openLibraryRoute(route: LibraryRoute) {
        _currentTab.value = AppTab.LIBRARY
        _libraryRoute.value = route
    }

    /** Back inside the tabs: Library sub-screen -> Library root -> Home. Returns false at Home. */
    fun navigateBackInTabs(): Boolean {
        if (_currentTab.value == AppTab.LIBRARY && _libraryRoute.value != LibraryRoute.Root) {
            _libraryRoute.value = LibraryRoute.Root
            return true
        }
        if (_currentTab.value != AppTab.HOME) {
            _currentTab.value = AppTab.HOME
            return true
        }
        return false
    }

    fun openActionsSheet() {
        if (_videoState.value.videoId.isNotBlank()) _showActionsSheet.value = true
    }

    fun dismissActionsSheet() {
        _showActionsSheet.value = false
    }


    // ---------------------------------------------------------------------------------------
    // For you (Home), stars, pull-down
    // ---------------------------------------------------------------------------------------

    /** Rebuilds the Home tab's "For you" list ([force]: even if it is recent). */
    fun refreshForYou(force: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            forYou.refresh(prefsRepo.preferencesFlow.first().starredChannels, force)
        }
    }

    /** Pull-down on Home: new uploads from subscriptions, then a new "For you" list. */
    fun refreshHome() {
        if (homeRefreshJob?.isActive == true) return
        homeRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            _homeRefreshing.value = true
            try {
                if (library.allSubscriptions().isNotEmpty()) refreshFeedsNow()
                forYou.refresh(prefsRepo.preferencesFlow.first().starredChannels, force = true)
            } finally {
                _homeRefreshing.value = false
            }
        }
    }

    fun forYouNotInterested(video: ForYouVideo) {
        viewModelScope.launch(Dispatchers.IO) {
            library.setFeedback(video.videoId, FeedbackType.NOT_INTERESTED, true)
            forYou.remove(video.videoId)
            showBanner("Got it: fewer videos like this")
        }
    }

    /** Home reached the end of the loaded "For you" videos: add more. */
    fun forYouLoadMore() {
        viewModelScope.launch(Dispatchers.IO) {
            forYou.extend(prefsRepo.preferencesFlow.first().starredChannels)
        }
    }

    /** "For you" videos that were on screen (they count as shown in the next list). */
    fun forYouSeen(videoIds: Collection<String>) = forYou.markSeen(videoIds)

    /** A "For you" video was opened (so it was not skipped). */
    fun forYouOpened(videoId: String) = forYou.opened(videoId)

    // ---------------------------------------------------------------------------------------
    // For you (Music tab)
    // ---------------------------------------------------------------------------------------

    /** Rebuilds the Music tab's "For you" list ([force]: even if it is recent). */
    fun refreshMusicForYou(force: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { musicForYou.refresh(force) }
    }

    /** Pull-down on the music home: a new list. */
    fun refreshMusicHome() {
        if (musicRefreshJob?.isActive == true) return
        musicRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            _musicRefreshing.value = true
            try {
                loadYtmHome(force = true)
                musicForYou.refresh(force = true)
            } finally {
                _musicRefreshing.value = false
            }
        }
    }

    /** A radio (YouTube Music's endless mix) started from a song. */
    fun playRadio(videoId: String) {
        if (videoId.isBlank()) return
        openLink("$MUSIC_HOME_URL/watch?v=$videoId&list=RDAMVM$videoId")
    }

    /** An artist's page in YouTube Music (a search for the name if the channel is not known). */
    fun openMusicArtist(channelId: String, name: String) {
        if (channelId.startsWith("UC")) openLink("$MUSIC_HOME_URL/channel/$channelId")
        else searchMusic(name.removeSuffix(" - Topic"))
    }

    // ---- YouTube Music's own home shelves --------------------------------------------------

    /** Loads YouTube Music's home (or the selected chip's), unless loaded within the hour. */
    fun loadYtmHome(force: Boolean) {
        val current = _ytmHome.value
        if (!force && (current.loading || (current.shelves.isNotEmpty() &&
                System.currentTimeMillis() - current.loadedAt < YTM_HOME_TTL_MS))
        ) return
        ytmJob?.cancel()
        val chip = current.selected
        ytmJob = viewModelScope.launch(Dispatchers.IO) {
            _ytmHome.update { it.copy(loading = true, error = "") }
            try {
                val page = YouTubeMusicHome.fetch(chip)
                ytmPage = page
                ytmPages = 1
                _ytmHome.update {
                    it.copy(
                        chips = if (chip.isBlank() && page.chips.isNotEmpty()) page.chips else it.chips,
                        shelves = page.shelves,
                        loading = false,
                        hasMore = page.continuation.isNotBlank(),
                        loadedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("StreamShield", "YouTube Music home", e)
                val why = listOfNotNull(e.javaClass.simpleName, e.message?.take(100)).joinToString(": ")
                _ytmHome.update { it.copy(loading = false, error = "YouTube Music's own shelves could not be loaded ($why). Pull down to try again.") }
            }
        }
    }

    /** A mood chip: YouTube Music's home for it (the same chip again: the whole home). */
    fun selectYtmChip(params: String) {
        val next = if (_ytmHome.value.selected == params) "" else params
        _ytmHome.update { it.copy(selected = next, shelves = emptyList(), hasMore = false) }
        loadYtmHome(force = true)
    }

    /** The end of the shelves was reached: YouTube Music's next ones (a few pages at most). */
    fun loadMoreYtm() {
        val page = ytmPage ?: return
        if (page.continuation.isBlank() || ytmPages >= YTM_MAX_PAGES || ytmJob?.isActive == true) return
        ytmJob = viewModelScope.launch(Dispatchers.IO) {
            _ytmHome.update { it.copy(loadingMore = true) }
            try {
                val next = YouTubeMusicHome.more(page)
                ytmPage = next
                ytmPages++
                _ytmHome.update {
                    it.copy(
                        shelves = it.shelves + next.shelves,
                        loadingMore = false,
                        hasMore = next.continuation.isNotBlank() && ytmPages < YTM_MAX_PAGES
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("StreamShield", "YouTube Music home, more", e)
                _ytmHome.update { it.copy(loadingMore = false, hasMore = false) }
            }
        }
    }

    fun openYtmItem(item: YtmItem) = openLink(YouTubeMusicHome.urlFor(item, MUSIC_HOME_URL))

    fun musicLoadMore() {
        viewModelScope.launch(Dispatchers.IO) { musicForYou.extend() }
    }

    /** Songs of the music "For you" that were on screen (they count as shown in the next list). */
    fun musicSeen(videoIds: Collection<String>) = musicForYou.markSeen(videoIds)

    fun musicNotInterested(song: MusicSong) {
        viewModelScope.launch(Dispatchers.IO) {
            library.setFeedback(song.videoId, FeedbackType.NOT_INTERESTED, true)
            musicForYou.remove(song.videoId)
            showBanner("Got it: fewer songs like this")
        }
    }

    /** YouTube Music's queue for a song, from the page (called on the WebView's bridge thread). */
    fun onMusicQueue(json: String) {
        val (seed, items) = MusicForYouRepository.parseQueue(json) ?: return
        musicForYou.addQueue(seed, items)
    }

    // ---------------------------------------------------------------------------------------
    // YouTube's Like / Dislike buttons on a video page (kept in the app; no Google account)
    // ---------------------------------------------------------------------------------------

    /** "like", "dislike" or "" for [videoId] (called on the WebView's bridge thread). */
    fun pageLikeState(videoId: String): String = when (videoId) {
        in likedSnapshot -> "like"
        in dislikedSnapshot -> "dislike"
        else -> ""
    }

    /**
     * A tap on YouTube's Like or Dislike button ([kind] "like" / "dislike") for [videoId]:
     * toggles it. Like = Library > Liked videos; dislike = left out of Home and a lower weight
     * for its channel there. One excludes the other.
     */
    fun onPageLikeTap(videoId: String, kind: String) {
        if (videoId.isBlank() || (kind != "like" && kind != "dislike")) return
        val now = System.currentTimeMillis()
        if (now - lastLikeTap < 500) return
        lastLikeTap = now
        val video = _videoState.value
        viewModelScope.launch(Dispatchers.IO) {
            if (video.videoId == videoId) rememberCurrent(video)
            val liked = videoId in likedSnapshot
            val disliked = videoId in dislikedSnapshot
            if (kind == "like") {
                if (disliked) library.setFeedback(videoId, FeedbackType.DISLIKED, false)
                val nowLiked = library.toggleBuiltIn(BuiltInPlaylist.LIKED, videoId, liked)
                showBanner(if (nowLiked) "Liked" else "Removed from liked")
            } else {
                if (liked) library.toggleBuiltIn(BuiltInPlaylist.LIKED, videoId, true)
                library.setFeedback(videoId, FeedbackType.DISLIKED, !disliked)
                if (!disliked) {
                    forYou.remove(videoId)
                    musicForYou.remove(videoId)
                }
                showBanner(if (!disliked) "Disliked: fewer videos like this" else "Dislike removed")
            }
        }
    }

    fun toggleStar(channelId: String) {
        if (channelId.isBlank()) return
        val starred = channelId in preferences.value.starredChannels
        viewModelScope.launch(Dispatchers.IO) {
            prefsRepo.setStarred(channelId, !starred)
            showBanner(if (starred) "Star removed" else "Starred: more of this channel in For you")
        }
    }

    /** A pull-down in progress on a web page (from the page script). */
    fun onWebPull(source: PullSource, mode: String, distance: Float) {
        _webPull.value = WebPull(source, mode, distance)
    }

    /** The finger was lifted: refresh, minimize, or nothing (too short / cancelled). */
    fun onWebPullEnd(source: PullSource, mode: String, distance: Float, velocity: Float, cancelled: Boolean) {
        _webPull.value = null
        if (cancelled) return
        when (mode) {
            "refresh" -> if (distance * PULL_DRAG_RATE >= PULL_REFRESH_THRESHOLD_DP && _webRefreshing.value == null) {
                startWebRefresh(source)
            }
            "minimize" -> if (source == PullSource.PLAYER && _playerExpanded.value &&
                (distance >= 140f || (velocity >= 0.8f && distance >= 48f))
            ) {
                collapsePlayer()
            }
        }
    }

    private fun startWebRefresh(source: PullSource) {
        _webRefreshing.value = source
        refreshSawLoading = false
        _reloadRequests.tryEmit(source)
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = viewModelScope.launch {
            delay(WEB_REFRESH_MAX_MS)
            _webRefreshing.value = null
        }
    }

    /** The refreshing page started (true) and then finished (false) loading. */
    private fun onRefreshLoading(loading: Boolean) {
        if (loading) {
            refreshSawLoading = true
        } else if (refreshSawLoading) {
            refreshTimeoutJob?.cancel()
            _webRefreshing.value = null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Subscribe buttons on YouTube pages (local subscriptions)
    // ---------------------------------------------------------------------------------------

    /** Handles (/@name) looked up in the background this session. */
    private val handleLookups: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Called on a page's JavaScript bridge thread: is this channel subscribed in the app? */
    fun isChannelSubscribed(id: String, path: String): Boolean {
        val key = path.lowercase()
        val channelId = id.takeIf { CHANNEL_ID_PATTERN.matches(it) } ?: channelHandles[key]
        if (channelId == null) {
            // A handle the app has not seen: look it up once; the page's buttons update then.
            if (key.startsWith("/@") && subscribedSnapshot.isNotEmpty() && handleLookups.add(key)) {
                viewModelScope.launch(Dispatchers.IO) {
                    val resolved = try {
                        NewPipeSupport.resolveChannelId("https://www.youtube.com$path")
                    } catch (_: Exception) {
                        null
                    }
                    if (resolved != null) {
                        channelHandles[key] = resolved
                        saveChannelHandles()
                        if (resolved in subscribedSnapshot) _subscriptionsChanged.tryEmit(Unit)
                    }
                }
            }
            return false
        }
        return channelId in subscribedSnapshot
    }

    /** YouTube's Subscribe / Subscribed button was tapped on a page. */
    fun onPageSubscribeTap(channel: PageChannel) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = channel.path.lowercase()
            var id = channel.id.takeIf { CHANNEL_ID_PATTERN.matches(it) } ?: channelHandles[key]
            if (id == null && channel.path.startsWith("/@")) {
                id = try {
                    NewPipeSupport.resolveChannelId("https://www.youtube.com" + channel.path)
                } catch (e: Exception) {
                    android.util.Log.w("StreamShield", "Channel lookup failed for ${channel.path}", e)
                    null
                }
            }
            if (id == null) {
                showBanner("Could not identify this channel. Try Save → Subscribe on one of its videos.")
                return@launch
            }
            if (channel.path.startsWith("/@") && channelHandles[key] != id) {
                channelHandles[key] = id
                saveChannelHandles()
            }
            val name = channel.name.trim()
            if (library.isSubscribed(id)) {
                val known = library.subscriptionName(id).orEmpty().ifBlank { name }
                _unsubscribeRequest.value = UnsubscribeRequest(id, known.ifBlank { "this channel" })
            } else if (library.subscribe(id, name)) {
                // The channel's feed also brings its proper name (the page may not have shown it).
                refresher.refreshOne(id, name)
                val known = library.subscriptionName(id).orEmpty().ifBlank { name }
                showBanner("Subscribed to ${known.ifBlank { "this channel" }}")
            }
        }
    }

    fun confirmPageUnsubscribe() {
        val request = _unsubscribeRequest.value ?: return
        _unsubscribeRequest.value = null
        setSubscribed(request.channelId, request.name, subscribe = false)
    }

    fun dismissPageUnsubscribe() {
        _unsubscribeRequest.value = null
        _subscriptionsChanged.tryEmit(Unit)
    }

    private fun channelHandlesFile() = File(getApplication<Application>().filesDir, "channel_handles.json")

    private fun loadChannelHandles() {
        try {
            val file = channelHandlesFile()
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            o.keys().forEach { k -> o.optString(k).takeIf { CHANNEL_ID_PATTERN.matches(it) }?.let { channelHandles[k] = it } }
        } catch (_: Exception) {
        }
    }

    private fun saveChannelHandles() {
        try {
            val o = JSONObject()
            channelHandles.forEach { (k, v) -> o.put(k, v) }
            channelHandlesFile().writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // Library actions for the current video
    // ---------------------------------------------------------------------------------------

    fun toggleSubscribeCurrent() {
        val a = currentVideoActions.value
        if (a.channelId.isBlank()) {
            showBanner("Channel not known yet, try again in a moment")
            return
        }
        setSubscribed(a.channelId, a.channelName, !a.subscribed)
    }

    fun setSubscribed(channelId: String, name: String, subscribe: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (subscribe) {
                if (library.subscribe(channelId, name)) {
                    showBanner("Subscribed to ${name.ifBlank { "channel" }}")
                    refresher.refreshOne(channelId, name)
                }
            } else {
                library.unsubscribe(channelId)
                showBanner("Unsubscribed")
            }
        }
    }

    fun toggleLikeCurrent() = toggleBuiltIn(BuiltInPlaylist.LIKED, currentVideoActions.value.liked, "Liked", "Removed from liked")

    fun toggleWatchLaterCurrent() =
        toggleBuiltIn(BuiltInPlaylist.WATCH_LATER, currentVideoActions.value.inWatchLater, "Saved to Watch later", "Removed from Watch later")

    private fun toggleBuiltIn(builtIn: BuiltInPlaylist, currentlyIn: Boolean, added: String, removed: String) {
        val video = _videoState.value
        if (video.videoId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            rememberCurrent(video)
            val nowIn = library.toggleBuiltIn(builtIn, video.videoId, currentlyIn)
            showBanner(if (nowIn) added else removed)
        }
    }

    fun toggleCurrentInPlaylist(playlistId: Long) {
        val video = _videoState.value
        if (video.videoId.isBlank()) return
        val inList = playlistId in currentVideoActions.value.playlistIds
        viewModelScope.launch(Dispatchers.IO) {
            rememberCurrent(video)
            if (inList) library.removeFromPlaylist(playlistId, video.videoId) else library.addToPlaylist(playlistId, video.videoId)
        }
    }

    fun createPlaylistWithCurrent(name: String) {
        val video = _videoState.value
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val kind = if (video.isMusic) PlaylistKind.MUSIC else PlaylistKind.VIDEO
            val id = library.createPlaylist(name, kind)
            if (video.videoId.isNotBlank()) {
                rememberCurrent(video)
                library.addToPlaylist(id, video.videoId)
            }
            showBanner("Saved to $name")
        }
    }

    fun toggleNotInterestedCurrent() {
        val a = currentVideoActions.value
        if (a.videoId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            library.setFeedback(a.videoId, FeedbackType.NOT_INTERESTED, !a.notInterested)
            showBanner(if (a.notInterested) "Feedback removed" else "Got it: fewer videos like this")
        }
    }

    private suspend fun rememberCurrent(video: WebVideoState) {
        library.rememberVideo(
            video.videoId, video.title, video.channelId, video.channel, video.bestDurationSec, video.viewCount, video.isMusic
        )
    }

    // ---------------------------------------------------------------------------------------
    // Playlists (Library tab)
    // ---------------------------------------------------------------------------------------

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { library.createPlaylist(name) }
    }

    fun renamePlaylist(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { library.renamePlaylist(id, name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { library.deletePlaylist(id) }
        _libraryRoute.value = LibraryRoute.Root
    }

    fun removeFromPlaylist(playlistId: Long, videoId: String) {
        viewModelScope.launch(Dispatchers.IO) { library.removeFromPlaylist(playlistId, videoId) }
    }

    fun movePlaylistItem(items: List<PlaylistVideo>, index: Int, delta: Int) {
        viewModelScope.launch(Dispatchers.IO) { library.moveInPlaylist(items, index, delta) }
    }

    // ---------------------------------------------------------------------------------------
    // Subscriptions
    // ---------------------------------------------------------------------------------------

    fun refreshFeeds() {
        if (_feedRefresh.value.running) return
        viewModelScope.launch(Dispatchers.IO) { refreshFeedsNow() }
    }

    private suspend fun refreshFeedsNow() {
        if (_feedRefresh.value.running) return
        _feedRefresh.value = FeedRefreshState(running = true)
        val result = try {
            refresher.refreshAll()
        } catch (e: Exception) {
            null
        }
        _feedRefresh.value = FeedRefreshState(
            running = false,
            lastMessage = when {
                result == null -> "Refresh failed"
                result.failed > 0 -> "${result.failed} channel(s) could not be refreshed (will retry)"
                else -> null
            }
        )
    }

    fun unsubscribe(channelId: String) = setSubscribed(channelId, "", subscribe = false)

    /** Imports a Google Takeout subscriptions.csv chosen with the system file picker. */
    fun importTakeoutSubscriptions(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
            val channels = text?.let { TakeoutSubscriptionsParser.parse(it) }.orEmpty()
            if (channels.isEmpty()) {
                showBanner("No channels found in that file")
                return@launch
            }
            var added = 0
            channels.forEach { if (library.subscribe(it.channelId, it.name)) added++ }
            showBanner("Imported $added new subscription(s)")
            refreshFeeds()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------------------------

    /**
     * Download button anywhere: uses the remembered quality for this kind (music / video) if
     * there is one, otherwise opens the quality sheet (songs pre-select audio only).
     */
    fun requestDownload(target: DownloadTarget) {
        if (target.videoId.isBlank()) return
        val prefs = preferences.value
        val remembered = if (target.isMusic) prefs.downloadMusicQuality else prefs.downloadVideoQuality
        if (remembered >= 0) {
            enqueueDownload(target, remembered)
            return
        }
        _downloadSheet.value = DownloadSheetState(target)
        viewModelScope.launch(Dispatchers.IO) {
            val next = try {
                val (options, meta) = DownloadCatalog.qualityOptions(target.videoId)
                val known = target.copy(
                    title = target.title.ifBlank { meta.title },
                    channelName = target.channelName.ifBlank { meta.channelName },
                    durationSec = if (target.durationSec > 0) target.durationSec else meta.durationSec
                )
                if (options.isEmpty()) {
                    DownloadSheetState(known, loading = false, error = "This video has no downloadable formats.")
                } else {
                    DownloadSheetState(known, loading = false, options = options)
                }
            } catch (e: Exception) {
                DownloadSheetState(target, loading = false, error = "Could not load download options. " + DownloadErrors.describe(e))
            }
            if (_downloadSheet.value?.target?.videoId == target.videoId) _downloadSheet.value = next
        }
    }

    /** Download button in the player: the video or song that is open. */
    fun downloadCurrent() {
        val v = _videoState.value
        if (v.videoId.isBlank()) return
        requestDownload(DownloadTarget(v.videoId, v.title, v.channel, v.channelId, v.bestDurationSec, v.isMusic))
    }

    fun confirmDownload(option: QualityOption, remember: Boolean) {
        val sheet = _downloadSheet.value ?: return
        _downloadSheet.value = null
        if (remember) viewModelScope.launch { prefsRepo.setDownloadQuality(sheet.target.isMusic, option.height) }
        enqueueDownload(sheet.target, option.height)
    }

    fun dismissDownloadSheet() {
        _downloadSheet.value = null
    }

    private fun enqueueDownload(target: DownloadTarget, height: Int) {
        val audioOnly = height == 0
        val id = OfflineItemEntity.idFor(target.videoId, audioOnly)
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val existing = offlineDao.getItem(id)
            if (existing != null && !existing.isSmart && existing.isDone && OfflineStorage.exists(app, existing.location)) {
                showBanner("Already downloaded")
                return@launch
            }
            // A smart download of the same song becomes a regular download (saved to Music).
            if (existing != null && existing.isSmart) OfflineDownloader.deleteFiles(app, existing)
            offlineDao.upsertItem(
                OfflineItemEntity(
                    id = id,
                    videoId = target.videoId,
                    title = target.title,
                    channelId = target.channelId,
                    channelName = target.channelName,
                    durationSec = target.durationSec,
                    isMusic = target.isMusic,
                    audioOnly = audioOnly,
                    targetHeight = if (audioOnly) 0 else height,
                    qualityLabel = "",
                    origin = OfflineOrigin.MANUAL.name,
                    status = OfflineStatus.QUEUED.name,
                    totalBytes = 0L,
                    downloadedBytes = 0L,
                    location = "",
                    mimeType = "",
                    error = "",
                    createdAt = System.currentTimeMillis(),
                    completedAt = 0L,
                    smartSection = "",
                    smartRank = 0
                )
            )
            DownloadWorker.enqueue(app, id, restart = existing != null && existing.status == OfflineStatus.FAILED.name)
            showBanner(if (audioOnly) "Downloading audio… (Library → Downloads)" else "Downloading ${height}p… (Library → Downloads)")
        }
    }

    fun retryDownload(item: OfflineItemEntity) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            offlineDao.updateStatus(item.id, OfflineStatus.QUEUED.name, "")
            if (item.isSmart) SmartDownloads.refreshNow(app) else DownloadWorker.enqueue(app, item.id, restart = true)
        }
    }

    /** Deletes a download. A removed smart download is not downloaded again for 60 days. */
    fun deleteDownload(item: OfflineItemEntity) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            if (!item.isSmart) DownloadWorker.cancel(app, item.id)
            OfflineDownloader.deleteFiles(app, item)
            offlineDao.deleteItem(item.id)
            if (offlineDao.allItems().none { it.videoId == item.videoId }) OfflineStorage.deleteThumbnail(app, item.videoId)
            if (item.isSmart) offlineDao.insertRemoved(SmartRemovedEntity(item.videoId, System.currentTimeMillis()))
            showBanner("Download deleted")
        }
    }

    /** Writes where the current video was left (before another player takes over). */
    private fun flushProgress() {
        val current = _videoState.value
        if (!preferences.value.historyPaused && progressVideoId.isNotBlank() && current.videoId == progressVideoId) {
            writeProgress(current, newSession = false)
        }
        progressVideoId = ""
    }

    /** Plays a downloaded item, with the other finished items of the same list as the queue. */
    fun playDownload(item: OfflineItemEntity) {
        if (!item.isDone) return
        flushProgress()
        val list = offlineItems.value.filter { it.isDone && it.isSmart == item.isSmart }
        val index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        _showSettings.value = false
        _playerExpanded.value = false
        _miniPlayerDismissedFor.value = ""
        _playbackSource.value = PlaybackSource.OFFLINE
        _offlineExpanded.value = true
        _videoState.value = WebVideoState()
        _offlinePlayRequests.tryEmit(OfflinePlayRequest(list, index))
    }

    fun onOfflineState(state: WebVideoState) {
        if (_playbackSource.value == PlaybackSource.OFFLINE) applyVideoState(state)
    }

    fun expandOffline() {
        if (_playbackSource.value == PlaybackSource.OFFLINE) _offlineExpanded.value = true
    }

    fun collapseOffline() {
        _offlineExpanded.value = false
    }

    /** Opening anything on YouTube hands the media controls back to the web player. */
    private fun switchToWebPlayback() {
        if (_playbackSource.value != PlaybackSource.OFFLINE) return
        flushProgress()
        _playbackSource.value = PlaybackSource.WEB
        _offlineExpanded.value = false
        _videoState.value = WebVideoState()
    }

    fun setSmartSize(mb: Int) {
        viewModelScope.launch {
            prefsRepo.setSmartSize(mb)
            SmartDownloads.onSizeChanged(getApplication(), mb)
            showBanner(if (mb <= 0) "Smart downloads off" else "Smart downloads: next refresh on Wi-Fi while charging")
        }
    }

    fun refreshSmartNow() {
        SmartDownloads.refreshNow(getApplication())
        showBanner("Refreshing smart downloads (on Wi-Fi)")
    }

    fun resetDownloadQuality(music: Boolean) {
        viewModelScope.launch { prefsRepo.setDownloadQuality(music, -1) }
    }

    // ---------------------------------------------------------------------------------------
    // Backup & restore (no account: a JSON file the user keeps, plus optional daily auto-backup)
    // ---------------------------------------------------------------------------------------

    fun exportBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val json = backup.export()
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } != null
            } catch (_: Exception) {
                false
            }
            showBanner(if (ok) "Backup saved" else "Could not save the backup")
        }
    }

    fun prepareImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = readText(uri)
            if (text == null) {
                showBanner("Could not read that file")
                return@launch
            }
            try {
                _pendingImport.value = PendingImport(text, backup.summarize(text))
            } catch (e: IllegalArgumentException) {
                showBanner(e.message ?: "Not a backup file")
            }
        }
    }

    fun confirmImport(mode: ImportMode) {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                backup.import(pending.text, mode)
                showBanner(if (mode == ImportMode.MERGE) "Backup merged" else "Backup restored")
                refreshFeeds()
            } catch (e: Exception) {
                showBanner("Import failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    /** Folder picked for automatic backups: keep access across restarts and back up right away. */
    fun setAutoBackupFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            prefsRepo.setAutoBackup(true, uri.toString())
            AutoBackupWorker.schedule(getApplication(), true)
            backupNow()
        }
    }

    fun toggleAutoBackup(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            prefsRepo.setAutoBackup(enabled, null)
            AutoBackupWorker.schedule(getApplication(), enabled && preferences.value.autoBackupFolder.isNotBlank())
        }
    }

    fun backupNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = prefsRepo.preferencesFlow.first().autoBackupFolder
            if (folder.isBlank()) {
                showBanner("Choose a backup folder first")
                return@launch
            }
            val ok = BackupFolder.writeAutoBackup(getApplication(), folder, backup.export())
            if (ok) prefsRepo.setLastAutoBackup(System.currentTimeMillis())
            showBanner(if (ok) "Backed up to ${BackupFolder.displayName(folder)}" else "Backup failed (folder no longer accessible?)")
        }
    }

    fun importTakeoutHistory(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = readText(uri) ?: return@launch showBanner("Could not read that file")
            try {
                val count = backup.importTakeoutHistory(text)
                showBanner("Imported $count videos from your YouTube history")
            } catch (e: IllegalArgumentException) {
                showBanner(e.message ?: "Not a watch-history.json file")
            }
        }
    }

    private fun readText(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (_: Exception) {
        null
    }

    override fun onCleared() {
        // Best effort: persist counts that have not been flushed yet.
        val snapshot = pendingCounts.value
        if (snapshot.ads > 0L || snapshot.requests > 0L) {
            kotlinx.coroutines.runBlocking { prefsRepo.addBlockedCounts(snapshot.ads, snapshot.requests) }
        }
        super.onCleared()
    }
}
