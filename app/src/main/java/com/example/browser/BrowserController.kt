package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.BuildConfig
import com.example.data.WebVideoState
import com.example.data.isYouTubeBrowsePage
import com.example.data.isYouTubeVideoPage
import com.example.ui.PlaybackSource
import com.example.ui.PageChannel
import com.example.ui.PullSource
import com.example.ui.MUSIC_HOME_URL
import com.example.engine.AdBlockShieldEngine
import com.example.engine.RequestFilter
import com.example.service.PlaybackCommand
import com.example.service.PlaybackCommands
import com.example.ui.StreamShieldViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * WebView that keeps media running while the activity is hidden (background play) or shown in a
 * picture-in-picture window. The page side of this lives in assets/shield.js.
 */
class KeepAliveWebView(context: Context) : WebView(context) {
    @Volatile
    var keepAlive: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAlive && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    // Chromium treats a WebView whose view (or an ancestor) is not VISIBLE as hidden and pauses
    // its video; keep reporting VISIBLE while playback should continue.
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepAlive && visibility != View.VISIBLE) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
        } else {
            super.onVisibilityChanged(changedView, visibility)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(if (keepAlive) true else hasWindowFocus)
    }

    override fun onPause() {
        if (!keepAlive) super.onPause()
    }
}

/**
 * Owns the player WebView (videos and YouTube Music) for the lifetime of the activity; browsing
 * pages live in [BrowsePageController]. Handles navigation, the ad-block
 * network filter, the page script, fullscreen video, and media commands. Settings are read from
 * the ViewModel at the time they are needed, never captured, so toggles apply immediately.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(
    private val activity: ComponentActivity,
    private val viewModel: StreamShieldViewModel
) {
    val webView = KeepAliveWebView(activity)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usesDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitFullscreen()
    }
    private var destroyed = false

    init {
        configureWebView()
        observeViewModel()
        // Nothing is loaded until the first video or YouTube Music. (It used to open YouTube's
        // home page here, which nobody sees: YouTube played a feed preview on it in the
        // background, using data and battery; refusing that preview in shield.js was the likely
        // cause of a ~30 s black start screen on a phone.)
    }

    // -------------------------------------------------------------------------------------------
    // Public API used by the UI
    // -------------------------------------------------------------------------------------------

    /**
     * History index of the first page opened by the app's last [load]. Pages before it belong to
     * an earlier visit (e.g. a video watched before going back to the tabs), so Back from the web
     * page stops there and returns to the app's tabs instead.
     */
    private var sessionStartIndex = 0

    /** Loads [url] as a new visit from the app (a search, a video, a channel, a shared link). */
    fun load(url: String) {
        sessionStartIndex = webView.copyBackForwardList().currentIndex + 1
        webView.loadUrl(url)
    }

    /** True if Back can stay inside the current visit (see [sessionStartIndex]). */
    fun canGoBackInSession(): Boolean =
        webView.canGoBack() && webView.copyBackForwardList().currentIndex > sessionStartIndex

    /** URL of the previous page of the current visit, or null. */
    fun previousUrlInSession(): String? = if (canGoBackInSession()) previousUrl() else null

    /** YouTube Music song to start again after a pull-to-refresh reload (id, position). */
    private var resumeAfterReload: Pair<String, Int>? = null
    /** After the fallback (opening the song's page): seek there once it loads. */
    private var seekAfterLoad: Pair<String, Int>? = null

    /**
     * Pull-to-refresh: reloads the page. YouTube Music stops playing on a reload, so a song that
     * was playing is started again from the same point afterwards.
     */
    fun refreshPage() {
        if (destroyed) return
        val video = viewModel.videoState.value
        resumeAfterReload = if (video.isMusicPage && video.isPlaying && video.videoId.isNotBlank()) {
            video.videoId to video.positionSec
        } else {
            null
        }
        webView.reload()
    }

    /**
     * Opens a YouTube Music page from the app (a search). YouTube Music stops playing when its
     * page is replaced, so a song that was playing is started again from the same point, as
     * after a pull-to-refresh.
     */
    fun loadKeepingMusic(url: String) {
        if (destroyed) return
        val video = viewModel.videoState.value
        resumeAfterReload = if (video.isMusicPage && video.isPlaying && video.videoId.isNotBlank()) {
            video.videoId to video.positionSec
        } else {
            null
        }
        load(url)
    }

    private fun handleResumeResult(ok: Boolean, videoId: String, position: Int) {
        if (ok || destroyed) return
        // The player API did not take it: open the song's page and seek there.
        seekAfterLoad = videoId to position
        load("$MUSIC_HOME_URL/watch?v=$videoId")
    }

    @Volatile private var lastResume: Pair<String, Int>? = null

    /** URL of the previous history entry, or null (used to keep Back inside YouTube Music). */
    private fun previousUrl(): String? {
        val list = webView.copyBackForwardList()
        val index = list.currentIndex - 1
        return if (index >= 0) list.getItemAtIndex(index)?.url else null
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun play() = runPageCommand("play()")

    fun pause() = runPageCommand("pause()")

    fun togglePlay() = runPageCommand("togglePlay()")

    /** Next / previous track, through the page's own queue, autoplay or playlist. */
    fun next() = runPageCommand("next()")

    fun previous() = runPageCommand("previous()")

    fun exitFullscreen() {
        if (fullscreenView == null) return
        runPageCommand("exitFullscreen()")
        fullscreenCallback?.onCustomViewHidden()
        hideFullscreenView()
    }

    /** Called when the activity stops (screen off, home, PiP window closed). */
    fun onActivityStopped() {
        if (!viewModel.preferences.value.backgroundPlayEnabled) pause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        hideFullscreenView()
        webView.keepAlive = false
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    // -------------------------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------------------------

    private fun configureWebView() {
        // Debug builds: let chrome://inspect / adb + CDP attach to the YouTube page (testing).
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.setBackgroundColor(Color.BLACK)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.keepAlive = viewModel.preferences.value.backgroundPlayEnabled

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = chromeUserAgent(WebSettings.getDefaultUserAgent(activity))
        }

        webView.addJavascriptInterface(PageBridge(), BRIDGE_NAME)

        if (usesDocumentStartScript) {
            // Runs before any of YouTube's scripts, in every YouTube frame, on every navigation.
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AdBlockShieldEngine.shieldScript(activity),
                setOf("https://*.youtube.com", "https://youtube.com")
            )
        }

        webView.webViewClient = ShieldWebViewClient()
        webView.webChromeClient = ShieldChromeClient()
    }

    private fun observeViewModel() {
        // Keep the page "visible" in the background only while it has a video or song loaded (so
        // it can play or be resumed from the notification). An idle page (YouTube's start page, or
        // while a download plays) is left to Chromium's normal background throttling.
        val hasWebMedia = combine(viewModel.videoState, viewModel.playbackSource) { video, source ->
            source == PlaybackSource.WEB && video.videoId.isNotBlank()
        }.distinctUntilChanged()
        activity.lifecycleScope.launch {
            combine(viewModel.preferences, viewModel.isInPipMode, hasWebMedia) { prefs, pip, media ->
                webView.keepAlive = pip || (prefs.backgroundPlayEnabled && media)
                if (pip) {
                    // As the original app did: make sure Chromium's media pipeline and timers run
                    // in the PiP window.
                    webView.onResume()
                    webView.resumeTimers()
                }
            }.collect { }
        }
        activity.lifecycleScope.launch {
            combine(viewModel.preferences, viewModel.isInPipMode, viewModel.appVisible, viewModel.playerPullMode) { prefs, pip, visible, pull ->
                AdBlockShieldEngine.configJson(prefs, pip, appVisible = visible, pull = pull)
            }.distinctUntilChanged().collect { pushConfig(it) }
        }
        activity.lifecycleScope.launch {
            viewModel.subscriptionsChanged.collect { runPageCommand("refreshSubscribeButtons()") }
        }
        activity.lifecycleScope.launch {
            viewModel.likesChanged.collect { runPageCommand("refreshLikeButtons()") }
        }
        activity.lifecycleScope.launch {
            viewModel.sponsorSegments.collect { pushSponsorSegments() }
        }
        activity.lifecycleScope.launch {
            PlaybackCommands.flow.collect { command ->
                // Downloads play in the offline player; it takes the media buttons while active.
                if (viewModel.playbackSource.value != PlaybackSource.WEB) return@collect
                when (command) {
                    PlaybackCommand.PLAY -> play()
                    PlaybackCommand.PAUSE, PlaybackCommand.STOP -> pause()
                    PlaybackCommand.TOGGLE -> togglePlay()
                    PlaybackCommand.NEXT -> next()
                    PlaybackCommand.PREVIOUS -> previous()
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Page script plumbing
    // -------------------------------------------------------------------------------------------

    private fun currentConfigJson(): String =
        AdBlockShieldEngine.configJson(
            viewModel.preferences.value,
            viewModel.isInPipMode.value,
            appVisible = viewModel.appVisible.value,
            pull = viewModel.playerPullMode.value
        )

    private fun pushConfig(json: String = currentConfigJson()) {
        if (destroyed) return
        webView.evaluateJavascript("window.__streamShield && window.__streamShield.setConfig($json);", null)
    }

    private fun pushSponsorSegments() {
        val segments = viewModel.sponsorSegments.value ?: return
        if (destroyed) return
        val videoId = JSONObject.quote(segments.videoId)
        webView.evaluateJavascript(
            "window.__streamShield && window.__streamShield.setSponsorSegments($videoId, ${segments.json});",
            null
        )
    }

    private fun runPageCommand(call: String) {
        if (destroyed) return
        webView.evaluateJavascript("window.__streamShield && window.__streamShield.$call;", null)
    }

    /** Older WebViews without document-start scripts: inject as early as the callbacks allow. */
    private fun injectFallback(view: WebView?) {
        if (usesDocumentStartScript || view == null) return
        val url = view.url ?: return
        if (!isYouTubeUrl(url)) return
        view.evaluateJavascript(AdBlockShieldEngine.shieldScript(activity), null)
    }

    private var lastPageUrl = ""

    private fun reportPage(view: WebView?, url: String?, loading: Boolean) {
        if (view == null || url == null || url == "about:blank") return
        val previous = lastPageUrl
        lastPageUrl = url
        if (url != previous && isYouTubeVideoPage(previous) && isYouTubeBrowsePage(url)) {
            // Left a video for a channel / results page by a route the page script did not catch:
            // show that page in the browse page and put the video back.
            viewModel.openBrowse(url)
            mainHandler.post { if (!destroyed && webView.canGoBack()) webView.goBack() }
            return
        }
        viewModel.onPageChanged(url, loading)
    }

    private inner class PageBridge {
        // Called on the WebView's JavaBridge thread.
        @JavascriptInterface
        fun getConfig(): String = currentConfigJson()

        @JavascriptInterface
        fun onAdBlocked(kind: String?) {
            mainHandler.post { viewModel.onAdBlocked() }
        }

        @JavascriptInterface
        fun onState(json: String?) {
            val state = parseState(json) ?: return
            val pagePip = try { JSONObject(json ?: "{}").optBoolean("cfgPip") } catch (_: Exception) { false }
            mainHandler.post {
                // The page's copy of the PiP flag must match the app's (a lost config update
                // would leave YouTube's own pause refused): send the config again if not.
                if (pagePip != viewModel.isInPipMode.value) pushConfig()
                viewModel.onVideoState(state)
            }
        }

        @JavascriptInterface
        fun onPull(data: String?) {
            val mode = data?.substringBefore(':') ?: return
            val distance = data.substringAfter(':', "").toFloatOrNull() ?: return
            mainHandler.post { viewModel.onWebPull(PullSource.PLAYER, mode, distance) }
        }

        @JavascriptInterface
        fun onPullEnd(json: String?) {
            val o = try { JSONObject(json ?: return) } catch (_: Exception) { return }
            mainHandler.post {
                viewModel.onWebPullEnd(
                    PullSource.PLAYER,
                    o.optString("mode"),
                    o.optDouble("distance", 0.0).toFloat(),
                    o.optDouble("velocity", 0.0).toFloat(),
                    o.optBoolean("cancelled")
                )
            }
        }

        @JavascriptInterface
        fun onSubscribeTap(json: String?) {
            val channel = parseChannel(json) ?: return
            mainHandler.post { viewModel.onPageSubscribeTap(channel) }
        }

        @JavascriptInterface
        fun isSubscribed(json: String?): Boolean {
            val channel = parseChannel(json) ?: return false
            return viewModel.isChannelSubscribed(channel.id, channel.path)
        }

        /** YouTube's Like / Dislike tapped: {videoId, kind: "like" | "dislike"}. */
        @JavascriptInterface
        fun onLikeTap(json: String?) {
            val o = try { JSONObject(json ?: return) } catch (_: Exception) { return }
            val videoId = o.optString("videoId")
            val kind = o.optString("kind")
            mainHandler.post { viewModel.onPageLikeTap(videoId, kind) }
        }

        /** "like", "dislike" or "" for the page's Like buttons. */
        @JavascriptInterface
        fun likeState(videoId: String?): String = viewModel.pageLikeState(videoId.orEmpty())

        /** YouTube Music's queue for a song (for the Music tab's For you); parsed on this thread. */
        @JavascriptInterface
        fun onMusicQueue(json: String?) {
            viewModel.onMusicQueue(json ?: return)
        }

        @JavascriptInterface
        fun onResumeResult(result: String?) {
            val resume = lastResume ?: return
            mainHandler.post { handleResumeResult(result == "ok", resume.first, resume.second) }
        }

        @JavascriptInterface
        fun onSpeedChanged(speed: String?) {
            val value = speed?.toFloatOrNull() ?: return
            mainHandler.post { viewModel.setPlaybackSpeed(value) }
        }

        /** The YouTube logo in m.youtube's top bar was tapped: show the app's Home tab. */
        @JavascriptInterface
        fun onHomeLogo(url: String?) {
            mainHandler.post { viewModel.onHomeLogoTapped() }
        }

        /** A channel / playlist / search link on the video page: open it in the browse page. */
        @JavascriptInterface
        fun onOpenBrowse(url: String?) {
            if (url.isNullOrBlank()) return
            mainHandler.post { viewModel.openBrowse(url) }
        }

        /** Only the browse page reports video taps; kept for an identical bridge surface. */
        @JavascriptInterface
        fun onOpenVideo(url: String?) = Unit

        @JavascriptInterface
        fun onSponsorSkipped(seconds: String?) {
            mainHandler.post { viewModel.showBanner("Skipped sponsor segment (${seconds ?: "?"}s)") }
        }
    }

    private inner class ShieldWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request == null || request.isForMainFrame) return null
            val adBlock = viewModel.preferences.value.adBlockEnabled
            val url = request.url.toString()
            RequestFilter.surrogateFor(url, adBlock)?.let { script ->
                viewModel.onRequestBlocked(url)
                return AdBlockShieldEngine.createScriptResponse(script)
            }
            RequestFilter.evaluate(url, adBlock) ?: return null
            viewModel.onRequestBlocked(url)
            return AdBlockShieldEngine.createEmptyResponse()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme ?: return false
            // YouTube's "open in app" links (intent://, vnd.youtube://) would fail with an error page.
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            reportPage(view, url, loading = true)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            injectFallback(view)
            pushConfig()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            injectFallback(view)
            pushConfig()
            pushSponsorSegments()
            reportPage(view, url, loading = false)
            resumeAfterReload?.let { (id, pos) ->
                resumeAfterReload = null
                lastResume = id to pos
                runPageCommand("resumeMusic(${JSONObject.quote(id)}, $pos)")
            }
            seekAfterLoad?.let { (id, pos) ->
                seekAfterLoad = null
                runPageCommand("seekWhenReady(${JSONObject.quote(id)}, $pos)")
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            reportPage(view, url, loading = false)
        }
    }

    private inner class ShieldChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) return
            if (fullscreenView != null) {
                callback?.onCustomViewHidden()
                return
            }
            showFullscreenView(view, callback)
        }

        override fun onHideCustomView() {
            hideFullscreenView()
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            reportPage(view, view?.url, loading = view?.progress?.let { it < 100 } ?: false)
        }

        // Without this WebView draws a grey play-button placeholder before videos load.
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // -------------------------------------------------------------------------------------------
    // Fullscreen video
    // -------------------------------------------------------------------------------------------

    private fun showFullscreenView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        val decor = activity.window.decorView as? FrameLayout ?: run {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        decor.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        WindowCompat.getInsetsController(activity.window, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val video = viewModel.videoState.value
        val portraitVideo = video.videoWidth in 1 until video.videoHeight
        activity.requestedOrientation = if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // Re-register so this callback is the most recent one and wins over Compose BackHandlers.
        fullscreenBackCallback.remove()
        activity.onBackPressedDispatcher.addCallback(activity, fullscreenBackCallback)
        fullscreenBackCallback.isEnabled = true
    }

    private fun hideFullscreenView() {
        val view = fullscreenView ?: return
        fullscreenView = null
        fullscreenCallback = null
        (view.parent as? ViewGroup)?.removeView(view)

        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullscreenBackCallback.isEnabled = false
        runPageCommand("nudgeLayout()")
    }

    companion object {
        private const val BRIDGE_NAME = "StreamShieldBridge"

        fun isYouTubeUrl(url: String): Boolean {
            val host = RequestFilter.hostOf(url) ?: return false
            return host == "youtube.com" || host.endsWith(".youtube.com")
        }

        /**
         * WebView's default UA marks itself as a WebView ("; wv", "Version/4.0"), which makes
         * YouTube serve "open the app" prompts and older layouts. Present as the matching Chrome.
         */
        fun chromeUserAgent(defaultUa: String): String {
            return defaultUa
                .replace("; wv)", ")")
                .replace(Regex(" Build/[^;)]+"), "")
                .replace(" Version/4.0", "")
        }

        /** {id, path, name} from the page script's Subscribe button. */
        fun parseChannel(json: String?): PageChannel? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                PageChannel(o.optString("id"), o.optString("path"), o.optString("name"))
            } catch (_: Exception) {
                null
            }
        }

        private fun parseState(json: String?): WebVideoState? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                WebVideoState(
                    videoId = o.optString("videoId"),
                    url = o.optString("url"),
                    title = o.optString("title"),
                    channel = o.optString("channel"),
                    durationSec = o.optInt("duration"),
                    isPlaying = o.optBoolean("playing"),
                    videoWidth = o.optInt("width"),
                    videoHeight = o.optInt("height"),
                    loadMs = o.optInt("loadMs", -1),
                    startMs = o.optInt("startMs", -1),
                    adMs = o.optInt("adMs"),
                    adsPruned = o.optInt("pruned"),
                    pipBlockedPauses = o.optInt("pipBlocked"),
                    pipSystemPauses = o.optInt("pipSystem"),
                    pipStalls = o.optInt("pipStalls"),
                    channelId = o.optString("channelId"),
                    lengthSec = o.optInt("lengthSec"),
                    viewCount = o.optLong("views"),
                    category = o.optString("category"),
                    positionSec = o.optInt("pos"),
                    ended = o.optBoolean("ended"),
                    timersBoosted = o.optInt("boosted"),
                    tapFixes = o.optInt("tapFix"),
                    tapFixTop = o.optString("tapTop"),
                    autoAdvance = o.optString("adv"),
                    autoAdvanceAt = o.optLong("advAt")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
