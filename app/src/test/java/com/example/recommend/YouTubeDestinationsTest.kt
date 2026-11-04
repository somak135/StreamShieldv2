package com.example.recommend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reading YouTube's topic pages (shapes from the live site, 3 Nov 2026; values made up). */
// SDK 35 like the other Robolectric tests: SDK 36 needs Java 21, the build runs Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class YouTubeDestinationsTest {

    private val page = """
    {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[
      {"richSectionRenderer":{"content":{"richShelfRenderer":{"title":{"runs":[{"text":"Live Now"}]},"contents":[
        {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"liveVideo01","title":{"runs":[{"text":"Live news"}]},
          "ownerText":{"runs":[{"text":"News 18","navigationEndpoint":{"browseEndpoint":{"browseId":"UCnews00000000000000000A"}}}]},
          "viewCountText":{"runs":[{"text":"149,186"},{"text":" watching"}]},"shortViewCountText":{"runs":[{"text":"149K"},{"text":" watching"}]},
          "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"LIVE","text":{"runs":[{"text":"LIVE"}]}}}]}}}},
        {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"plainVideo1","title":{"runs":[{"text":"Plain video"}]},
          "ownerText":{"runs":[{"text":"Chan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCchan00000000000000000A"}}}]},
          "shortViewCountText":{"simpleText":"13K views"},"viewCountText":{"simpleText":"13,678 views"},
          "publishedTimeText":{"simpleText":"15 minutes ago"},"lengthText":{"simpleText":"10:07"},
          "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"DEFAULT","text":{"simpleText":"10:07"}}}]}}}},
        {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"upcoming001","title":{"runs":[{"text":"Soon"}]},
          "upcomingEventData":{"startTime":"1800000000"}}}}},
        {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"plainVideo1","title":{"runs":[{"text":"Plain video again"}]}}}}}
      ]}}}},
      {"itemSectionRenderer":{"contents":[{"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[
        {"gridVideoRenderer":{"videoId":"gridVideo01","title":{"runs":[{"text":"Grid video"}]},
          "shortBylineText":{"runs":[{"text":"Gamer","navigationEndpoint":{"browseEndpoint":{"browseId":"UCgamer0000000000000000A"}}}]},
          "shortViewCountText":{"simpleText":"1.7M views"},"publishedTimeText":{"simpleText":"6 days ago"},
          "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"DEFAULT","text":{"simpleText":"39:33"}}}]}},
        {"gridVideoRenderer":{"videoId":"shortVideo1","title":{"runs":[{"text":"A short"}]},
          "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"SHORTS"}}]}}
      ]}}}}]}},
      {"itemSectionRenderer":{"contents":[{"horizontalCardListRenderer":{"cards":[
        {"gameCardRenderer":{"game":{"gameDetailsRenderer":{"title":{"simpleText":"A game"}}}}},
        {"videoCardRenderer":{"videoId":"cardVideo01","title":{"runs":[{"text":"Card video"}]},
          "bylineText":{"runs":[{"text":"Streamer","navigationEndpoint":{"browseEndpoint":{"browseId":"UCstream000000000000000A"}}}]},
          "metadataText":{"simpleText":"71K views · 7 days ago"},"lengthText":{"simpleText":"16:51"}}}
      ]}}]}},
      {"richSectionRenderer":{"content":{"richShelfRenderer":{"contents":[
        {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"lockupVid01","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[
            {"thumbnailBadgeViewModel":{"text":"2:37","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"}}]}}]}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"A music video"},"metadata":{"contentMetadataViewModel":{"metadataRows":[
            {"metadataParts":[{"text":{"content":"thekidszn","commandRuns":[{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCartist000000000000000A"}}}}]}}]},
            {"metadataParts":[{"text":{"content":"403K views"}},{"text":{"content":"13 days ago"}}]}]}}}}}}}},
        {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"RDstation","contentType":"LOCKUP_CONTENT_TYPE_STATION",
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"A mix"}}}}}}}
      ]}}}},
      {"richSectionRenderer":{"content":{"reelShelfRenderer":{"items":[{"reelItemRenderer":{"videoId":"reelVideo01"}}]}}}}
    ]}}}}]}}}
    """.trimIndent()

    @Test
    fun `videos of every shape, each once, in page order`() {
        val videos = YouTubeDestinations.parse(JSONObject(page))
        assertEquals(listOf("liveVideo01", "plainVideo1", "gridVideo01", "cardVideo01", "lockupVid01"), videos.map { it.videoId })
    }

    @Test
    fun `fields`() {
        val byId = YouTubeDestinations.parse(JSONObject(page)).associateBy { it.videoId }
        val live = byId.getValue("liveVideo01")
        assertTrue(live.live)
        assertEquals(0, live.durationSec)
        assertEquals("149K watching", live.meta)
        assertEquals("UCnews00000000000000000A", live.channelId)
        assertEquals(ChipVideo("plainVideo1", "Plain video", "UCchan00000000000000000A", "Chan", 607, "13K views · 15 minutes ago"), byId["plainVideo1"])
        assertEquals(ChipVideo("gridVideo01", "Grid video", "UCgamer0000000000000000A", "Gamer", 2373, "1.7M views · 6 days ago"), byId["gridVideo01"])
        assertEquals(ChipVideo("cardVideo01", "Card video", "UCstream000000000000000A", "Streamer", 1011, "71K views · 7 days ago"), byId["cardVideo01"])
        assertEquals(ChipVideo("lockupVid01", "A music video", "UCartist000000000000000A", "thekidszn", 157, "403K views · 13 days ago"), byId["lockupVid01"])
    }
}
