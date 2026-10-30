package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.BrowserController
import com.example.data.ShieldPreferencesState
import com.example.data.extractYouTubeVideoId
import com.example.data.formatCount
import com.example.data.formatSpeedBadge
import com.example.engine.AdBlockShieldEngine
import com.example.engine.BlockKind
import com.example.engine.RequestFilter
import com.example.engine.SponsorBlockClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `app name resolves`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("StreamShield", context.getString(R.string.app_name))
    }

    @Test
    fun `request filter blocks youtube ad endpoints and ad networks`() {
        assertEquals(BlockKind.AD, RequestFilter.evaluate("https://m.youtube.com/api/stats/ads?ver=2&ns=1", true))
        assertEquals(BlockKind.AD, RequestFilter.evaluate("https://www.youtube.com/pagead/adview?ai=1", true))
        assertEquals(BlockKind.AD, RequestFilter.evaluate("https://www.youtube.com/ptracking?pltype=adhost", true))
        assertEquals(BlockKind.AD, RequestFilter.evaluate("https://www.google.com/pagead/lvz?evtid=1", true))
        assertEquals(BlockKind.AD, RequestFilter.evaluate("https://securepubads.g.doubleclick.net/gampad/ads", true))
        assertEquals(BlockKind.AD, RequestFilter.evaluate("https://static.doubleclick.net/instream/ad_status.js", true))
        assertEquals(BlockKind.TRACKER, RequestFilter.evaluate("https://www.google-analytics.com/collect?v=1", true))
    }

    @Test
    fun `request filter leaves playback and page requests alone`() {
        assertNull(RequestFilter.evaluate("https://m.youtube.com/watch?v=MnCf88b1hG0", true))
        assertNull(RequestFilter.evaluate("https://m.youtube.com/youtubei/v1/player?key=x", true))
        assertNull(RequestFilter.evaluate("https://m.youtube.com/api/stats/watchtime?docid=x", true))
        assertNull(RequestFilter.evaluate("https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1", true))
        assertNull(RequestFilter.evaluate("https://i.ytimg.com/vi/abc/hqdefault.jpg", true))
        // Look-alike hosts must not match by suffix.
        assertNull(RequestFilter.evaluate("https://notdoubleclick.net/x", true))
        assertNull(RequestFilter.evaluate("https://example.com/pagead/x", true))
        // Query strings are not paths.
        assertNull(RequestFilter.evaluate("https://m.youtube.com/results?search_query=/pagead/", true))
    }

    @Test
    fun `ad blocker check script gets a stand-in instead of being blocked`() {
        val url = "https://static.doubleclick.net/instream/ad_status.js"
        assertEquals("window.google_ad_status = 1;", RequestFilter.surrogateFor(url, true))
        assertNull(RequestFilter.surrogateFor(url, false))
        assertNull(RequestFilter.surrogateFor("https://static.doubleclick.net/other.js", true))
    }

    @Test
    fun `request filter is off when ad block is disabled`() {
        assertNull(RequestFilter.evaluate("https://m.youtube.com/api/stats/ads", false))
        assertNull(RequestFilter.evaluate("https://ad.doubleclick.net/x", false))
    }

    @Test
    fun `host and path parsing`() {
        assertEquals("m.youtube.com", RequestFilter.hostOf("https://user@M.YouTube.com:443/watch?v=1"))
        assertEquals("/watch", RequestFilter.pathOf("https://m.youtube.com/watch?v=1#t=3"))
        assertEquals("/", RequestFilter.pathOf("https://m.youtube.com"))
        assertNull(RequestFilter.hostOf("not a url"))
    }

    @Test
    fun `sponsorblock segments are parsed and filtered`() {
        val json = """
            [
              {"category":"sponsor","actionType":"skip","segment":[30.5,75.0],"UUID":"a"},
              {"category":"selfpromo","actionType":"mute","segment":[100,110],"UUID":"b"},
              {"category":"interaction","actionType":"skip","segment":[10,10.4],"UUID":"c"},
              {"category":"selfpromo","segment":[5,9],"UUID":"d"}
            ]
        """.trimIndent()
        val segments = SponsorBlockClient.parseSegments(json)
        assertEquals(listOf(5.0 to 9.0, 30.5 to 75.0), segments)
        assertEquals("[[5,9],[30.5,75]]", SponsorBlockClient.segmentsToJson(segments))
        assertTrue(SponsorBlockClient.parseSegments("Not Found").isEmpty())
        assertTrue(SponsorBlockClient.parseSegments("").isEmpty())
    }

    @Test
    fun `page config json carries live preferences`() {
        val prefs = ShieldPreferencesState(adBlockEnabled = false, backgroundPlayEnabled = true, playbackSpeed = 1.5f)
        val json = JSONObject(AdBlockShieldEngine.configJson(prefs, inPip = true, appVisible = false))
        assertFalse(json.getBoolean("appVisible"))
        assertTrue(json.getBoolean("autoplay"))
        assertFalse(json.getBoolean("adBlock"))
        assertTrue(json.getBoolean("bgPlay"))
        assertTrue(json.getBoolean("pip"))
        assertEquals(1.5, json.getDouble("speed"), 0.001)
    }

    @Test
    fun `shield script ships in assets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val script = AdBlockShieldEngine.shieldScript(context)
        assertTrue(script.contains("window.__streamShield"))
        assertTrue(script.contains("StreamShieldBridge"))
    }

    @Test
    fun `video ids are extracted from youtube urls`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("abcdefghijk", extractYouTubeVideoId("https://www.youtube.com/shorts/abcdefghijk"))
        assertNull(extractYouTubeVideoId("https://m.youtube.com/feed/subscriptions"))
    }

    @Test
    fun `speed and count formatting`() {
        assertEquals("1.0x", formatSpeedBadge(1.0f))
        assertEquals("1.1x", formatSpeedBadge(1.1f))
        assertEquals("1.25x", formatSpeedBadge(1.25f))
        assertEquals("999", formatCount(999))
        assertEquals("1.2k", formatCount(1_234))
        assertEquals("45k", formatCount(45_000))
    }

    @Test
    fun `webview user agent is presented as chrome`() {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.6723.107 Mobile Safari/537.36",
            BrowserController.chromeUserAgent(ua)
        )
        assertTrue(BrowserController.isYouTubeUrl("https://m.youtube.com/watch?v=1"))
        assertFalse(BrowserController.isYouTubeUrl("https://evil-youtube.com/"))
    }

    @Test
    fun `pip aspect ratio is clamped to what android accepts`() {
        assertEquals(16f / 9f, MainActivity.pipAspectRatio(1920, 1080).toFloat(), 0.001f)
        assertEquals(2.39f, MainActivity.pipAspectRatio(4000, 1000).toFloat(), 0.001f)
        assertEquals(1f / 2.39f, MainActivity.pipAspectRatio(100, 1000).toFloat(), 0.001f)
        assertEquals(16f / 9f, MainActivity.pipAspectRatio(0, 0).toFloat(), 0.001f)
    }
}
