# Local testing guide

How to build StreamShield in the Docker dev environment, and what an AI agent (or a person)
should test once there is real internet access, including YouTube. Read
[ARCHITECTURE.md](../ARCHITECTURE.md) and [AGENTS.md](../AGENTS.md) first.

`shield.js` is tested against **mock** YouTube pages in headless Chromium; the plan below checks
the real site on an emulator or phone. Earlier runs are in [test-reports/](test-reports/).

---

## 1. Start the container

**Linux (recommended).** `tools/docker-dev.sh` runs the container with rootless Docker: a
private daemon running as your user, started on demand as a transient systemd user unit. No
sudo, no `docker` group, nothing enabled at boot. It needs the `docker-ce-rootless-extras`
package (or your distribution's equivalent) and `pasta` or `slirp4netns`.

```sh
tools/docker-dev.sh build      # once; 5–15 min, about 6 GB (pass --build-arg INSTALL_EMULATOR=false to skip the emulator)
tools/docker-dev.sh shell      # repo at /workspace, KVM passed through; stops the daemon when you exit
tools/docker-dev.sh run './gradlew testDebugUnitTest'   # one command, then stop
tools/docker-dev.sh purge      # delete the image, caches and all rootless Docker data
```

The container runs as root, which in rootless Docker is your own user on the host, so files
written to the repository stay yours. Its `--network host` is the rootless daemon's network
namespace, not the host's: drive the emulator from inside the container (or forward adb to a
phone, below).

**Other setups.** Build the image yourself:

```sh
docker build -t streamshield-dev \
  --build-arg USER_UID=$(id -u) --build-arg USER_GID=$(id -g) .
# Apple Silicon / no emulator wanted:
docker build -t streamshield-dev --build-arg INSTALL_EMULATOR=false .
```

**Linux with KVM, rootful Docker.** The emulator works inside the container. `--network host`
makes the emulator reachable from the host, so you can watch it with `scrcpy`.

```sh
docker run -it --rm --name streamshield \
  --device /dev/kvm --network host \
  -v "$PWD":/workspace \
  -v streamshield-gradle:/home/dev/.gradle \
  streamshield-dev
```

**macOS / Windows (Docker Desktop has no KVM).** Run an emulator on the host, or use a phone, and
let the container use the host's adb server:

```sh
adb kill-server && adb -a nodaemon server start &      # on the host, listens on all interfaces
docker run -it --rm --name streamshield \
  -e ADB_SERVER_SOCKET=tcp:host.docker.internal:5037 \
  -v "$PWD":/workspace -v streamshield-gradle:/home/dev/.gradle \
  streamshield-dev
```

To use a phone over Wi-Fi from either setup, turn on Wireless debugging on the phone and run
`adb pair <ip>:<port>`, then `adb connect <ip>:<port>`.

To use Claude Code inside the container, pass your credentials (for example
`-e ANTHROPIC_API_KEY`) or log in with `claude` after starting.

## 2. First build

```sh
tools/dev-setup.sh                      # debug.keystore, local.properties, gradlew (once)
./gradlew assembleDebug testDebugUnitTest
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs
```

All three must pass before and after every change.

## 3. Emulator and app

```sh
emulator -avd streamshield -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
./gradlew installDebug
adb shell am start -n com.aistudio.streamshield.vqxkpl/com.example.MainActivity
```

- **App id:** `com.aistudio.streamshield.vqxkpl`. **Activity:** `com.example.MainActivity`.
- **Opening a video directly:**
  `adb shell am start -a android.intent.action.VIEW -d "https://m.youtube.com/watch?v=<id>"`.
- **Screenshots:** `adb exec-out screencap -p > /tmp/shot.png`.
- **UI tree:** `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/`.
- **Logs:** `adb logcat chromium:I *:S` shows the page's console output (`[INFO:CONSOLE…]`).
- **WebView version:** check with `adb shell dumpsys webviewupdate`. `shield.js` needs the
  document-start-script feature, which recent WebViews have; note the version in your report.
- **Audio:** `-no-audio` means you can't listen. Judge playback from the page state (section 4)
  and `adb shell dumpsys media_session`.

## 4. Look inside the YouTube page (CDP)

Debug builds allow WebView inspection (`BrowserController.configureWebView`). Forward the
DevTools socket and use the scripts in [tools/device-probe](../tools/device-probe/README.md).
Playwright's `connectOverCDP` does **not** work with Android WebView (it fails on
`Browser.setDownloadBehavior`), so they speak raw CDP:

```sh
PID=$(adb shell pidof com.aistudio.streamshield.vqxkpl)
adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
node tools/device-probe/cdp.mjs -e "const v = document.querySelector('video');
  return { url: location.href, config: window.__streamShield.getConfig(),
           paused: v.paused, time: v.currentTime, playerClass: document.getElementById('movie_player').className }"
```

The forward breaks when the app process restarts (new PID); run it again.

Useful page-side hooks (see `window.__streamShield` in `shield.js`):
- `__streamShield.getConfig()` and `__streamShield.reportState()`;
- `__streamShield.internals.nextByVideo`: what plays next, per video;
- `HTMLMediaElement` events: attach `pause` / `waiting` / `playing` listeners to count
  interruptions over time.

`tools/device-probe/monitor.mjs` (CDP `Network` domain) shows which requests the player is waiting
on, and `ump.mjs` decodes the SABR media responses. The app's own `shouldInterceptRequest` blocks
are listed in **Settings → Diagnostics**.

Testing tips from the first run (see [test-reports/2026-10-24.md](test-reports/2026-10-24.md)):
- A fresh emulator plays videos muted (YouTube's muted autoplay); that is not the shield.
- Full page loads (intents) and taps inside YouTube behave differently: measure start delay with
  taps on related videos, not only intents.
- Ads are not served on every load. Before concluding "no ad", check that YouTube scheduled one:
  `adPlacements` / `adSlots` in the raw player response, or the same test with the shield off.

**Without a device**, `shield.js` can be checked against the real site in desktop Chromium with
mobile emulation:
`chromium.launch()` → `newContext({ ...devices['Pixel 7'] })` →
`addInitScript(shield.js)` → `goto('https://m.youtube.com/watch?v=…')`.
This covers ad pruning and player hooks, but not Android behaviour (PiP, background, service).

## 5. What to test

Record pass/fail, numbers and screenshots for each item. Also run the device checks in
[AGENTS.md → Verifying changes](../AGENTS.md#verifying-changes), which cover the tabs, browse
page, Music, downloads, pull-to-refresh and Subscribe buttons.

### A. Ad blocking
1. **Pre-roll.** Open 10 different popular videos. No ad plays, and Diagnostics shows "Time in
   ad state: none".
2. **Mid-rolls and in-video ads** (passed on the emulator, 2026-10-24). Play a long video (>20 min) and seek past
   typical mid-roll points. No ad plays; if one does, record `#movie_player` classes and what
   `ad-showing` did.
3. **Feed, search, Shorts.** No promoted cards in the home feed or search results, and no ads
   while swiping through Shorts.
4. **No anti-adblock dialog** ("Ad blockers are not allowed") after 20+ videos.
5. **Start delay** (explained 2026-10-24: a 4 s server-side SABR backoff when a stripped pre-roll was
   scheduled; see the report and "Slow video starts" in AGENTS.md).
   - Compare "Video data loaded" and "Playback started" with the shield on and off, 5 videos each.
   - With CDP, find what happens between `loadedmetadata` and `playing`: requests the player
     waits on, and timers.
   - Compare with the same videos in Chrome on the same device.
   - If the gap is caused by the shield, find the rule responsible (try disabling network
     blocking and JSON pruning separately) before changing anything.
6. **Counters** go up once per ad and stay reasonable: no jumps of hundreds.

### B. Playback controls
1. Speed sheet: the speed applies immediately and persists to the next video. With "Lock speed"
   off, it resets to 1.0x.
2. Changing speed in YouTube's own menu is adopted by the app's speed pill.
3. SponsorBlock: on a video with known segments, the segment is skipped and a banner shows.

### C. Fullscreen
1. YouTube's fullscreen button: immersive mode; landscape for landscape videos; Back exits;
   the player is not cropped afterwards.

### D. Picture-in-picture
1. **Enter.** Press Home (`adb shell input keyevent KEYCODE_HOME`) while playing. PiP opens and
   the video keeps playing.
2. **No stutter.** In PiP, sample `currentTime` every 250 ms for 60 s, and count `pause` events.
   Expect zero pauses and steady progress.
3. **Live stream** (passed on the emulator, 2026-10-24). Play a live stream in PiP for 3 minutes; it must not stop every few
   seconds. Check "PiP: stalls" in Diagnostics (should stay near 0).
4. **Expand.** The page layout is restored and the player is not cropped. Take a screenshot; the
   player width equals the viewport width.
5. **Close** the PiP window: playback pauses.
6. **PiP play/pause button** works.

### E. Background play
1. Screen off (`adb shell input keyevent KEYCODE_SLEEP`): playback continues for 3+ minutes.
2. **Media controls:** `adb shell cmd media_session dispatch pause` / `play`, the notification
   buttons, and the lock screen all work, and the page state follows.
3. The notification's **Stop** pauses the video and removes the notification.
4. Turning background play off makes the app pause when it is hidden.
5. **Swiping the app from Recents** stops the service:
   `adb shell dumpsys activity services com.aistudio.streamshield.vqxkpl` shows none.

### F. Autoplay (mixes/playlists fixed and passed on the emulator, 2026-10-24)
1. **Mix in PiP:** open a mix (`list=RD…`), seek to 5 s before the end (CDP:
   `v.currentTime = v.duration - 5`), and enter PiP. Within ~6 s of the end, the next video of
   the mix plays, and `index` in the URL increases.
2. **Mix with the screen off:** the same, with the screen off.
3. **Playlist:** the same with a normal playlist (`list=PL…`).
4. **Single video,** the "Autoplay" setting on: the suggestion plays. With it off: nothing plays.
5. **App visible:** YouTube's own countdown runs and the app does not interfere.

### G. App behaviour
1. Rotate, switch dark mode and open/close Settings while playing: the video never reloads or
   stops.
2. Back on the root page while playing (background play on): the app goes to the background and
   keeps playing.
3. Deep links: `youtu.be/<id>` and `m.youtube.com/watch?v=<id>` intents open in the app.
4. Library: history, Watch later and a playlist; add, open and remove entries. Export a backup and
   import it again (Settings).

### H. Performance and battery
1. CPU while playing in the foreground, PiP and background, 5 minutes each:
   `adb shell top -b -n 1 | grep streamshield`. Note `shield.js` CPU via the CDP Performance
   domain (`Performance.getMetrics` → `ScriptDuration`).
2. Wake lock: held while playing, released within seconds of pausing
   (`adb shell dumpsys power | grep -i streamshield`).
3. No foreground service or notification remains after Stop or closing the app.

## 6. Reporting and follow-up

- Write findings to `docs/test-reports/YYYY-MM-DD.md` with:
  - the device or emulator, Android version and WebView version;
  - a pass/fail table for sections A–H;
  - measurements and screenshots.
- For each failure, find the cause before fixing it. Use the page hooks, CDP and logcat.
- **Fixes to `shield.js` must come with a case in `tools/shield-js-test/shield.test.mjs`**
  reproducing the YouTube behaviour you observed: the mock page or response shape.
- Record new YouTube quirks under "Pitfalls" in [AGENTS.md](../AGENTS.md).
