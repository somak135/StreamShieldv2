package com.example

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon as GraphicsIcon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import com.example.ui.PullSource
import com.example.ui.components.PullToRefreshContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.browser.BrowserController
import com.example.browser.BrowsePageController
import com.example.data.OfflineItemEntity
import com.example.data.WebVideoState
import com.example.data.OfflineStatus
import com.example.offline.OfflinePlayerController
import com.example.ui.PlaybackSource
import com.example.ui.components.DownloadQualitySheet
import com.example.ui.components.NowPlayingDownloadStrip
import com.example.ui.screens.BrowsePageScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.OfflinePlayerScreen
import androidx.compose.foundation.layout.Column
import com.example.data.BackupManager
import com.example.data.ImportMode
import com.example.data.formatAgo
import com.example.data.youTubeLinkOrNull
import com.example.service.BackgroundPlaybackService
import com.example.service.PlaybackCommand
import com.example.service.PlaybackCommands
import com.example.ui.AppTab
import com.example.ui.HOME_URL
import com.example.data.isYouTubeVideoPage
import com.example.ui.LibraryRoute
import com.example.ui.MUSIC_HOME_URL
import com.example.ui.StreamShieldViewModel
import com.example.ui.components.AppNavigationBar
import com.example.ui.components.MiniPlayer
import com.example.ui.components.TabsScaffold
import com.example.ui.components.VideoActionsSheet
import com.example.ui.components.PlaybackSpeedBottomSheet
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.MusicScreen
import com.example.ui.screens.PipStats
import com.example.ui.screens.SubscriptionsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: StreamShieldViewModel by viewModels()
    private lateinit var browser: BrowserController
    private lateinit var browsePage: BrowsePageController
    private lateinit var offline: OfflinePlayerController
    private var pipJustClosed = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Play/pause button inside the picture-in-picture window. */
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_TOGGLE -> PlaybackCommands.send(PlaybackCommand.TOGGLE)
                ACTION_PIP_NEXT -> PlaybackCommands.send(PlaybackCommand.NEXT)
                ACTION_PIP_PREVIOUS -> PlaybackCommands.send(PlaybackCommand.PREVIOUS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        browser = BrowserController(this, viewModel)
        browsePage = BrowsePageController(this, viewModel)
        offline = OfflinePlayerController(this, viewModel)

        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter().apply {
                addAction(ACTION_PIP_TOGGLE)
                addAction(ACTION_PIP_NEXT)
                addAction(ACTION_PIP_PREVIOUS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        observePlaybackForWindow()
        requestNotificationPermissionOnce()
        // Native screens ask for pages through the view model; the WebView lives here.
        lifecycleScope.launch { viewModel.openUrlRequests.collect { url -> browser.load(url) } }
        lifecycleScope.launch { viewModel.musicPageRequests.collect { url -> browser.loadKeepingMusic(url) } }
        lifecycleScope.launch { viewModel.browseRequests.collect { url -> browsePage.load(url) } }
        // Pull-to-refresh on a web page.
        lifecycleScope.launch {
            viewModel.reloadRequests.collect { source ->
                if (source == PullSource.PLAYER) browser.refreshPage() else browsePage.reload()
            }
        }
        // A download starts playing: the web player stops.
        lifecycleScope.launch {
            viewModel.playbackSource.collect { source -> if (source == PlaybackSource.OFFLINE) browser.pause() }
        }
        // After the collectors above: a shared link is loaded through them.
        handleIncomingIntent(intent)

        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val darkTheme = preferences.darkMode

            LaunchedEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            MyApplicationTheme(darkTheme = darkTheme, amoledBlack = preferences.amoledBlackMode) {
                StreamShieldApp(
                    viewModel = viewModel,
                    browser = browser,
                    browsePage = browsePage,
                    offline = offline,
                    onEnterPip = { enterPip() },
                    onExit = { exitToBackground() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /** YouTube links opened with the app, or text shared to it ("Share → StreamShield"). */
    private fun handleIncomingIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        val link = youTubeLinkOrNull(text)
        if (link == null) {
            if (intent?.action == Intent.ACTION_SEND) viewModel.showBanner("No YouTube link in what was shared")
            return
        }
        viewModel.openLink(link)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppVisible(true)
        browsePage.onAppShown()
    }

    override fun onResume() {
        super.onResume()
        pipJustClosed = false
    }

    override fun onStop() {
        super.onStop()
        viewModel.setAppVisible(false)
        browsePage.onAppHidden()
        viewModel.flushCountsNow()
        if (pipJustClosed) {
            // The PiP window was dismissed (expanding it resumes instead of stopping): stop watching.
            pipJustClosed = false
            PlaybackCommands.send(PlaybackCommand.PAUSE)
        } else {
            browser.onActivityStopped()
            if (!viewModel.preferences.value.backgroundPlayEnabled && viewModel.playbackSource.value == PlaybackSource.OFFLINE) {
                offline.player.pause()
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(pipActionReceiver)
        browser.destroy()
        browsePage.destroy()
        offline.release()
        // The WebView is gone, so leftover media controls would control nothing.
        if (isFinishing) BackgroundPlaybackService.stop(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Picture-in-picture
    // ---------------------------------------------------------------------------------------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP on its own via setAutoEnterEnabled (smoother animation).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (viewModel.preferences.value.autoPipOnLeave && showsVideo(viewModel.videoState.value)) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setPipMode(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) pipJustClosed = true
        if (isInPictureInPictureMode) {
            viewModel.closeSettings()
            viewModel.dismissSpeedSheet()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            viewModel.showBanner("Picture-in-picture needs Android 8.0 or newer")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            viewModel.showBanner("This device does not support picture-in-picture")
            return
        }
        viewModel.closeSettings()
        // Switch the page to its PiP layout first so the window does not show a frame of app UI.
        viewModel.setPipMode(true)
        val entered = try {
            enterPictureInPictureMode(buildPipParams())
        } catch (_: Exception) {
            false
        }
        if (!entered) {
            viewModel.setPipMode(false)
            viewModel.showBanner("Picture-in-picture is turned off for this app in system settings")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val video = viewModel.videoState.value
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio(video.videoWidth, video.videoHeight))
            .setActions(pipActions(video.isPlaying))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val prefs = viewModel.preferences.value
            builder.setAutoEnterEnabled(prefs.autoPipOnLeave && showsVideo(video))
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Previous, play/pause, next (as many as the device allows; play/pause always). */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(playing: Boolean): List<RemoteAction> {
        val max = try { maxNumPictureInPictureActions } catch (_: Exception) { 1 }
        if (max < 3) return listOf(playPauseAction(playing))
        return listOf(
            simplePipAction(ACTION_PIP_PREVIOUS, 201, "Previous", android.R.drawable.ic_media_previous),
            playPauseAction(playing),
            simplePipAction(ACTION_PIP_NEXT, 202, "Next", android.R.drawable.ic_media_next)
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun simplePipAction(action: String, requestCode: Int, label: String, icon: Int): RemoteAction {
        val pending = PendingIntent.getBroadcast(
            this, requestCode, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(GraphicsIcon.createWithResource(this, icon), label, label, pending)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun playPauseAction(playing: Boolean): RemoteAction {
        val intent = Intent(ACTION_PIP_TOGGLE).setPackage(packageName)
        val pending = PendingIntent.getBroadcast(
            this, 200, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = if (playing) "Pause" else "Play"
        val icon = GraphicsIcon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        return RemoteAction(icon, label, label, pending)
    }

    /** Keeps PiP params (aspect ratio, play/pause, auto-enter) and keep-screen-on in sync. */
    private fun observePlaybackForWindow() {
        lifecycleScope.launch {
            combine(viewModel.videoState, viewModel.preferences) { video, prefs ->
                PipInputs(showsVideo(video), video.videoWidth, video.videoHeight, prefs.autoPipOnLeave)
            }.distinctUntilChanged().collect { inputs ->
                if (inputs.playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                ) {
                    try {
                        setPictureInPictureParams(buildPipParams())
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /** "playing" here means a video is playing: songs (YouTube Music, downloaded audio) keep
     * neither the screen on nor open picture-in-picture. */
    private data class PipInputs(val playing: Boolean, val width: Int, val height: Int, val autoPip: Boolean)

    /** A video (not a song) is playing: keep the screen on and allow auto picture-in-picture. */
    private fun showsVideo(video: WebVideoState): Boolean =
        video.isPlaying && video.videoWidth > 0 && !video.isMusicPage

    // ---------------------------------------------------------------------------------------

    /** Back on the root page: keep playing in the background if that is on, otherwise close. */
    private fun exitToBackground() {
        val keepPlaying = viewModel.preferences.value.backgroundPlayEnabled && viewModel.videoState.value.isPlaying
        if (keepPlaying) moveTaskToBack(true) else finish()
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("streamshield_ui", MODE_PRIVATE)
        if (!granted && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val ACTION_PIP_TOGGLE = "com.example.action.PIP_TOGGLE"
        private const val ACTION_PIP_NEXT = "com.example.action.PIP_NEXT"
        private const val ACTION_PIP_PREVIOUS = "com.example.action.PIP_PREVIOUS"

        /** Android only accepts PiP aspect ratios between 1:2.39 and 2.39:1. */
        fun pipAspectRatio(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) return Rational(16, 9)
            val ratio = width.toFloat() / height.toFloat()
            return when {
                ratio > 2.39f -> Rational(239, 100)
                ratio < 1f / 2.39f -> Rational(100, 239)
                else -> Rational(width, height)
            }
        }
    }
}

@Composable
fun StreamShieldApp(
    viewModel: StreamShieldViewModel,
    browser: BrowserController,
    browsePage: BrowsePageController,
    offline: OfflinePlayerController,
    onEnterPip: () -> Unit,
    onExit: () -> Unit
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    val blockCounts by viewModel.blockCounts.collectAsStateWithLifecycle()
    val lastStart by viewModel.lastStart.collectAsStateWithLifecycle()
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val recentBlocked by viewModel.recentBlocked.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
    val showSpeedSheet by viewModel.showSpeedSheet.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val isInPipMode by viewModel.isInPipMode.collectAsStateWithLifecycle()
    val playerExpanded by viewModel.playerExpanded.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val libraryRoute by viewModel.libraryRoute.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val feedRefresh by viewModel.feedRefresh.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val showActionsSheet by viewModel.showActionsSheet.collectAsStateWithLifecycle()
    val currentActions by viewModel.currentVideoActions.collectAsStateWithLifecycle()
    val miniPlayerDismissedFor by viewModel.miniPlayerDismissedFor.collectAsStateWithLifecycle()
    val webPageUrl by viewModel.webPageUrl.collectAsStateWithLifecycle()
    val browseTab by viewModel.browseTab.collectAsStateWithLifecycle()
    val browsePageState by viewModel.browsePage.collectAsStateWithLifecycle()
    val playbackSource by viewModel.playbackSource.collectAsStateWithLifecycle()
    val offlineExpanded by viewModel.offlineExpanded.collectAsStateWithLifecycle()
    val offlineItems by viewModel.offlineItems.collectAsStateWithLifecycle()
    val downloadSheet by viewModel.downloadSheet.collectAsStateWithLifecycle()
    val forYouState by viewModel.forYouState.collectAsStateWithLifecycle()
    val homeRefreshing by viewModel.homeRefreshing.collectAsStateWithLifecycle()
    val musicForYouState by viewModel.musicForYouState.collectAsStateWithLifecycle()
    val musicRefreshing by viewModel.musicRefreshing.collectAsStateWithLifecycle()
    val musicPageShown by viewModel.musicPageShown.collectAsStateWithLifecycle()
    val ytmHome by viewModel.ytmHome.collectAsStateWithLifecycle()
    val webPull by viewModel.webPull.collectAsStateWithLifecycle()
    val webRefreshing by viewModel.webRefreshing.collectAsStateWithLifecycle()
    val pendingVideo by viewModel.pendingVideo.collectAsStateWithLifecycle()
    val unsubscribeRequest by viewModel.unsubscribeRequest.collectAsStateWithLifecycle()
    val tapFixes by viewModel.tapFixes.collectAsStateWithLifecycle()
    val autoplayDiagnostics by viewModel.autoplayDiagnostics.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTakeoutSubscriptions(uri)
    }
    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.prepareImport(uri)
    }
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setAutoBackupFolder(uri)
    }
    val takeoutHistoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTakeoutHistory(uri)
    }

    // Lowest-priority back handler: the player page and settings register their own after this one.
    BackHandler(enabled = !isInPipMode) {
        if (!viewModel.navigateBackInTabs()) onExit()
    }

    val shareCurrent: () -> Unit = {
        val id = currentActions.videoId
        if (id.isNotBlank()) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$id")
            context.startActivity(Intent.createChooser(send, null))
        }
    }

    val offlineActive = playbackSource == PlaybackSource.OFFLINE

    // ---- player slide (like the YouTube app's player sheet) ----
    // 0 = in place, 1 = one player height below the screen. The player page stays composed (and
    // its WebView attached) at all times; while a video is open it is drawn above the tabs.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val playerSlide = remember { Animatable(0f) }
    var playerOnTop by remember { mutableStateOf(false) }
    var playerHeightPx by remember { mutableIntStateOf(1) }
    val slideShowing by remember { derivedStateOf { playerSlide.value > 0.001f } }

    // Like the YouTube app: a video fills the screen with the player controls; Back or
    // Minimize shrinks it to the mini-player over whatever was open (tab or browse page).
    val videoPage = playerExpanded && isYouTubeVideoPage(webPageUrl)

    LaunchedEffect(videoPage) {
        if (videoPage) {
            // Opening: slide up from the bottom (from where the mini-player is).
            if (!playerOnTop) playerSlide.snapTo(1f)
            playerOnTop = true
            playerSlide.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
        } else if (playerOnTop) {
            // Minimizing: slide down, then go back under the tabs.
            playerSlide.animateTo(1f, tween(240, easing = FastOutLinearInEasing))
            playerOnTop = false
            playerSlide.snapTo(0f)
        }
    }

    // Pull-down on the video page: the player follows the finger; released short of the
    // threshold it slides back (the view model minimizes when it is far enough).
    val minimizeDistance = webPull?.takeIf { it.source == PullSource.PLAYER && it.mode == "minimize" }?.distance
    LaunchedEffect(minimizeDistance) {
        if (minimizeDistance != null) {
            if (videoPage) playerSlide.snapTo((minimizeDistance * density.density / playerHeightPx).coerceIn(0f, 0.9f))
        } else if (videoPage && playerSlide.value > 0f) {
            playerSlide.animateTo(0f, tween(200))
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isInPipMode) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        ) {
            // Music tab: the YouTube Music page itself, with the app's bottom navigation under it
            // (otherwise the tab shows the app's music home).
            val musicMode = currentTab == AppTab.MUSIC && !playerExpanded && musicPageShown &&
                browserState.url.startsWith(MUSIC_HOME_URL)
            val tabsUnderPage = musicMode || (playerExpanded && !videoPage)
            val musicPlaying = musicMode && videoState.videoId.isNotBlank() && videoState.isMusicPage

            BrowserScreen(
                controller = browser,
                browserState = browserState,
                preferences = preferences,
                isInPipMode = isInPipMode,
                backEnabled = playerExpanded || musicMode,
                actionsEnabled = videoState.videoId.isNotBlank(),
                pendingVideo = if (videoPage) pendingVideo else null,
                pull = webPull?.takeIf { it.source == PullSource.PLAYER },
                refreshing = webRefreshing == PullSource.PLAYER,
                modifier = Modifier
                    .zIndex(if (playerOnTop && !isInPipMode) 2f else 0f)
                    .onSizeChanged { playerHeightPx = it.height.coerceAtLeast(1) }
                    .graphicsLayer { translationY = if (isInPipMode) 0f else playerSlide.value * playerHeightPx },
                bottomBar = if (tabsUnderPage) {
                    {
                        Column {
                            if (musicPlaying) {
                                NowPlayingDownloadStrip(title = videoState.title, onDownload = viewModel::downloadCurrent)
                            }
                            // A download playing (or paused) keeps its mini-player in the Music tab.
                            if (offlineActive && videoState.videoId.isNotBlank() && videoState.videoId != miniPlayerDismissedFor) {
                                MiniPlayer(
                                    video = videoState,
                                    onExpand = viewModel::expandOffline,
                                    onTogglePlay = { PlaybackCommands.send(PlaybackCommand.TOGGLE) },
                                    onNext = { PlaybackCommands.send(PlaybackCommand.NEXT) },
                                    onClose = {
                                        PlaybackCommands.send(PlaybackCommand.PAUSE)
                                        viewModel.dismissMiniPlayer()
                                    }
                                )
                            }
                            AppNavigationBar(currentTab = currentTab, onSelectTab = viewModel::selectTab)
                        }
                    }
                } else null,
                onBack = {
                    if (musicMode) {
                        // Stay inside YouTube Music; leaving it goes back to the music home.
                        if (browser.previousUrlInSession()?.startsWith(MUSIC_HOME_URL) == true) browser.goBack()
                        else viewModel.showMusicHome()
                    } else {
                        viewModel.collapsePlayer()
                    }
                },
                onMinimize = viewModel::collapsePlayer,
                onOpenActions = viewModel::openActionsSheet,
                onOpenSpeedSheet = viewModel::openSpeedSheet,
                onEnterPip = onEnterPip,
                onOpenSettings = viewModel::openSettings,
                onDownload = viewModel::downloadCurrent
            )

            // Back on a video page (Android 14+ shows it as you swipe): the player follows the
            // gesture down, then minimizes. Registered after the player's own handler, so it wins.
            PredictiveBackHandler(enabled = videoPage && !isInPipMode) { progress ->
                try {
                    progress.collect { event -> playerSlide.snapTo(event.progress * 0.3f) }
                    viewModel.collapsePlayer()
                } catch (e: CancellationException) {
                    scope.launch { playerSlide.animateTo(0f, tween(200)) }
                    throw e
                }
            }

            // The tabs: hidden behind an open video (kept until the player has slid over them,
            // and while it is pulled down) and in the Music tab, where YouTube Music's page shows.
            val showTabs = !isInPipMode && !musicMode &&
                (!playerExpanded || (videoPage && (!playerOnTop || slideShowing)))
            AnimatedVisibility(
                visible = showTabs,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.zIndex(1f)
            ) {
                val showMini = videoState.videoId.isNotBlank() && videoState.videoId != miniPlayerDismissedFor &&
                    !(offlineActive && offlineExpanded)
                TabsScaffold(
                    currentTab = currentTab,
                    onSelectTab = viewModel::selectTab,
                    miniPlayer = if (showMini) {
                        {
                            MiniPlayer(
                                video = videoState,
                                onExpand = {
                                    when {
                                        offlineActive -> viewModel.expandOffline()
                                        videoState.isMusicPage -> viewModel.showMusicPage()
                                        else -> viewModel.expandPlayer()
                                    }
                                },
                                onTogglePlay = { PlaybackCommands.send(PlaybackCommand.TOGGLE) },
                                onNext = { PlaybackCommands.send(PlaybackCommand.NEXT) },
                                onClose = {
                                    PlaybackCommands.send(PlaybackCommand.PAUSE)
                                    viewModel.dismissMiniPlayer()
                                }
                            )
                        }
                    } else null
                ) { innerModifier ->
                    val screen = when {
                        browseTab == currentTab -> TabScreen.Browse(currentTab)
                        currentTab == AppTab.LIBRARY && libraryRoute == LibraryRoute.Downloads -> TabScreen.Downloads
                        currentTab == AppTab.LIBRARY -> TabScreen.Tab(AppTab.LIBRARY, libraryRoute)
                        else -> TabScreen.Tab(currentTab, null)
                    }
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val into = targetState
                            val from = initialState
                            val sameTab = into.tab == from.tab
                            when {
                                // Deeper page (browse page, Library sub-screen): slides in from the right.
                                sameTab && into.depth > from.depth || into is TabScreen.Browse && from !is TabScreen.Browse ->
                                    (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                                // Back out of it: it slides away to the right, on top.
                                sameTab && into.depth < from.depth || from is TabScreen.Browse && into !is TabScreen.Browse ->
                                    (fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(200))))
                                        .apply { targetContentZIndex = -1f }
                                // Another tab: a quick cross-fade.
                                else -> fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                            }
                        },
                        label = "tabContent",
                        modifier = innerModifier
                    ) { shown ->
                        val inFront = transition.targetState == EnterExitState.Visible
                        when (shown) {
                            is TabScreen.Browse -> BrowsePageScreen(
                                controller = browsePage,
                                state = browsePageState,
                                onClose = viewModel::closeBrowse,
                                modifier = Modifier.fillMaxSize(),
                                backEnabled = inFront && showTabs && !playerExpanded,
                                pull = webPull?.takeIf { it.source == PullSource.BROWSE },
                                refreshing = webRefreshing == PullSource.BROWSE
                            )
                            TabScreen.Downloads -> LocalRefresh(modifier = Modifier.fillMaxSize()) {
                                DownloadsScreen(
                                    items = offlineItems,
                                    smartSizeMb = preferences.smartSizeMb,
                                    smartSummary = preferences.smartSummary,
                                    lastSmartRefreshAt = preferences.lastSmartRefreshAt,
                                    onBack = { viewModel.openLibraryRoute(LibraryRoute.Root) },
                                    onPlay = viewModel::playDownload,
                                    onDelete = viewModel::deleteDownload,
                                    onRetry = viewModel::retryDownload,
                                    onRefreshSmart = viewModel::refreshSmartNow,
                                    onOpenSettings = viewModel::openSettings,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            is TabScreen.Tab -> when (shown.tab) {
                                AppTab.HOME -> PullToRefreshContainer(
                                    refreshing = homeRefreshing,
                                    onRefresh = viewModel::refreshHome,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    HomeScreen(
                                        forYou = forYouState.items,
                                        forYouRefreshing = forYouState.refreshing,
                                        forYouNote = forYouState.note,
                                        forYouGeneration = forYouState.generation,
                                        forYouExtending = forYouState.extending,
                                        forYouEnd = forYouState.endReached,
                                        feed = feed,
                                        feedRefreshing = feedRefresh.running,
                                        hasSubscriptions = subscriptions.isNotEmpty(),
                                        subscribedChannels = remember(subscriptions) { subscriptions.map { it.channelId }.toSet() },
                                        history = history,
                                        recentSearches = recentSearches,
                                        onSearch = viewModel::search,
                                        onDeleteSearch = viewModel::deleteSearch,
                                        onOpenVideo = viewModel::openVideo,
                                        onOpenChannel = { id -> viewModel.openBrowse("$HOME_URL/channel/$id") },
                                        onOpenSubscriptions = { viewModel.selectTab(AppTab.SUBSCRIPTIONS) },
                                        onDownload = viewModel::requestDownload,
                                        onNotInterested = viewModel::forYouNotInterested,
                                        onForYouOpened = viewModel::forYouOpened,
                                        onForYouSeen = viewModel::forYouSeen,
                                        onLoadMore = viewModel::forYouLoadMore,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                AppTab.MUSIC -> PullToRefreshContainer(
                                    refreshing = musicRefreshing,
                                    onRefresh = viewModel::refreshMusicHome,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    MusicScreen(
                                        history = history,
                                        forYou = musicForYouState.items,
                                        forYouRefreshing = musicForYouState.refreshing,
                                        forYouNote = musicForYouState.note,
                                        forYouGeneration = musicForYouState.generation,
                                        ytm = ytmHome,
                                        videoPlaying = videoState.isPlaying && !videoState.isMusicPage,
                                        onSearch = viewModel::searchMusic,
                                        onOpenYouTubeMusic = viewModel::openYouTubeMusic,
                                        onPlaySong = viewModel::playSong,
                                        onPlayRadio = viewModel::playRadio,
                                        onOpenArtist = viewModel::openMusicArtist,
                                        onOpenYtmItem = viewModel::openYtmItem,
                                        onSelectChip = viewModel::selectYtmChip,
                                        onLoadMoreYtm = viewModel::loadMoreYtm,
                                        onDownload = viewModel::requestDownload,
                                        onNotInterested = viewModel::musicNotInterested,
                                        onSeen = viewModel::musicSeen,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                AppTab.SUBSCRIPTIONS -> PullToRefreshContainer(
                                    refreshing = feedRefresh.running,
                                    onRefresh = viewModel::refreshFeeds,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    SubscriptionsScreen(
                                        subscriptions = subscriptions,
                                        starred = preferences.starredChannels,
                                        feed = feed,
                                        refreshState = feedRefresh,
                                        onRefresh = viewModel::refreshFeeds,
                                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                                        onOpenChannel = { id -> viewModel.openBrowse("$HOME_URL/channel/$id") },
                                        onUnsubscribe = viewModel::unsubscribe,
                                        onToggleStar = viewModel::toggleStar,
                                        onOpenVideo = { id -> viewModel.openVideo(id) },
                                        onDownload = viewModel::requestDownload,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                AppTab.LIBRARY -> LocalRefresh(modifier = Modifier.fillMaxSize()) {
                                    LibraryScreen(
                                        route = shown.route ?: LibraryRoute.Root,
                                        history = history,
                                        playlists = playlists,
                                        continueWatching = continueWatching,
                                        historyPaused = preferences.historyPaused,
                                        playlistItems = viewModel.playlistItemsFlow,
                                        onNavigate = viewModel::openLibraryRoute,
                                        onOpenVideo = viewModel::openVideo,
                                        onDeleteHistoryEntry = viewModel::deleteHistoryEntry,
                                        onClearHistory = viewModel::clearWatchHistory,
                                        onToggleHistoryPaused = viewModel::toggleHistoryPaused,
                                        onCreatePlaylist = viewModel::createPlaylist,
                                        onRenamePlaylist = viewModel::renamePlaylist,
                                        onDeletePlaylist = viewModel::deletePlaylist,
                                        onRemoveFromPlaylist = viewModel::removeFromPlaylist,
                                        onMovePlaylistItem = viewModel::movePlaylistItem,
                                        onOpenSettings = viewModel::openSettings,
                                        downloadsSummary = downloadsSummary(offlineItems),
                                        onDownload = viewModel::requestDownload,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Downloads play in their own player, over everything (also in picture-in-picture).
            if (offlineActive && (offlineExpanded || isInPipMode)) {
                OfflinePlayerScreen(
                    controller = offline,
                    video = videoState,
                    isInPipMode = isInPipMode,
                    onMinimize = viewModel::collapseOffline,
                    onEnterPip = onEnterPip,
                    modifier = Modifier.zIndex(3f)
                )
            }

            // Settings slide over everything; the WebView stays alive (and playing) underneath.
            AnimatedVisibility(
                visible = showSettings && !isInPipMode,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.zIndex(4f)
            ) {
                SettingsScreen(
                    preferences = preferences,
                    blockCounts = blockCounts,
                    lastStart = lastStart,
                    pipStats = PipStats(videoState.pipBlockedPauses, videoState.pipSystemPauses, videoState.pipStalls),
                    tapFixes = tapFixes.count to tapFixes.lastTop,
                    autoplayDiagnostics = autoplayDiagnostics,
                    recentBlocked = recentBlocked,
                    onClose = viewModel::closeSettings,
                    onToggleAdBlock = viewModel::toggleAdBlock,
                    onToggleSponsorSkip = viewModel::toggleSponsorSkip,
                    onToggleBackgroundPlay = viewModel::toggleBackgroundPlay,
                    onToggleAutoPip = viewModel::toggleAutoPip,
                    onOpenSpeedSheet = viewModel::openSpeedSheet,
                    onToggleDarkMode = viewModel::toggleDarkMode,
                    onToggleAmoledBlack = viewModel::toggleAmoledBlack,
                    onResetCounts = viewModel::resetBlockCounts,
                    onToggleAutoplay = viewModel::toggleAutoplay,
                    onToggleShowStartTime = viewModel::toggleShowStartTime,
                    onSetSmartSize = viewModel::setSmartSize,
                    onRefreshSmart = viewModel::refreshSmartNow,
                    onResetDownloadQuality = viewModel::resetDownloadQuality,
                    onExportBackup = { exportBackupLauncher.launch(BackupManager.defaultFileName()) },
                    onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onToggleAutoBackup = viewModel::toggleAutoBackup,
                    onChooseBackupFolder = { backupFolderLauncher.launch(null) },
                    onBackupNow = viewModel::backupNow,
                    onImportTakeoutHistory = { takeoutHistoryLauncher.launch(arrayOf("application/json", "*/*")) }
                )
            }

            AnimatedVisibility(
                visible = statusBanner != null && !isInPipMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .zIndex(5f)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp, start = 16.dp, end = 16.dp)
            ) {
                statusBanner?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .clickable { viewModel.dismissBanner() }
                            .testTag("status_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = EmeraldSafe,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = message, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    unsubscribeRequest?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPageUnsubscribe,
            title = { Text("Unsubscribe?") },
            text = { Text("Remove ${request.name} from your subscriptions in this app.") },
            confirmButton = { TextButton(onClick = viewModel::confirmPageUnsubscribe) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPageUnsubscribe) { Text("Cancel") } }
        )
    }

    pendingImport?.let { pending ->
        val sum = pending.summary
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Import backup?") },
            text = {
                Text(
                    buildString {
                        append("From ${sum.device.ifBlank { "another device" }}")
                        if (sum.exportedAt > 0) append(", saved ${formatAgo(sum.exportedAt)}")
                        append(".\n\n${sum.subscriptions} subscriptions, ${sum.playlists} playlists (${sum.playlistItems} videos), ")
                        append("${sum.history} watched videos, ${sum.searches} searches, and settings.\n\n")
                        append("Merge keeps what is on this phone and adds the backup. Replace deletes this phone's library first.")
                    }
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { viewModel.confirmImport(ImportMode.REPLACE) }) { Text("Replace") }
                    TextButton(onClick = { viewModel.confirmImport(ImportMode.MERGE) }) { Text("Merge") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancel") } }
        )
    }

    if (showActionsSheet && !isInPipMode) {
        VideoActionsSheet(
            actions = currentActions,
            playlists = playlists,
            onDismiss = viewModel::dismissActionsSheet,
            onToggleSubscribe = viewModel::toggleSubscribeCurrent,
            onToggleLike = viewModel::toggleLikeCurrent,
            onToggleWatchLater = viewModel::toggleWatchLaterCurrent,
            onTogglePlaylist = viewModel::toggleCurrentInPlaylist,
            onCreatePlaylist = viewModel::createPlaylistWithCurrent,
            onToggleNotInterested = viewModel::toggleNotInterestedCurrent,
            onShare = shareCurrent,
            onDownload = {
                viewModel.dismissActionsSheet()
                viewModel.downloadCurrent()
            }
        )
    }

    downloadSheet?.let { sheet ->
        if (!isInPipMode) {
            DownloadQualitySheet(
                state = sheet,
                onConfirm = viewModel::confirmDownload,
                onDismiss = viewModel::dismissDownloadSheet
            )
        }
    }

    if (showSpeedSheet && !isInPipMode) {
        PlaybackSpeedBottomSheet(
            currentSpeed = preferences.playbackSpeed,
            rememberSpeed = preferences.rememberSpeedAcrossVideos,
            onSpeedSelected = viewModel::setPlaybackSpeed,
            onToggleRememberSpeed = viewModel::toggleRememberSpeed,
            onDismiss = viewModel::dismissSpeedSheet
        )
    }
}

/** What the tab area shows; the key for its transitions. [depth]: 0 = a tab's own screen. */
private sealed interface TabScreen {
    val tab: AppTab
    val depth: Int

    data class Tab(override val tab: AppTab, val route: LibraryRoute?) : TabScreen {
        override val depth: Int get() = if (route != null && route != LibraryRoute.Root) 1 else 0
    }

    data object Downloads : TabScreen {
        override val tab: AppTab get() = AppTab.LIBRARY
        override val depth: Int get() = 1
    }

    data class Browse(override val tab: AppTab) : TabScreen {
        override val depth: Int get() = 2
    }
}

/**
 * Pull-to-refresh on a screen whose content is all on the phone (Library, Downloads, Music):
 * the same gesture as elsewhere; the lists are already current, so it only shows briefly.
 */
@Composable
private fun LocalRefresh(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(600)
            refreshing = false
        }
    }
    PullToRefreshContainer(refreshing = refreshing, onRefresh = { refreshing = true }, modifier = modifier, content = content)
}

/** "3 downloads · 120 smart songs" for the Library tab. */
private fun downloadsSummary(items: List<OfflineItemEntity>): String {
    val manual = items.count { !it.isSmart && it.isDone }
    val smart = items.count { it.isSmart && it.isDone }
    val active = items.count { !it.isDone && it.status != OfflineStatus.FAILED.name }
    val parts = buildList {
        add(if (manual == 1) "1 download" else "$manual downloads")
        if (smart > 0) add("$smart smart songs")
        if (active > 0) add("$active in progress")
    }
    return if (manual == 0 && smart == 0 && active == 0) "Nothing downloaded yet" else parts.joinToString(" · ")
}
