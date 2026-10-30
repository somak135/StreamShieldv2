# Device probe

Scripts for inspecting the app's YouTube page on a device or emulator over the WebView DevTools
protocol (see [docs/LOCAL_TESTING.md](../../docs/LOCAL_TESTING.md), section 4). Android WebView does
not support Playwright's `connectOverCDP` (`Browser.setDownloadBehavior` fails), so these talk raw
CDP over a WebSocket with Node 22's built-in `WebSocket`. No npm packages needed.

Forward the DevTools socket first:

```sh
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.aistudio.streamshield.vqxkpl)
```

| Script | What it does |
|---|---|
| `cdp.mjs <file.js \| -e code>` | Evaluates an async function body in the page, prints the JSON result. |
| `monitor.mjs` | Long-running: installs `init.js` at document start and logs requests as JSON lines (`ALL=1` for every request). |
| `init.js` | Page-side timeline in `window.__probe.ev`: media events, `ad-*` classes on `#movie_player`, SPA navigations. |
| `ump.mjs <seconds>` | Logs `videoplayback` (SABR) exchanges and decodes small UMP responses, e.g. a server backoff (part 35, field 4 = ms). |

Example: time from navigation start to first frame.

```sh
node monitor.mjs >> net.jsonl &
node cdp.mjs -e "const o = performance.timeOrigin; const e = window.__probe.ev.find(e => e.type === 'playing'); return e && e.t - o"
```
