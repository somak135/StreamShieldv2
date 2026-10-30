package com.example.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.data.isYouTubeVideoPage
import com.example.engine.AdBlockShieldEngine
import com.example.engine.RequestFilter
import com.example.ui.PullSource
import com.example.ui.StreamShieldViewModel
import org.json.JSONObject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The second WebView: YouTube pages that are browsed rather than watched (search results,
 * channels, playlists). Nothing plays here. A tap on a video is handed to the player page
 * ([BrowserController]), so the video plays in the player while this page keeps its place, and
 * Back / minimize returns to it with the video in the mini-player, as in the YouTube app.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowsePageController(
    private val activity: ComponentActivity,
    private val viewModel: StreamShieldViewModel
) {
    val webView = WebView(activity)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usesDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var destroyed = false

    /** History index of the first page of the current visit (see [BrowserController.load]). */
    private var sessionStartIndex = 0

    init {
        configureWebView()
        activity.lifecycleScope.launch {
            combine(viewModel.preferences, viewModel.appVisible) { prefs, visible ->
                AdBlockShieldEngine.configJson(prefs, inPip = false, role = ROLE, appVisible = visible, pull = "refresh")
            }.distinctUntilChanged().collect { pushConfig(it) }
        }
        activity.lifecycleScope.launch {
            viewModel.subscriptionsChanged.collect {
                if (!destroyed) {
                    webView.evaluateJavascript("window.__streamShield && window.__streamShield.refreshSubscribeButtons();", null)
                }
            }
        }
    }

    /** Loads [url] as a new visit (a search, a channel, a playlist). */
    fun load(url: String) {
        if (destroyed) return
        sessionStartIndex = webView.copyBackForwardList().currentIndex + 1
        webView.loadUrl(url)
    }

    fun canGoBackInSession(): Boolean =
        webView.canGoBack() && webView.copyBackForwardList().currentIndex > sessionStartIndex

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun reload() = webView.reload()

    fun destroy() {
        if (destroyed) return
        destroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Nothing should play on this page (the page script refuses playback as well).
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = BrowserController.chromeUserAgent(WebSettings.getDefaultUserAgent(activity))
        }
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        if (usesDocumentStartScript) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AdBlockShieldEngine.shieldScript(activity),
                setOf("https://*.youtube.com", "https://youtube.com")
            )
        }
        webView.webViewClient = Client()
        webView.webChromeClient = object : WebChromeClient() {
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun currentConfigJson(): String =
        AdBlockShieldEngine.configJson(
            viewModel.preferences.value,
            inPip = false,
            role = ROLE,
            appVisible = viewModel.appVisible.value,
            pull = "refresh"
        )

    /** App in the background: nothing on this page needs to run. */
    fun onAppHidden() {
        if (!destroyed) webView.onPause()
    }

    fun onAppShown() {
        if (!destroyed) webView.onResume()
    }

    private fun pushConfig(json: String = currentConfigJson()) {
        if (destroyed) return
        webView.evaluateJavascript("window.__streamShield && window.__streamShield.setConfig($json);", null)
    }

    private fun injectFallback(view: WebView?) {
        if (usesDocumentStartScript || view == null) return
        val url = view.url ?: return
        if (!BrowserController.isYouTubeUrl(url)) return
        view.evaluateJavascript(AdBlockShieldEngine.shieldScript(activity), null)
    }

    private var lastReportedUrl = ""

    private fun onUrl(view: WebView?, url: String?, loading: Boolean) {
        if (view == null || url == null || url == "about:blank") return
        if (isYouTubeVideoPage(url)) {
            // A video page slipped through (a tap the page script did not catch): play it in the
            // player page and put this page back where it was.
            if (url != lastReportedUrl) {
                lastReportedUrl = url
                viewModel.openVideoUrl(url)
                mainHandler.post { if (!destroyed && webView.canGoBack()) webView.goBack() }
            }
            return
        }
        lastReportedUrl = url
        viewModel.onBrowsePageChanged(url, loading)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getConfig(): String = currentConfigJson()

        @JavascriptInterface
        fun onAdBlocked(kind: String?) {
            mainHandler.post { viewModel.onAdBlocked() }
        }

        @JavascriptInterface
        fun onOpenVideo(url: String?) {
            if (url.isNullOrBlank()) return
            mainHandler.post { viewModel.openVideoUrl(url) }
        }

        @JavascriptInterface
        fun onHomeLogo(url: String?) {
            mainHandler.post { viewModel.onHomeLogoTapped() }
        }

        @JavascriptInterface
        fun onPull(data: String?) {
            val mode = data?.substringBefore(':') ?: return
            val distance = data.substringAfter(':', "").toFloatOrNull() ?: return
            mainHandler.post { viewModel.onWebPull(PullSource.BROWSE, mode, distance) }
        }

        @JavascriptInterface
        fun onPullEnd(json: String?) {
            val o = try { JSONObject(json ?: return) } catch (_: Exception) { return }
            mainHandler.post {
                viewModel.onWebPullEnd(
                    PullSource.BROWSE,
                    o.optString("mode"),
                    o.optDouble("distance", 0.0).toFloat(),
                    o.optDouble("velocity", 0.0).toFloat(),
                    o.optBoolean("cancelled")
                )
            }
        }

        @JavascriptInterface
        fun onSubscribeTap(json: String?) {
            val channel = BrowserController.parseChannel(json) ?: return
            mainHandler.post { viewModel.onPageSubscribeTap(channel) }
        }

        @JavascriptInterface
        fun isSubscribed(json: String?): Boolean {
            val channel = BrowserController.parseChannel(json) ?: return false
            return viewModel.isChannelSubscribed(channel.id, channel.path)
        }

        /** Like / Dislike buttons are handled on video pages, which open in the player page. */
        @JavascriptInterface
        fun onLikeTap(json: String?) = Unit

        @JavascriptInterface
        fun likeState(videoId: String?): String = ""

        /** YouTube Music plays in the player page only. */
        @JavascriptInterface
        fun onMusicQueue(json: String?) = Unit

        @JavascriptInterface
        fun onResumeResult(result: String?) = Unit

        // The page script does not report playback from this page; these keep the bridge
        // surface identical to the player page's.
        @JavascriptInterface
        fun onState(json: String?) = Unit

        @JavascriptInterface
        fun onSpeedChanged(speed: String?) = Unit

        @JavascriptInterface
        fun onSponsorSkipped(seconds: String?) = Unit

        @JavascriptInterface
        fun onOpenBrowse(url: String?) = Unit
    }

    private inner class Client : WebViewClient() {
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
            if (request == null) return false
            val uri = request.url ?: return false
            val scheme = uri.scheme ?: return false
            if (scheme != "http" && scheme != "https") return true
            val url = uri.toString()
            if (request.isForMainFrame && isYouTubeVideoPage(url)) {
                viewModel.openVideoUrl(url)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onUrl(view, url, loading = true)
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
            onUrl(view, url, loading = false)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            onUrl(view, url, loading = false)
        }
    }

    companion object {
        private const val BRIDGE_NAME = "StreamShieldBridge"
        const val ROLE = "browse"
    }
}
