# AGENTS.md

Working notes for AI coding agents and contributors. Read [ARCHITECTURE.md](ARCHITECTURE.md)
first for the component map; this file covers commands, conventions and pitfalls.

## Project at a glance

- Android app, Kotlin + Jetpack Compose, single activity (`MainActivity`), package `com.example`.
- YouTube runs in two WebViews: the player page (`browser/BrowserController.kt`) and the browse
  page (`browser/BrowsePageController.kt`). Ad blocking, player control, page routing, pull-down
  gestures and Subscribe buttons are done by `app/src/main/assets/shield.js`, injected at document
  start into both.
- Native screens (Home, Music, Subscriptions, Library, Downloads, Settings) are Compose; state lives
  in `ui/StreamShieldViewModel.kt` (StateFlows). Preferences are in DataStore; the library,
  downloads and listening log in Room.
- Downloads, smart downloads and "For you" use NewPipe Extractor (JitPack, pinned commit) and
  WorkManager; downloads play in Media3 ExoPlayer (`offline/`).
- Personal-use app: favour robustness and low battery and CPU use over feature breadth.

## Local environment

If you are running in the Docker dev container (`Dockerfile`) or another environment with
internet access and a device/emulator, follow [docs/LOCAL_TESTING.md](docs/LOCAL_TESTING.md):
setup, inspecting the live YouTube page over CDP, and the test plan.

## Commands

```sh
./gradlew assembleDebug                 # build
./gradlew testDebugUnitTest             # Robolectric/JVM unit tests
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs   # shield.js browser tests
node --check app/src/main/assets/shield.js                          # quick syntax check
```

- There is no `gradlew` script checked in. `tools/dev-setup.sh` generates it, plus the git-ignored
  `debug.keystore` that debug builds are signed with, and `local.properties`; or use Android
  Studio. Building, CI, releases and signing: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
- The build needs Google Maven (`dl.google.com`), Maven Central and JitPack. In sandboxes without
  them, run at least the shield.js tests; the pure-Kotlin rules (`download/SmartRule.kt`,
  `recommend/ForYouRule.kt`, `engine/RequestFilter.kt`) compile in a plain Kotlin/JVM project
  (the Kotlin compiler from GitHub releases works where Maven is blocked; JUnit test names must
  not contain `:` or `.`).
- The shield.js tests need the `playwright` npm package, Chromium and `ffmpeg` (used to generate a
  test clip).

### Working through Google AI Studio

The app is also developed in a Google AI Studio project, which builds it and runs it in a cloud
Android emulator. Changed files are uploaded there as a zip (the code view's upload) and saved;
AI Studio rebuilds, and "Build successful" plus a relaunch of the app (no `FATAL` in the console)
confirms the build. If a save reports that the preview is outdated, save again and accept the
uploaded version in the conflict dialog. The emulator's network always gets YouTube's "confirm
you're not a bot" check, so playback, downloads and NewPipe requests cannot be tested there;
browsing, navigation, the library, settings and Subscribe buttons can. **Install** in AI Studio
installs on a phone over USB.

## Conventions

- Match the surrounding style: four-space indent, KDoc on classes and non-obvious functions, and
  short comments that explain *why*.
- **Read settings live.** Inside WebView callbacks, bridge methods and service code, read
  `viewModel.preferences.value` at call time. Never capture preference values in lambdas created
  once (factory blocks, clients).
- **Main thread.** `@JavascriptInterface` methods run on a background thread: only read StateFlow
  values, `@Volatile` snapshots or parse JSON there, and `post` everything else to the main thread.
- **Bridge changes.** When you add a native → page call, add it to `window.__streamShield` in
  `shield.js` *and* a test in `tools/shield-js-test/shield.test.mjs`. When you add a page → native
  call, add a `@JavascriptInterface` method to **both** `BrowserController.PageBridge` and
  `BrowsePageController.Bridge` (a no-op where it does not apply), and to the mock bridge in the
  tests.
- **Batching.** Counters and other high-frequency events are batched in memory; do not write
  DataStore or Room per event. Progress is written at most every 15 s.
- **Preferences.** New preferences go in `ShieldPreferencesState` + `UserPreferencesRepository`
  (and `toBackupJson` / `restoreFromBackup` if they belong in backups). Toggles are exposed as
  `toggleX(enabled)` on the ViewModel.
- **Database.** Never bump the Room version without a `Migration`
  (`fallbackToDestructiveMigration` would wipe the library). New columns need a default in both the
  entity (`@ColumnInfo(defaultValue = …)`) and the `ALTER TABLE`. Add the migration to the
  migration test in `LibraryTest`.
- **Reviewed rules.** `SmartRule`, `ForYouRule` and `MusicForYouRule` implement rules the owner
  reviewed; their KDoc is the specification. Change them only with the owner's agreement, and keep their tests in step.
- **Gradle.** Keep the `signingConfigs` blocks in `app/build.gradle.kts` unchanged; AI Studio
  needs them. Unused dependencies are commented out rather than deleted. Java 17 and core library
  desugaring are required by NewPipe Extractor (it contains records and uses `java.time`).
- **Compose APIs.** Avoid experimental Material 3 APIs that changed between versions (the pull-to-
  refresh is built on nested scrolling for this reason). Inside a `Box` that is itself in a
  `Column`, call `androidx.compose.animation.AnimatedVisibility` fully qualified, or the
  `ColumnScope` overload is picked and fails to compile.

## Pitfalls (learned the hard way)

- **Injection timing.** Injecting `shield.js` from `onPageStarted`/`onPageFinished` is too late:
  YouTube has already read its ad schedule, so ads start and must be skipped (a 3–4 s delay).
  Keep the document-start registration.
- **Ad-state detection.** Do not watch the whole document with a `MutationObserver`, or poll
  quickly, to detect ads. YouTube mutates the DOM constantly; the old version burned CPU and
  reported hundreds of "ads blocked" for a single ad. Observe only `#movie_player`'s `class`.
- **Restore after a skipped ad.** Anything changed to skip an ad (muted, `playbackRate`) must be
  restored when `ad-showing` goes away.
- **Element blur.** Stop `blur` only when the target is `window`/`document`; element blur events
  (e.g. the search box) must still reach the page.
- **Foreground service rules.** Android 12+ forbids starting foreground services from the
  background. The ViewModel uses `startForegroundService` only for the first start; later updates
  use `startService`. Stopping goes through `ACTION_SHUTDOWN` so a just-started service can still
  call `startForeground` (Android 9–11 crash otherwise). Download workers fall back to normal
  background work when a foreground notification is not allowed.
- **Autoplay while hidden.** With the screen off nothing is drawn, so YouTube's "Up next"
  countdown never finishes (in PiP it is hidden). `shield.js` moves on itself when a video ends
  while the app is hidden: on m.youtube.com it opens the next video named by the watch-next
  response (keeps mix/playlist position; `nextVideo()` would leave the mix), on YouTube Music it
  calls the page's next track and opens the next song's page only if that did not work. The
  ViewModel keeps the notification service "playing" (foreground + wake lock) for up to 30 s
  between the two videos; reporting "paused" there let Android suspend the app and stopped the
  service from returning to the foreground. `holdThroughAutoplayGap()` must run on every state
  report (it also ends the gap when the next video plays); a skipped call left the gap stuck and
  broke every change after the first. Without the foreground service the app also loses network
  in Doze, which the emulator (plugged in) does not show: test with
  `adb shell dumpsys battery unplug` and `adb shell dumpsys deviceidle force-idle`. Settings → Diagnostics shows how the last autoplay
  went and how long the gap was.
- **Muted autoplay.** YouTube starts a video muted when its page was opened without a tap (the
  app's own loads, background autoplay). `shield.js` unmutes on `playing`/`volumechange` through
  `player.unMute()`, except during ads and after the user muted with a mute/volume control on
  the page (remembered per tab in sessionStorage); at most 5 times per video.
- **Feed previews / player page start.** The player page used to open on m.youtube.com's home
  page, which nobody sees; YouTube played the first feed item there as a muted preview, and
  once muted starts were unmuted that became hidden audio on app start. The player page now
  starts empty (`BrowserController` loads nothing until the first video or YouTube Music), and
  `shield.js` never unmutes outside a watch page. Refusing the previews' `play()` instead
  coincided with a ~30 s black start screen on a phone; do not reintroduce it.
- **PiP playback.** YouTube's player pauses the video when the window turns into PiP. Resuming
  with `video.play()` behind the player's back makes it pause again (audible stutter). Instead,
  `shield.js` refuses page `pause()` calls in PiP unless the user asked (`userPaused`), and resumes
  through the player API (`playVideo()`). Because that refusal would also swallow the user's own
  taps if the page wrongly thought it was in PiP, the page echoes its PiP flag (`cfgPip`) and the
  app re-sends its config when they differ. The controller also calls `webView.onResume()` /
  `resumeTimers()` on entering PiP. Settings → Diagnostics counts refused page pauses, browser
  pauses and stalls in PiP. The PiP stylesheet is the original app's; change it only with
  on-device testing.
- **Refused pauses leave YouTube "paused".** After refusing a page pause in PiP, call
  `player.playVideo()` (`resyncPlayer`): a player that believes it is paused stops downloading,
  which live streams (few seconds of buffer) hit within seconds.
- **m.youtube.com playlists.** The mobile player does not know the playlist (`getPlaylist()` is
  `[]`), so `player.nextVideo()` leaves a mix for an unrelated suggestion. The queue is in
  `singleColumnWatchNextResults.playlist.playlist` (no `nextVideo` key), and its `contents` can be a
  window of the playlist: find the current video by id, not by `currentIndex`.
- **Play/pause taps.** A user reported YouTube's on-video play/pause not reacting. The page
  script now applies a tap the page missed and counts it in Settings → Diagnostics with the
  element that was on top. A dialog hidden by hand must take its backdrop with it.
- **Slow video starts.** Settings → Diagnostics shows when the video data loaded and when playback
  started, time spent in the ad state and recently blocked requests. Ask for those numbers before
  changing ad-blocking rules.
  Known cause (2026-10-24): when YouTube scheduled a pre-roll (`adSlots` with
  `playerBytesAdLayoutRenderer`) and the shield strips it, the SABR media server answers the first
  `videoplayback` request with a 4000 ms backoff (UMP part 35) and no media, so playback starts
  ~5 s after the tap. The server enforces it: removing the backoff from the response makes the
  player retry and wait longer, and letting the ad play while fast-forwarding takes 13–22 s.
  Videos without a scheduled pre-roll start in ~1 s. Measure with `tools/device-probe`.
- **Layout after PiP/fullscreen.** YouTube sizes its player in JavaScript. After PiP or fullscreen
  transitions, call `nudgeLayout()` or the player stays cropped.
- **Two pages, one player.** Only the player page plays. The browse page refuses playback and
  hands video taps over; the player page hands channel, playlist and search links to the browse
  page. Keep the routing in `shield.js` and the URL fallbacks in both controllers in step.
- **Subscribe buttons.** YouTube's markup changes; on m.youtube the button sits about eight levels
  below the row with the channel link (see the `deepSub` test). Subscribe taps log
  `[StreamShield] subscribe tap {…}` with the button's ancestry to the console (logcat), which
  shows what to match when a channel cannot be identified.
- **Shorts.** RSS feeds link Shorts as `/shorts/…`; `feed_items.isShort` keeps them out of For you.
- **For you budget.** Each refresh fetches at most 6 related lists, 2 related-of-related lists,
  1 search and the watched channels' RSS feeds (6 h); loading more fetches 4 + 2. Every NewPipe
  stream request is a full YouTube page load, so keep these small. Lists are cached a day and
  used for a week (`for_you.json`). The Music tab's For you fetches at most 4 related lists per
  refresh (3 more when loading more) and the followed artists' RSS feeds (6 h); YouTube Music's
  queues come from the page for free (`onMusicQueue`).
- **YouTube Music's home shelves** (`recommend/YouTubeMusicHome.kt`) are InnerTube responses
  read by hand (shapes from the live site, October 2026). Continuations need the first
  response's `visitorData` as `X-Goog-Visitor-Id`, or YouTube returns an empty page. Loaded at
  most once an hour (and on pull-down), up to 6 pages.
- **"Use the app" prompts.** YouTube Music shows them after a page load (pull-to-refresh, coming
  back to the app, autoplay opening the next song's page). `dismissAppUpsell` finds them by the
  app button's label or by a small dialog's own text about the app and presses its own Not now /
  Close (else hides it). While the app is hidden it runs only when a prompt could hold playback
  (`playbackMayBeHeld`), to keep the background heartbeat cheap.
- **Like / Dislike buttons** follow the Subscribe pattern (capture-phase stop, sync state call).
  Comments have Like buttons too ("Like this comment …"); they must stay YouTube's.
- **Downloads.** Stream URLs expire and some need the client's user agent; `RangeFetcher` and
  `OfflineDownloader` handle that. Do not reintroduce placeholder or sample-video fallbacks.

## Verifying changes

1. `./gradlew testDebugUnitTest` and the shield.js tests pass.
2. For page-script or WebView changes, check on a device:
   - a pre-roll ad does not appear and the video starts immediately; feed and Shorts ads are
     hidden;
   - the on-video play/pause, the mini-player and the lock-screen controls all pause and play;
   - with the screen off, the next video of a mix and the next song in the Music tab start on
     their own (seek near the end, lock the phone, wait);
   - fullscreen enters and exits; PiP enters on Home, the player is not cropped after expanding,
     and closing the PiP window pauses;
   - a video opens with the slide and placeholder, Back and pull-down minimize it;
   - pull-to-refresh on Home, a search page and the Music tab (a playing song resumes);
   - Subscribe on a video page and a channel page shows "Subscribed".
3. For download changes: download a song (audio only) and a 720p video, play both offline.
