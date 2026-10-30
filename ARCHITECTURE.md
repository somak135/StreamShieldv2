# Architecture

This document describes how StreamShield is structured, so you can find where a change belongs.
It covers what is stable; details that change often are left to the code.

## Bird's-eye view

StreamShield is a single-activity Android app. The YouTube mobile website runs in **two
WebViews** that live as long as the activity: the *player page* (`BrowserController`: videos and
YouTube Music) and the *browse page* (`BrowsePageController`: search results, channels,
playlists; nothing plays there, a tapped video is handed to the player page). Downloads play in a
third, native player (Media3 ExoPlayer, `offline/OfflinePlayerController`). Around them the app
draws native Compose screens in the YouTube app's layout: Home, Music, Subscriptions, Library,
Settings, a mini-player and the player controls. Features are added in three layers:

1. **Page layer:** a JavaScript file, `app/src/main/assets/shield.js`, injected into every YouTube
   page *before YouTube's own scripts run*. It removes ads from YouTube's data, keeps playback
   going in the background, controls speed and SponsorBlock skipping, reports playback state,
   routes taps between the two pages, reports pull-down gestures and handles Subscribe buttons.
2. **Network layer:** `WebViewClient.shouldInterceptRequest` refuses ad and tracker requests.
3. **Android layer:** Compose UI, a ViewModel holding app state, a local library (Room), a
   foreground service with a MediaSession for background play, WorkManager jobs for downloads and
   backups, picture-in-picture and fullscreen.

There is no Google account. Subscriptions, history, likes, playlists and feedback are stored in
the app; new uploads come from each channel's public RSS feed; "For you", smart downloads and
downloads use NewPipe Extractor (JitPack) to read YouTube's related lists and streams.

```
 ┌───────────────────────────────── MainActivity ──────────────────────────────────┐
 │  Compose: StreamShieldApp ── TabsScaffold (Home, Music, Subscriptions, Library,  │
 │           browse page) · BrowserScreen (player page) · OfflinePlayerScreen ·     │
 │           SettingsScreen · sheets and dialogs                                    │
 │        │ reads StateFlows / calls actions                                        │
 │        ▼                                                                         │
 │  StreamShieldViewModel ──► DataStore prefs · Room (library, downloads, plays)    │
 │        ▲     │        ──► ForYouRepository · SubscriptionRefresher (RSS)         │
 │        │     │        ──► DownloadWorker / SmartDownloadWorker (WorkManager)     │
 │        │     └──► BackgroundPlaybackService (MediaSession, notification)         │
 │        │ onVideoState / onWebPull / onPageSubscribeTap / …                       │
 │  BrowserController ─► KeepAliveWebView (player page) ─┐                          │
 │  BrowsePageController ─► WebView (browse page) ───────┤ shield.js in each page   │
 │        ▲  evaluateJavascript(window.__streamShield.*) │                          │
 │        └──────────── StreamShieldBridge (@JavascriptInterface) ◄─┘               │
 │  OfflinePlayerController ─► ExoPlayer (downloads)    PlaybackCommands (media     │
 │                                                      buttons) ─► active player   │
 └──────────────────────────────────────────────────────────────────────────────────┘
```

## Code map

All Kotlin lives under `app/src/main/java/com/example/` (the namespace is `com.example`; the
application id is `com.aistudio.streamshield.vqxkpl`; the fork somak135/StreamShieldv2 builds as
`com.aistudio.streamshield.vytkzp`, see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#application-id)).

### `MainActivity.kt`
The only activity. It creates the two page controllers and the offline player, hosts the Compose
tree (`StreamShieldApp`) and owns window-level behaviour:
- picture-in-picture: params with the video's aspect ratio, previous / play-pause / next
  `RemoteAction`s, `setAutoEnterEnabled` on Android 12+ (videos only, not songs) and
  `onUserLeaveHint` before that;
- keep-screen-on while a video (not a song) plays;
- notification permission on Android 13+;
- links: `youtube.com` / `youtu.be` / `music.youtube.com` links opened with the app or shared to
  it;
- `StreamShieldApp`: the player page is always composed (so playback continues); the tabs are
  drawn over it, and an open video is drawn over the tabs (`zIndex`) with the slide animation;
  the tab area is an `AnimatedContent` keyed by `TabScreen`; pull-to-refresh wrappers; the
  predictive-back handler for the player; the unsubscribe and backup-import dialogs;
- Back at the root page: moves the task to the background if something is playing with
  background play on, otherwise finishes.

`configChanges` in the manifest covers rotation, resizing, PiP and dark-mode changes. The activity
is not recreated in those cases, so the WebViews keep playing.

### `browser/BrowserController.kt` and `browser/BrowsePageController.kt`
`BrowserController` owns the player page's `KeepAliveWebView` and everything WebView-specific:
- **Settings:** the user agent is the WebView default with the WebView markers removed, so YouTube
  serves its normal Chrome layout.
- **Script injection:** `shield.js` is registered through
  `WebViewCompat.addDocumentStartJavaScript` for `https://*.youtube.com`. On WebViews without that
  feature it falls back to `evaluateJavascript` in page callbacks.
- **Network filter:** `shouldInterceptRequest` calls `RequestFilter`; main-frame navigations are
  never blocked.
- **Fullscreen:** `onShowCustomView` adds the view to the decor view, hides the system bars, locks
  landscape for landscape videos and registers a Back callback.
- **Bridge and commands:** the `StreamShieldBridge` JavaScript interface receives page events. The
  controller forwards `PlaybackCommands` (notification, lock screen, PiP buttons) to the page as
  `window.__streamShield.play()/pause()/togglePlay()/next()/previous()` while the web player is the
  active player.
- **Config push:** observes preferences, PiP state, app visibility and the pull-down mode and calls
  `window.__streamShield.setConfig(...)` when they change. The page echoes its PiP flag in each
  state report; if it differs from the app's, the config is sent again.
- **Start:** the player page loads nothing until the first video or YouTube Music. (On YouTube's
  home page, which nobody sees there, YouTube played a feed item as a background preview.)
- **Visits:** every page the app opens with `load()` starts a *visit*; Back walks the WebView
  history only inside it. `refreshPage()` reloads (pull-to-refresh) and restarts a YouTube Music
  song that was playing.

`BrowsePageController` is the same for the browse page (role `browse`): nothing plays, video
taps and video URLs go to the player page (`openVideoUrl`), the WebView is paused while the app is
hidden.

**Rule:** the controllers read settings from the ViewModel *when they need them*. They never
capture them in a closure; an earlier version did, and toggles silently stopped working.

`KeepAliveWebView` fakes window visibility and focus and ignores `onPause()` while `keepAlive` is
true, so Chromium keeps decoding media. `keepAlive` is true in PiP, and with background play on
only while the web player has a video or song loaded (and is the active player); an idle page is
left to Chromium's normal background throttling.

### `assets/shield.js`
The page script. It is idempotent: it returns early if `window.__streamShield` exists. Sections:
1. **JSON ad pruning:** wraps `JSON.parse`, `Response.prototype.json` and the
   `XMLHttpRequest.prototype.response` getter (for `responseType = 'json'`), and puts setters on
   `window.ytInitialPlayerResponse` / `ytInitialData`. It deletes `adPlacements`, `adSlots`,
   `playerAds` and similar keys from player responses, removes ad renderers from
   browse/search/next/Shorts responses, and drops the anti-adblock enforcement dialog. Only objects
   that look like YouTube InnerTube responses (`responseContext`, `playabilityStatus`, …) are walked.
   Batched responses (arrays) and wrappers (`{playerResponse, response}`, as returned for in-app
   navigation) are pruned element by element. `setTimeout` calls with exactly 17000 ms and a
   native-code callback are shortened to 17 ms (uBlock Origin's `nano-stb` rule for YouTube).
2. **Background play:** overrides `document.hidden` / `visibilityState`, and stops page-level
   `visibilitychange`/`blur`/`pagehide`/`freeze` events with capture listeners registered first.
3. **Cosmetic CSS** for leftover ad slots, plus a PiP layout stylesheet. `hideOpenAppButton()`
   (run from `tick()`) hides YouTube's "Open App" / "Sign in" buttons, matched by label inside the
   top bar. m.youtube's own bottom bar (`ytm-pivot-bar-renderer`) is hidden because the app shows
   its tabs under the page. A capture-phase click listener on `window` turns a tap on the YouTube
   logo into `onHomeLogo` (the app's Home tab); so does in-page navigation to `/`. The "Watch in
   YouTube app" dialog is pruned from player responses (`upsellDialogRenderer`) and, if one still
   shows, closed with its own close button (`dismissAppUpsell()`).
4. **Player hooks:**
   - a `MutationObserver` on the player's `class` attribute only; when `ad-showing` appears, it
     mutes the ad, presses skip and seeks to the end, then restores sound and speed;
   - speed enforcement;
   - SponsorBlock segment skipping on `timeupdate`;
   - state reporting (sent only when the state changes);
   - a heartbeat that re-finds the player after single-page navigation: every 1.5 s while the
     app is on screen (`config.appVisible`), every 5 s in the background (playback state only,
     no page tidying), and every 10 s on a hidden browse page (which then does nothing);
   - a PiP keep-playing guard: in PiP, any pause the user did not request through the app is
     undone, using the `pause` event plus a 500 ms check that runs only in PiP; after a refused
     page pause, `resyncPlayer()` calls `playVideo()` so the player keeps downloading (live
     streams), and `waiting` events in PiP are counted as stalls;
   - autoplay while hidden: on `ended` in PiP or with the app hidden, open the next video of the
     mix/playlist (or the suggestion, if Autoplay is on) captured from watch-next responses
     (`nextByVideo`); on YouTube Music, the page's next track first;
   - muted starts: a video YouTube started muted is unmuted through `player.unMute()` (not during
     ads, not after the user muted on the page, at most 5 times per video), only on a watch page
     or YouTube Music, so feed previews elsewhere stay muted;
   - start-up diagnostics (time to first frame, time in ad state, ads removed, 17 s timers
     shortened), sent with the state report, shown in Settings → Diagnostics and, unless turned
     off, as a short note when a video starts. For in-page navigation, timing starts at the user's
     last tap (the URL only changes, via `history.pushState`, once the data has arrived).
5. **Native → page API:** `window.__streamShield.setConfig`, `setSponsorSegments`, `play`, `pause`,
   `togglePlay`, `next`, `previous`, `exitFullscreen`, `nudgeLayout`, `refreshSubscribeButtons`,
   `refreshLikeButtons`, `resumeMusic`, `seekWhenReady`. `getConfig`, `reportState` and `internals` are for tests and
   CDP debugging.
6. **Play/pause taps:** a tap on a visible play/pause control in the player that has not changed
   playback 400 ms later is applied by the app (`pauseVideo` / `resumeVideo`). Each fix is counted
   with the element that was on top at the tapped point (`tapFix`, `tapTop` in the state report;
   Settings → Diagnostics). A pause right after a tap in the page counts as the user's own. The
   state report also carries the page's copy of the PiP flag (`cfgPip`); the app re-sends its
   config if they differ. A dialog hidden by `dismissAppUpsell()` takes its backdrop with it.
7. **Pull-down gesture:** passive touch listeners report a vertical pull that starts at the top of
   the page (`onPull`, `onPullEnd` with distance and velocity); nothing is prevented. `config.pull`
   decides: `refresh` (browse page, YouTube Music in the Music tab), `minimize` (video page; also
   a pull that starts on the video) or `off` (and always off in PiP). The app draws the indicator.
8. **Subscribe buttons:** taps on YouTube's Subscribe buttons are stopped in the capture phase
   (YouTube's sign-in prompt never opens) and sent as `onSubscribeTap {id, path, name}`: the video
   page's channel (player response), a channel row's link, or the channel page (its
   `channelMetadataRenderer`, noted from YouTube's JSON, or the URL). Buttons show the app's state
   through the synchronous bridge call `isSubscribed`.
9. **Like / Dislike buttons** (video and Shorts pages on m.youtube.com, not comments): taps are
   stopped the same way and sent as `onLikeTap {videoId, kind}`; the app keeps likes in Liked
   videos and dislikes as feedback (`DISLIKED`). Buttons show the state from `likeState(videoId)`
   (`aria-pressed`, a tint); `refreshLikeButtons()` after a change. The browse page never shows a
   video page, so its bridge answers these calls with no-ops.
10. **Browse page fade:** while YouTube swaps pages in the browse page, `html.ss-nav` dims the body
   briefly.

Page → native calls go through `window.StreamShieldBridge`: `getConfig()` and `isSubscribed(json)`
(both synchronous), `onAdBlocked(kind)`, `onState(json)`, `onSpeedChanged(rate)`,
`onSponsorSkipped(seconds)`, `onHomeLogo(url)`, `onOpenVideo(url)` (browse page),
`onOpenBrowse(url)` (player page), `onPull(mode:distance)`, `onPullEnd(json)`,
`onSubscribeTap(json)` and `onResumeResult(result)`. Both controllers implement the same surface.
Bridge methods run on a background thread; the controllers post to the main thread.

### `engine/`
- `RequestFilter.kt`: pure Kotlin. Matches the host (walking up parent domains in hash sets) and,
  for YouTube/Google hosts, the path (`/pagead/`, `/api/stats/ads`, …). It must stay cheap
  because it runs for every request. `surrogateFor` answers ad-blocker check scripts
  (`static.doubleclick.net/instream/ad_status.js`) with a harmless stand-in instead of blocking
  them, as uBlock Origin does.
- `AdBlockShieldEngine.kt`: the empty 204 response for blocked requests, cached loading of
  `shield.js`, and the config JSON sent to the page.
- `SponsorBlockClient.kt`: fetches skip segments from `sponsor.ajay.app` and parses them. It sends
  only the video ID.

### `ui/`
- `StreamShieldViewModel.kt`: the single source of app state (StateFlows): preferences, page and
  video state, tabs and navigation (`currentTab`, `playerExpanded`, `browseTab`, `libraryRoute`),
  the library flows, downloads, playback source (web or offline), pull-down and refresh state, the
  opening-video placeholder, For you, and diagnostics. It records history and the listening log
  from `onVideoState`, fetches SponsorBlock segments, keeps the background service in sync, and
  batches counters (flushed to DataStore at most every 5 s and on `onStop`).
- `screens/`: `HomeScreen` (the YouTube app's layout: top bar with search, tabs Home · Subs ·
  Recently uploaded · New to you, full-width cards), `MusicScreen` (the YouTube Music app's
  layout: mood chips, Speed dial, Quick picks, Mixed for you, Forgotten favorites, New releases,
  then YouTube Music's own shelves),
  `SubscriptionsScreen` (channels with stars, feed), `LibraryScreen` (history, playlists),
  `DownloadsScreen`, `BrowserScreen` (player page: the WebView, the opening-video placeholder, the
  pull indicator and the player control bar), `BrowsePageScreen`, `OfflinePlayerScreen` and
  `SettingsScreen` (slides over everything; the WebViews stay alive underneath).
- `components/`: `TabsScaffold` and `AppNavigationBar`, `VideoComponents` (cards, rows, menus),
  `PlayerChrome` (mini-player, Save sheet), `DownloadComponents` (quality sheet),
  `PlaybackSpeedSheet`, and `PullRefresh` (`PullToRefreshContainer` for native lists and
  `PullIndicator`, shared with the web pages).
- `theme/`: colours, typography and the `MyApplicationTheme` light/dark/AMOLED schemes.

### `service/`
- `BackgroundPlaybackService.kt`: a foreground service (`mediaPlayback` type) with a
  `MediaSessionCompat` and a media-style notification. It only mirrors the active player's
  state; it does not play anything itself.
  - While playing: foreground plus a partial wake lock.
  - While paused: notification detached, wake lock released.
  - Shuts down on Stop, on task removal, or when the activity finishes.
  - `stop()` goes through `onStartCommand(ACTION_SHUTDOWN)` rather than `stopService()`, to honour
    the `startForegroundService` contract.
- `PlaybackCommands.kt`: a process-wide `SharedFlow` carrying media-control commands from the
  service, MediaSession, PiP receiver and mini-player to whichever player is active.

### `data/`
- `UserPreferencesRepository.kt`: DataStore preferences (`ShieldPreferencesState`), including
  download qualities, smart-download size and starred channels. Counter keys carry a `_v2` suffix
  because the v1 values were seeded with fake numbers.
- `AppDatabase.kt`: Room database `streamshield_offline.db`, version 4, with migrations 1→2
  (library), 2→3 (downloads, listening log) and 3→4 (Shorts flag on feed items). The original
  tables `bookmarks`, `watch_history` and `downloads` are kept so the schema stays compatible:
  `downloads` (the original simulated downloads) is unused, and `bookmarks` has no UI any more but
  still goes into and out of backups. `fallbackToDestructiveMigration` is on, so **bumping the
  version without a migration wipes the library**.
- `LibraryEntities.kt`, `LibraryDao.kt`, `LibraryRepository.kt`: videos, watch entries (resume
  position, watched fraction), subscriptions, feed items, playlists (Watch later and Liked are
  built in), feedback ("Not interested", hidden channels, dislikes) and searches.
- `OfflineEntities.kt`: downloads (`offline_items`), the listening log (`play_events`) and smart
  downloads the rule removed (`smart_removed`).
- `SubscriptionFeed.kt`: the RSS client and parser (also marks Shorts), the refresher, and the
  Google Takeout `subscriptions.csv` parser.
- `BackupManager.kt`, `AutoBackupWorker.kt`: export/import of the library and settings as one JSON
  file, the daily automatic backup into a folder the user picked, and Takeout history import.
- `StreamModels.kt`: `WebVideoState`, speed presets, URL and format helpers.

### `download/`
- `NewPipeSupport.kt`: NewPipe Extractor setup on OkHttp, stream and related-video lists, channel
  id lookup.
- `StreamChoice.kt`, `RangeFetcher.kt`, `MediaRemuxer.kt`, `OfflineStorage.kt`,
  `OfflineDownloader.kt`, `DownloadWorker.kt`, `DownloadCatalog.kt`: picking streams, fetching them
  in 512 KB ranges, merging video and audio, saving to `Movies/StreamShield` / `Music/StreamShield`,
  and the WorkManager job with its notification.
- `SmartRule.kt` (the reviewed smart-download rule, pure Kotlin) and `SmartDownloads.kt` (the
  daily job that applies it).

### `recommend/`
- `ForYouRule.kt` (the reviewed "For you" rule, pure Kotlin) and `ForYouRepository.kt` (reads
  the library, fetches related / related-of-related / search lists and watched channels' RSS
  feeds within a budget, caches them, tracks what was shown, saves it all in `for_you.json`).
- `MusicForYouRule.kt` (the Music tab's agreed "For you" rule for songs, pure Kotlin) and
  `MusicForYouRepository.kt` (keeps YouTube Music's queues reported by the page, fetches a few
  related lists and followed artists' RSS feeds, saves it all in `music_for_you.json`).

### `offline/`
- `OfflinePlayerController.kt`: ExoPlayer for downloads; reports its state in the same shape as the
  web player and takes the media buttons while it is the active player.

## Key flows

**Downloads.** A Download button (player controls, Save sheet, every ⋮ menu, the strip above the
tabs while a YouTube Music song plays) calls `StreamShieldViewModel.requestDownload`: a
remembered quality enqueues at once, otherwise `DownloadQualitySheet` lists what
`DownloadCatalog` found. `DownloadWorker` (WorkManager, foreground notification) runs
`OfflineDownloader`: `NewPipeSupport` → `StreamChoice` (H.264+AAC → MP4, VP9+Opus → WebM, or a
stream with sound built in) → `RangeFetcher` → `MediaRemuxer` → `OfflineStorage`. Rows live in
`offline_items` (database v3).

**Smart downloads (music only).** `SmartDownloadWorker` runs daily on Wi-Fi while charging (and
on "Refresh now" / size changes) and applies `SmartRule` (see its KDoc for the reviewed rule) to
the listening log (`play_events`, one row per play, written by the view model), likes, playlists,
subscriptions and feedback; Discover uses YouTube's related lists via NewPipe Extractor.

**Playback source.** `StreamShieldViewModel.playbackSource` is WEB or OFFLINE. The active player
reports `WebVideoState` and takes the media buttons (`PlaybackCommands`); the mini-player,
notification, PiP and history work the same for both.

**Navigation (YouTube-app style).** The app's tabs (Home · Music · Subscriptions · Library) are on
screen for every browsing page: the native screens, and web pages such as search results,
channels, playlists and YouTube Music (`BrowserScreen`'s `bottomBar` slot). Only a YouTube video
page (`isYouTubeVideoPage`: `/watch`, `/shorts/`, `/live/<id>`, `/embed/`) hides them and shows the
player controls. `StreamShieldViewModel.playerExpanded` says whether the web page or a native
screen is shown; `webPageUrl` is the page shown or being opened. Every page the app opens through
`BrowserController.load` starts a new *visit*: Back walks the WebView history only inside the
visit, and at its first page returns to the native tab. Back on a video page goes to the results /
channel page it was opened from (same visit), otherwise minimizes to the mini-player.

**Opening a video.** YouTube navigates within its single-page app. The document-start script has
already hooked `JSON.parse`/`fetch`, so the player response arrives without ad placements. The
script hooks the `<video>` element and reports `onState`. The ViewModel then fetches SponsorBlock
segments, which the controller pushes into the page, and records history.

**Transitions.** The player page is always composed under the tabs. Opening a video raises it
above the tabs (`zIndex`) and slides it up from the bottom (`playerSlide`, an `Animatable`);
minimizing slides it down and puts it back underneath. Back on a video page uses
`PredictiveBackHandler` (the player follows the gesture on Android 14+; the manifest enables
`enableOnBackInvokedCallback`), and the page script's pull-down drives the same slide. While a
video loads, `pendingVideo` (thumbnail and title) covers the page until it plays, 1.2 s after the
new video's page first reports its state, 2.5 s after the page loaded, or 5 s at most, whichever
comes first. The tab area is an `AnimatedContent` keyed by `TabScreen`: deeper
screens (browse page, Library sub-screens) slide in from the right, other tabs cross-fade.

**Pull-to-refresh.** Native screens use `PullToRefreshContainer` (nested scrolling, no
experimental Material API): Home refreshes subscriptions and rebuilds "For you", Subscriptions
refreshes the feeds, the music home rebuilds its "For you", Library / Downloads only show the
gesture. Web pages report the pull
from the page script; `StreamShieldViewModel.onWebPullEnd` reloads the page (`reloadRequests`) or
minimizes the player. Reloading YouTube Music while a song plays stops it, so
`BrowserController.refreshPage` starts it again afterwards (`resumeMusic` through the player's
`loadVideoById`; if that does not take, the song's page is opened and `seekWhenReady` seeks).
A search from the music home opens YouTube Music's search page the same way
(`BrowserController.loadKeepingMusic`).

**Music tab.** It opens on the app's music home (`MusicScreen`); `musicPageShown` says whether
YouTube Music's page (in the player page) fills the tab instead. A song, a search or the
"YouTube Music" button shows the page; Back at the first page of that visit, or tapping the Music
tab, returns to the music home, with the song in the mini-player (tapping it shows the page
again). YouTube Music is loaded only from there, so opening the tab does not replace a paused
video.

**For you (Music).** `recommend/MusicForYouRule` (agreed rule, KDoc; unit-tested) and
`MusicForYouRepository`, built like Home's. Seeds are songs played in the app (play count
counts). Candidates: YouTube Music's queue after a seed song, reported by the page script
(`onMusicQueue`, from the watch-next response: radio, album or playlist; kept 30 days, no extra
requests), YouTube's related lists (up to 4 per refresh, NewPipe) and RSS uploads of the 5 artists
played most. Artists are matched by name ("- Topic" removed). Songs played in the last 14 days
are left out (they are in Speed dial).

**Music home shelves.** `recommend/MusicShelves` (pure Kotlin, unit-tested) makes Speed dial,
Mixed for you (radios: `watch?v=X&list=RDAMVMX`) and Forgotten favorites from the history.
`recommend/YouTubeMusicHome` loads YouTube Music's own signed-out home through InnerTube
(`/youtubei/v1/browse`, client WEB_REMIX, the version NewPipe Extractor determines, the WebView's
YouTube Music cookies): the first page has the mood chips and a few shelves; further shelves need
the `visitorData` of the first response as `X-Goog-Visitor-Id` (without it the continuation
returns an empty page). A chip is the same request with the chip's `params`.

**For you (Home).** `recommend/ForYouRule` (pure Kotlin, unit-tested; its KDoc is the reviewed
rule) and `ForYouRepository`. Candidates: new uploads from subscriptions (RSS), YouTube's related
lists for a rotating sample of up to 30 seed videos (recent watches count double), related lists
of the best related videos, uploads of often-watched channels without a subscription (RSS) and
results of recent searches (NewPipe Extractor; each list cached a day, a few fetched per refresh).
Each gets a score (source terms, popularity, channel score minus dislikes); videos shown on
screen in earlier lists without being opened are discounted (x 0.6 per list, 3 days). A refresh
pins fresh subscription uploads, then draws the rest in a random order weighted by
e^(score / 0.35) (Gumbel trick), every third a subscription upload, at most 2 per channel in 20.
Home shows 30, then 20 more as you scroll, and asks the repository for more lists at the end.
Rebuilt when Home opens and the list is older than 4 h, or on pull-down. Starred channels are a
preference (`starredChannels`, long-press in the Subscriptions tab).

**Background play.**
1. A state of `playing=true` makes the ViewModel call `BackgroundPlaybackService.update(...)`
   (`startForegroundService` the first time).
2. When the app is hidden, `KeepAliveWebView` and the visibility overrides in `shield.js` keep
   playback going.
3. Notification and lock-screen buttons go to the service, then to `PlaybackCommands`, then to
   `BrowserController`, then to `__streamShield.play()/pause()`.
4. The page reports the new state, which updates the notification.

**Picture-in-picture.**
1. The activity enters PiP (auto-enter, Home, or the button).
2. `isInPipMode=true` hides the Compose chrome and pushes `pip:true`, which applies the
   full-window player CSS.
3. On exit, the CSS is removed and `nudgeLayout()` fires several `resize` events so YouTube
   re-sizes the player. This fixes a cropped player after PiP.
4. Closing the PiP window (rather than expanding it) pauses the video.

**Fullscreen.** YouTube's fullscreen button calls `requestFullscreen`. WebView then calls
`onShowCustomView`, and the controller shows that view over everything. Back, or YouTube's exit
button, calls `onHideCustomView`.

## Architectural invariants

- Exactly two WebViews per activity (player page and browse page), created in
  `MainActivity.onCreate` and destroyed in `onDestroy`. Never create WebViews inside composables;
  composables host the controllers' instances with `AndroidView` (the factory detaches the view
  from any previous host).
- `shield.js` must stay idempotent and must not throw at document start: `document.head` and even
  `document.documentElement` may not exist yet.
- Native code talks to the page only through `window.__streamShield.*`; the page talks to native
  code only through `StreamShieldBridge`. Keep both surfaces small and update both sides (and
  both controllers) together.
- One player at a time: `playbackSource` decides whether the web player or the offline player
  owns the mini-player, notification and media buttons.
- Hot paths stay cheap. That means `shouldInterceptRequest`, the bridge callbacks and anything in
  `shield.js` that runs per mutation, per request or per `timeupdate`. Do not add document-wide
  `MutationObserver`s or fast `setInterval`s, and do not write to disk per event.
- Counters count events once: one ad prevented, or one request blocked.

## Testing

- `app/src/test/.../ExampleRobolectricTest.kt` covers the request filter, SponsorBlock parsing,
  config JSON, URL/format helpers, the user-agent rewrite and PiP aspect-ratio clamping.
- `app/src/test/.../data/LibraryTest.kt` covers the database migrations, library round trips, the
  RSS and Takeout parsers and page classification; `download/SmartRuleTest.kt` and
  `recommend/ForYouRuleTest.kt` cover the two reviewed rules, `recommend/MusicForYouRuleTest.kt`
  the music rule.
- `tools/shield-js-test/shield.test.mjs` runs `shield.js` in headless Chromium against mock
  YouTube pages. It covers pruning, the ad fallback, background-play events, speed, SponsorBlock,
  PiP layout, page routing, the heartbeat, play/pause tap fixes, Subscribe and Like / Dislike
  buttons, feed previews left muted, pull-down
  gestures, the browse-page fade, resuming after a reload and parse overhead. It cannot catch
  YouTube changing its markup or JSON shape, so verify on a device after YouTube updates.
