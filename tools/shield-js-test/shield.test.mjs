// Headless-Chromium tests for app/src/main/assets/shield.js against mock YouTube pages.
//
// Run:  NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs [path/to/clip.webm]
// Needs the `playwright` package and a Chromium build. If no clip is given, one is generated
// with ffmpeg (a 30 s VP8/Opus test pattern).

import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const here = path.dirname(fileURLToPath(import.meta.url));
const shieldJs = readFileSync(path.join(here, '../../app/src/main/assets/shield.js'), 'utf8');

let clipPath = process.argv[2];
if (!clipPath || !existsSync(clipPath)) {
    clipPath = path.join(mkdtempSync(path.join(tmpdir(), 'shield-')), 'clip.webm');
    execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=size=320x180:rate=15',
        '-f', 'lavfi', '-i', 'sine=frequency=440', '-t', '30', '-c:v', 'libvpx', '-b:v', '200k',
        '-c:a', 'libopus', '-shortest', clipPath]);
}
const clip = readFileSync(clipPath);

const PLAYER_RESPONSE_WITH_ADS = {
    responseContext: { serviceTrackingParams: [] },
    playabilityStatus: { status: 'OK' },
    streamingData: { formats: [] },
    videoDetails: { videoId: 'abc123def45', title: 'Test video', author: 'Test Channel', channelId: 'UCtestchannel000000000AA',
        lengthSeconds: '30', viewCount: '1234' },
    microformat: { playerMicroformatRenderer: { category: 'Music' } },
    adPlacements: [{ adPlacementRenderer: { kind: 'AD_PLACEMENT_KIND_START' } }],
    playerAds: [{ playerLegacyDesktopWatchAdsRenderer: {} }],
    adSlots: [{ adSlotRenderer: {} }],
    auxiliaryUi: { messageRenderers: {
        enforcementMessageViewModel: { title: 'Ad blockers are not allowed' },
        upsellDialogRenderer: { dialogMessage: 'Get the best experience' }
    } }
};

const BROWSE_RESPONSE_WITH_ADS = {
    responseContext: {},
    contents: {
        singleColumnBrowseResultsRenderer: {
            tabs: [{
                tabRenderer: {
                    content: {
                        richGridRenderer: {
                            contents: [
                                { richItemRenderer: { content: { videoRenderer: { videoId: 'real1' } } } },
                                { richItemRenderer: { content: { adSlotRenderer: { slotId: 'ad' } } } },
                                { itemSectionRenderer: { contents: [{ promotedSparklesWebRenderer: {} }] } },
                                { itemSectionRenderer: { contents: [{ videoWithContextRenderer: { videoId: 'real2' } }] } },
                                { richItemRenderer: { content: { videoRenderer: { videoId: 'real3' } } } }
                            ]
                        }
                    }
                }
            }]
        }
    }
};

function watchPage() {
    return `<!doctype html><html><head><title>Test video - YouTube</title>
<script>var ytInitialPlayerResponse = ${JSON.stringify(PLAYER_RESPONSE_WITH_ADS)};</script>
<script>
  window.__pageSaw = {
    adPlacements: ytInitialPlayerResponse.adPlacements,
    playerAds: ytInitialPlayerResponse.playerAds,
    enforcement: ytInitialPlayerResponse.auxiliaryUi.messageRenderers.enforcementMessageViewModel,
    upsell: ytInitialPlayerResponse.auxiliaryUi.messageRenderers.upsellDialogRenderer,
    videoId: ytInitialPlayerResponse.videoDetails.videoId
  };
  window.__visibilityEvents = 0;
  window.__inputBlurEvents = 0;
  window.__resizeEvents = 0;
  window.__skipAdCalls = 0;
  window.__nextCalls = 0;
  window.__prevCalls = 0;
  if (navigator.mediaSession) {
    navigator.mediaSession.setActionHandler('nexttrack', function () { window.__nextCalls++; });
    navigator.mediaSession.setActionHandler('previoustrack', function () { window.__prevCalls++; });
  }
  document.addEventListener('visibilitychange', function () { window.__visibilityEvents++; });
  window.addEventListener('resize', function () { window.__resizeEvents++; });
</script>
</head><body>
<ytm-mobile-topbar-renderer>
  <a id="topbarLogo" href="/">YouTube</a>
  <button id="topbarSearch" aria-label="Search YouTube"></button>
  <ytm-button-renderer id="openAppRenderer"><button>Open App</button></ytm-button-renderer>
  <ytm-button-renderer id="signInRenderer"><a href="/signin">Sign in</a></ytm-button-renderer>
</ytm-mobile-topbar-renderer>
<div id="movie_player" class="html5-video-player playing-mode">
  <video src="/clip.webm" playsinline></video>
</div>
<div class="slim-video-information-title">Test video</div>
<div class="slim-owner-icon-and-title"><div class="subhead">Test Channel</div></div>
<ytm-subscribe-button-renderer><button id="subBtn" aria-label="Subscribe to Test Channel."><span id="subLabel">Subscribe</span></button></ytm-subscribe-button-renderer>
<button id="subsTab">Subscriptions</button>
<ytm-promoted-sparkles-web-renderer id="feedAd">Sponsored</ytm-promoted-sparkles-web-renderer>
<ytm-rich-item-renderer id="realItem">Real video</ytm-rich-item-renderer>
<ytm-pivot-bar-renderer id="pivotBar">Home Shorts Subscriptions You</ytm-pivot-bar-renderer>
<a id="videoLink" href="/watch?v=other123456"><span id="videoLinkTitle">Other video</span></a>
<a id="channelLink" href="/@testchannel">Test Channel</a>
<form id="searchForm" action="/results"><input id="ytSearch" class="searchbox-input" name="search_query" placeholder="Search YouTube"></form>
<input id="search"><button id="other">x</button>
<script>document.getElementById('search').addEventListener('blur', function () { window.__inputBlurEvents++; });
document.getElementById('movie_player').skipAd = function () { window.__skipAdCalls++; };
window.__signIn = 0; window.__subsTab = 0;
document.getElementById('subBtn').addEventListener('click', function () { window.__signIn++; });
document.getElementById('subsTab').addEventListener('click', function () { window.__subsTab++; });</script>
</body></html>`;
}

function bridgeInit(config) {
    return `
    window.__bridgeLog = [];
    window.StreamShieldBridge = {
      getConfig: function () { return ${JSON.stringify(JSON.stringify(config))}; },
      onAdBlocked: function (kind) { window.__bridgeLog.push(['ad', kind]); },
      onState: function (json) { window.__bridgeLog.push(['state', JSON.parse(json)]); },
      onSpeedChanged: function (s) { window.__bridgeLog.push(['speed', s]); },
      onSponsorSkipped: function (s) { window.__bridgeLog.push(['sponsor', s]); },
      onHomeLogo: function (u) { window.__bridgeLog.push(['home', u]); },
      onOpenVideo: function (u) { window.__bridgeLog.push(['openVideo', u]); },
      onOpenBrowse: function (u) { window.__bridgeLog.push(['openBrowse', u]); },
      onSubscribeTap: function (j) { window.__bridgeLog.push(['subscribeTap', JSON.parse(j)]); },
      isSubscribed: function (j) {
        var k = JSON.parse(j);
        return !!(window.__subscribedKeys && (window.__subscribedKeys[k.id] || window.__subscribedKeys[k.path]));
      },
      onPull: function (s) { window.__bridgeLog.push(['pull', s]); },
      onPullEnd: function (j) { window.__bridgeLog.push(['pullEnd', JSON.parse(j)]); },
      onResumeResult: function (s) { window.__bridgeLog.push(['resume', s]); },
      onLikeTap: function (j) { window.__bridgeLog.push(['likeTap', JSON.parse(j)]); },
      onMusicQueue: function (j) { window.__bridgeLog.push(['musicQueue', JSON.parse(j)]); },
      likeState: function (id) { return (window.__likeStates && window.__likeStates[id]) || ''; }
    };`;
}

let failures = 0;
let passes = 0;
function check(name, condition, detail) {
    if (condition) {
        passes++;
        console.log(`  ok   ${name}`);
    } else {
        failures++;
        console.log(`  FAIL ${name}${detail !== undefined ? ' -> ' + JSON.stringify(detail) : ''}`);
    }
}

const browser = await chromium.launch({
    args: ['--autoplay-policy=no-user-gesture-required']
});

// Watch-next responses (/youtubei/v1/next?kind=...) for the autoplay-while-hidden tests.
function nextResponse(kind) {
    const next = { watchEndpoint: { videoId: 'nextVid0001', playlistId: 'RDabc', index: 3 } };
    const item = (videoId, index, list) => ({ playlistPanelVideoRenderer: { videoId,
        navigationEndpoint: { watchEndpoint: { videoId, playlistId: list, index } } } });
    if (kind === 'music-radio') {
        // A YouTube Music radio as the live site sends it: titles, bylines (artist with its
        // channel, album, year), lengths; started from seedSong001, now playing radioSong02.
        const song = (videoId, n, selected) => ({ playlistPanelVideoRenderer: {
            videoId, selected: !!selected,
            title: { runs: [{ text: 'Song ' + n }] },
            shortBylineText: { runs: [{ text: 'Artist ' + n, navigationEndpoint: { browseEndpoint: { browseId: 'UCartist' + String(n).padStart(16, '0') } } }] },
            longBylineText: { runs: [{ text: 'Artist ' + n }, { text: ' \u2022 ' }, { text: 'Album' }, { text: ' \u2022 ' }, { text: '2021' }] },
            lengthText: { runs: [{ text: n === 3 ? '1:02:03' : '3:4' + n }] },
            navigationEndpoint: { watchEndpoint: { videoId, playlistId: 'RDAMVMseedSong001', index: n } } } });
        const wrapped = (videoId, n) => ({ playlistPanelVideoWrapperRenderer: { primaryRenderer: song(videoId, n) } });
        return { responseContext: {}, contents: { singleColumnMusicWatchNextResultsRenderer: { tabbedRenderer: {
            watchNextTabbedResultsRenderer: { tabs: [{ tabRenderer: { content: { musicQueueRenderer: { content: {
                playlistPanelRenderer: { playlistId: 'RDAMVMseedSong001', contents: [
                    song('seedSong001', 0), wrapped('radioSong01', 1), song('radioSong02', 2, true),
                    song('radioSong03', 3), song('radioSong01', 1), song('radioSong04', 4)] }
                } } } } }] } } } } };
    }
    if (kind === 'music') {
        // YouTube Music: the queue in a playlistPanelRenderer, songs wrapped as song/video pairs,
        // the current song marked "selected", and (here) no currentVideoEndpoint.
        const wrap = (videoId, index) => ({ playlistPanelVideoWrapperRenderer: { primaryRenderer: item(videoId, index, 'RDAMVMabc') } });
        const current = item('abc123def45', 1, 'RDAMVMabc');
        current.playlistPanelVideoRenderer.selected = true;
        return { responseContext: {}, contents: { singleColumnMusicWatchNextResultsRenderer: { tabbedRenderer: {
            watchNextTabbedResultsRenderer: { tabs: [{ tabRenderer: { content: { musicQueueRenderer: { content: {
                playlistPanelRenderer: { playlistId: 'RDAMVMabc', contents: [wrap('prevSong001', 0), current, wrap('nextSong001', 2)] }
            } } } } }] } } } } };
    }
    let results;
    if (kind === 'mweb-mix') {
        // m.youtube.com (shape seen on the live site): the queue is in playlist.playlist and the
        // autoplay sets carry only autoplayVideo.
        results = {
            playlist: { playlist: { playlistId: 'RDabc', currentIndex: 2, isInfinite: true,
                contents: [item('prevVid0001', 1, 'RDabc'), item('abc123def45', 2, 'RDabc'), item('nextVid0001', 3, 'RDabc')] } },
            autoplay: { autoplay: { sets: [{ autoplayVideo: next }] } }
        };
    } else {
        const sets = kind === 'suggestion'
            ? [{ autoplayVideo: { watchEndpoint: { videoId: 'suggVid0001' } } }]
            : [{ nextVideo: next, autoplayVideo: next }];
        results = { autoplay: { autoplay: { sets } } };
    }
    return { responseContext: {}, currentVideoEndpoint: { watchEndpoint: { videoId: 'abc123def45' } },
        contents: { singleColumnWatchNextResults: results } };
}

async function openWatchPage(config = {}, startUrl = 'https://m.youtube.com/watch?v=abc123def45') {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.route('**/*', async (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/clip.webm') {
            // Range support so the media element can seek.
            const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers()['range'] || '');
            if (range) {
                const start = Number(range[1]);
                const end = range[2] ? Number(range[2]) : clip.length - 1;
                return route.fulfill({
                    status: 206,
                    headers: {
                        'Content-Type': 'video/webm',
                        'Accept-Ranges': 'bytes',
                        'Content-Range': `bytes ${start}-${end}/${clip.length}`
                    },
                    body: clip.subarray(start, end + 1)
                });
            }
            return route.fulfill({ status: 200, headers: { 'Content-Type': 'video/webm', 'Accept-Ranges': 'bytes' }, body: clip });
        }
        if (url.pathname === '/youtubei/v1/player') {
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PLAYER_RESPONSE_WITH_ADS) });
        }
        if (url.pathname === '/youtubei/v1/get_watch') {
            // In-app navigation: a batch of wrappers (player + up-next data).
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
                { playerResponse: PLAYER_RESPONSE_WITH_ADS },
                { response: BROWSE_RESPONSE_WITH_ADS }
            ]) });
        }
        if (url.pathname === '/youtubei/v1/browse') {
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(BROWSE_RESPONSE_WITH_ADS) });
        }
        if (url.pathname === '/youtubei/v1/next') {
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(nextResponse(url.searchParams.get('kind'))) });
        }
        // '/': YouTube's home page (the mock's video stands for a feed preview).
        if (url.pathname === '/watch' || url.pathname === '/') {
            return route.fulfill({ status: 200, contentType: 'text/html', body: watchPage() });
        }
        return route.fulfill({ status: 404, body: '' });
    });
    await page.addInitScript(bridgeInit({ adBlock: true, bgPlay: true, speed: 1.0, pip: false, sponsorSkip: true, ...config }));
    await page.addInitScript(shieldJs);
    await page.goto(startUrl);
    await page.waitForFunction(() => {
        const v = document.querySelector('video');
        return v && v.readyState >= 1 && isFinite(v.duration);
    });
    return { context, page };
}

const adEvents = (page) => page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'ad').map((e) => e[1]));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------------------------
console.log('JSON pruning');
{
    const { context, page } = await openWatchPage();
    const saw = await page.evaluate(() => window.__pageSaw);
    check('inline ytInitialPlayerResponse has no adPlacements when the page reads it', saw.adPlacements === undefined, saw);
    check('inline ytInitialPlayerResponse has no playerAds', saw.playerAds === undefined);
    check('anti-adblock enforcement message removed', saw.enforcement === undefined);
    check('"Watch in YouTube app" dialog data removed', saw.upsell === undefined);
    check('real player data untouched', saw.videoId === 'abc123def45');
    check('prune reported once for the inline response', (await adEvents(page)).filter((k) => k === 'prune').length === 1, await adEvents(page));

    const fetched = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/player', { method: 'POST' });
        const data = await r.json();
        return { ads: data.adPlacements, slots: data.adSlots, status: data.playabilityStatus.status };
    });
    check('fetch().json() player response pruned', fetched.ads === undefined && fetched.slots === undefined && fetched.status === 'OK', fetched);

    const viaText = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/player', { method: 'POST' });
        const data = JSON.parse(await r.text());
        return { ads: data.adPlacements, ads2: data.playerAds };
    });
    check('JSON.parse(text) player response pruned', viaText.ads === undefined && viaText.ads2 === undefined, viaText);

    const feed = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/browse', { method: 'POST' });
        const data = await r.json();
        const items = data.contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.richGridRenderer.contents;
        return items.map((i) => JSON.stringify(i));
    });
    check('feed ad cells removed, real videos kept', feed.length === 3 && feed.every((s) => /real\d/.test(s)), feed);

    const shorts = await page.evaluate(() => {
        const text = JSON.stringify({
            responseContext: {},
            entries: [
                { command: { reelWatchEndpoint: { videoId: 'short1' } } },
                { command: { reelWatchEndpoint: { videoId: 'adShort', adClientParams: { isAd: true } } } },
                { command: { reelWatchEndpoint: { videoId: 'short2' } } }
            ]
        });
        return JSON.parse(text).entries.map((e) => e.command.reelWatchEndpoint.videoId);
    });
    check('Shorts sequence ad entries removed', shorts.join(',') === 'short1,short2', shorts);

    const unrelated = await page.evaluate(() => {
        const o = JSON.parse('{"adPlacements":[1],"other":true,"padding":"' + 'x'.repeat(80) + '"}');
        return o.adPlacements;
    });
    check('unrelated JSON (no responseContext) is not modified', Array.isArray(unrelated) && unrelated.length === 1, unrelated);

    const parseString = await page.evaluate(() => JSON.parse('"plain"') === 'plain' && JSON.parse('[1,2]').length === 2);
    check('JSON.parse still behaves for primitives/arrays', parseString);

    const cosmetic = await page.evaluate(() => ({
        ad: getComputedStyle(document.getElementById('feedAd')).display,
        real: getComputedStyle(document.getElementById('realItem')).display
    }));
    check('cosmetic filter hides promoted renderer', cosmetic.ad === 'none', cosmetic);
    check('cosmetic filter leaves real items visible', cosmetic.real !== 'none', cosmetic);

    const topbar = await page.evaluate(() => ({
        openApp: getComputedStyle(document.getElementById('openAppRenderer')).display,
        search: getComputedStyle(document.getElementById('topbarSearch')).display,
        logo: getComputedStyle(document.getElementById('topbarLogo')).display
    }));
    check("YouTube's Open App button is hidden", topbar.openApp === 'none', topbar);
    check('Sign in button in the top bar is hidden',
        await page.evaluate(() => getComputedStyle(document.getElementById('signInRenderer')).display) === 'none');
    check('other top bar buttons stay visible', topbar.search !== 'none' && topbar.logo !== 'none', topbar);

    // YouTube re-renders the top bar on navigation: a re-created button is hidden again.
    await page.evaluate(() => {
        const r = document.createElement('ytm-button-renderer');
        r.id = 'openAppAgain';
        r.innerHTML = '<button aria-label="Open App"><span></span></button>';
        document.querySelector('ytm-mobile-topbar-renderer').appendChild(r);
        window.dispatchEvent(new Event('yt-navigate-finish'));
    });
    await sleep(200);
    const again = await page.evaluate(() => getComputedStyle(document.getElementById('openAppAgain')).display);
    check('re-created Open App button (aria-label) hidden after navigation', again === 'none', again);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Response shapes (in-app navigation, XHR)');
{
    const { context, page } = await openWatchPage();
    const batch = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/get_watch', { method: 'POST' });
        const data = await r.json();
        const items = data[1].response.contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.richGridRenderer.contents;
        return { ads: data[0].playerResponse.adPlacements, slots: data[0].playerResponse.adSlots,
            status: data[0].playerResponse.playabilityStatus.status, cells: items.length };
    });
    check('batched get_watch: wrapped player response pruned', batch.ads === undefined && batch.slots === undefined && batch.status === 'OK', batch);
    check('batched get_watch: up-next ad cells removed', batch.cells === 3, batch);

    const viaParse = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/get_watch', { method: 'POST' });
        const data = JSON.parse(await r.text());
        return { ads: data[0].playerResponse.adPlacements, ads2: data[0].playerResponse.playerAds };
    });
    check('batched get_watch via JSON.parse pruned', viaParse.ads === undefined && viaParse.ads2 === undefined, viaParse);

    const wrapper = await page.evaluate(() => {
        const text = JSON.stringify({ page: 'watch', playerResponse: { playabilityStatus: { status: 'OK' }, adPlacements: [1], responseContext: {} } });
        return JSON.parse(text).playerResponse.adPlacements;
    });
    check('wrapper object {page, playerResponse} pruned', wrapper === undefined, wrapper);

    const xhr = await page.evaluate(() => new Promise((resolve) => {
        const x = new XMLHttpRequest();
        x.open('POST', '/youtubei/v1/player');
        x.responseType = 'json';
        x.onload = () => resolve({ ads: x.response.adPlacements, slots: x.response.adSlots, status: x.response.playabilityStatus.status });
        x.send();
    }));
    check("XHR responseType 'json' player response pruned", xhr.ads === undefined && xhr.slots === undefined && xhr.status === 'OK', xhr);

    const xhrText = await page.evaluate(() => new Promise((resolve) => {
        const x = new XMLHttpRequest();
        x.open('POST', '/youtubei/v1/player');
        x.onload = () => resolve(JSON.parse(x.responseText).adPlacements);
        x.send();
    }));
    check('XHR text + JSON.parse pruned', xhrText === undefined, xhrText);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('17 s player timer');
{
    const { context, page } = await openWatchPage();
    const boosted = await page.evaluate(() => new Promise((resolve) => {
        const t0 = performance.now();
        const done = function () { resolve(performance.now() - t0); };
        setTimeout(done.bind(null), 17000);   // bound function: toString() is native code
    }));
    check('17000 ms timer with a native-code callback fires almost at once', boosted < 1000, boosted);
    const untouched = await page.evaluate(() => new Promise((resolve) => {
        let fired = false;
        const id = setTimeout(function () { fired = true; }, 17000);
        const other = setTimeout(function () {}.bind(null), 16999);
        setTimeout(() => { clearTimeout(id); clearTimeout(other); resolve(fired); }, 400);
    }));
    check('other 17 s timers (page code, other delays) keep their delay', untouched === false, untouched);
    await page.evaluate(() => window.__streamShield.reportState());
    const state = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'state').pop()[1]);
    check('boosted timers are reported in the state', state.boosted >= 1, state.boosted);
    await context.close();
}
{
    const { context, page } = await openWatchPage({ adBlock: false });
    const plain = await page.evaluate(() => new Promise((resolve) => {
        let fired = false;
        const id = setTimeout(function () { fired = true; }.bind(null), 17000);
        setTimeout(() => { clearTimeout(id); resolve(fired); }, 400);
    }));
    check('with the ad shield off, the timer is left alone', plain === false, plain);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('App upsell dialog');
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => {
        const v = document.querySelector('video');
        v.pause();
        v.currentTime = 0;
        const d = document.createElement('div');
        d.id = 'upsell';
        d.style.cssText = 'position:fixed;top:20px;left:20px;width:200px;height:200px;background:#333';
        d.innerHTML = '<div><button id="upsellClose" aria-label="Close">x</button></div>' +
            '<div><p>Get the best experience</p><button id="upsellWatch"><span>Watch in YouTube app</span></button></div>';
        d.querySelector('#upsellClose').addEventListener('click', () => d.remove());
        document.body.appendChild(d);
        window.dispatchEvent(new Event('yt-navigate-finish'));
    });
    await sleep(700);
    const gone = await page.evaluate(() => !document.getElementById('upsell'));
    check("'Watch in YouTube app' dialog closed with its own close button", gone);
    const playing = await page.evaluate(() => !document.querySelector('video').paused);
    check('video starts after the dialog is closed', playing);
    const counted = (await adEvents(page)).filter((k) => k !== 'prune').length;
    check('closing the dialog is not counted as a blocked ad', counted === 0, await adEvents(page));

    await page.evaluate(() => {
        const d = document.createElement('div');
        d.id = 'upsell2';
        d.setAttribute('role', 'dialog');
        d.style.cssText = 'position:fixed;top:20px;left:20px;width:200px;height:200px;background:#333';
        d.innerHTML = '<a id="upsellLink" href="/app">Open in YouTube app</a>';
        document.body.appendChild(d);
        window.dispatchEvent(new Event('yt-navigate-finish'));
    });
    await sleep(300);
    const hidden = await page.evaluate(() => getComputedStyle(document.getElementById('upsell2')).display);
    check('dialog without a close button is hidden', hidden === 'none', hidden);

    // Other wordings: an "Open App" button with "Not now", and a prompt known by its text.
    const addPrompt = (html, attrs = {}) => page.evaluate(({ h, a }) => {
        const d = document.createElement(a.tag || 'div');
        d.id = a.id;
        if (a.role) d.setAttribute('role', a.role);
        d.style.cssText = 'position:fixed;top:20px;left:20px;width:200px;height:200px;background:#333';
        d.innerHTML = h;
        d.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => {
            window.__promptClicks = (window.__promptClicks || []).concat(b.id);
            if (b.dataset.closes) d.remove();
        }));
        document.body.appendChild(d);
        window.dispatchEvent(new Event('yt-navigate-finish'));
    }, { h: html, a: attrs });
    await addPrompt('<p>Listen with the app</p><button id="openApp">Open App</button><button id="notNow" data-closes="1">Not now</button>',
        { id: 'upsell3', tag: 'tp-yt-paper-dialog' });
    await sleep(300);
    check("'Open App' prompt closed with its own 'Not now'",
        await page.evaluate(() => !document.getElementById('upsell3') && window.__promptClicks.join() === 'notNow'),
        await page.evaluate(() => window.__promptClicks));
    await addPrompt('<p>Keep listening in the YouTube Music app</p><button id="getIt">Open</button><button id="noThanks" data-closes="1">No thanks</button>',
        { id: 'upsell4', role: 'dialog' });
    await sleep(300);
    check('prompt about the YouTube Music app closed by its text ("Open" button)',
        await page.evaluate(() => !document.getElementById('upsell4') && !window.__promptClicks.includes('getIt')));
    await addPrompt('<p>Sign in to like videos</p><button id="signInBtn">Sign in</button><button id="cancelBtn" data-closes="1">Cancel</button>',
        { id: 'notUpsell', role: 'dialog' });
    await sleep(300);
    check('a dialog that is not about the app is left alone',
        await page.evaluate(() => !!document.getElementById('notUpsell') &&
            getComputedStyle(document.getElementById('notUpsell')).display !== 'none' && !window.__promptClicks.includes('cancelBtn')));
    check("the top bar's 'Open App' button is not pressed", await page.evaluate(() => !window.__promptClicks.includes('openAppRenderer')));
    await context.close();
}
{
    // App hidden: a prompt that appeared while the page was not drawn and holds playback.
    const { context, page } = await openWatchPage({ appVisible: false });
    const addHeldPrompt = (id) => page.evaluate((pid) => {
        const v = document.querySelector('video');
        v.currentTime = 0;
        v.pause();                                    // the page pauses (not the user)
        const d = document.createElement('div');
        d.id = pid;
        d.setAttribute('role', 'dialog');
        d.style.cssText = 'position:fixed;top:20px;left:20px;width:200px;height:200px;background:#333';
        d.innerHTML = '<p>Get the app</p><button>Open YouTube Music</button><button class="x" aria-label="Close">x</button>';
        d.querySelector('.x').addEventListener('click', () => d.remove());
        document.body.appendChild(d);
        window.dispatchEvent(new Event('yt-navigate-finish'));
    }, id);
    await addHeldPrompt('heldPrompt');
    await sleep(700);
    check('app hidden: a prompt holding playback is closed', await page.evaluate(() => !document.getElementById('heldPrompt')));
    check('app hidden: playback continues after it', await page.evaluate(() => !document.querySelector('video').paused));
    await page.evaluate(() => window.__streamShield.pause());
    await addHeldPrompt('pausedPrompt');
    await sleep(700);
    check('app hidden, paused by the user: the page is left alone',
        await page.evaluate(() => !!document.getElementById('pausedPrompt') && document.querySelector('video').paused));
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Start-up timing for in-page navigation');
{
    const { context, page } = await openWatchPage();
    const r = await page.evaluate(async () => {
        document.getElementById('other').click();          // the user's tap
        const tapAt = Date.now();
        await new Promise((res) => setTimeout(res, 400));  // YouTube fetches the next video's data
        JSON.parse(JSON.stringify({ responseContext: {}, playabilityStatus: { status: 'OK' }, adPlacements: [1, 2] }));
        await new Promise((res) => setTimeout(res, 100));
        history.pushState({}, '', '/watch?v=zzzzzzzzzzz');
        const d = window.__streamShield.internals.diag();
        return { videoId: d.videoId, sinceTap: d.navAt - tapAt, pruned: d.pruned };
    });
    check('in-page navigation: timing starts at the tap', r.videoId === 'zzzzzzzzzzz' && Math.abs(r.sinceTap) < 50, r);
    check('in-page navigation: ads removed after the tap count for the new video', r.pruned === 1, r);
    const same = await page.evaluate(() => {
        const before = window.__streamShield.internals.diag();
        history.replaceState({}, '', '/watch?v=zzzzzzzzzzz&t=5');
        return window.__streamShield.internals.diag() === before;
    });
    check('URL change within the same video keeps its timing', same);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Player page / browse page');
{
    const { context, page } = await openWatchPage({ role: 'browse' });
    const before = page.url();
    await page.click('#videoLinkTitle');
    await sleep(200);
    const opened = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'openVideo').map((e) => e[1]));
    check('browse page: a video tap is handed to the player page', opened.length === 1 && opened[0] === 'https://m.youtube.com/watch?v=other123456', opened);
    check('browse page: the page itself does not navigate', page.url() === before, page.url());
    const refused = await page.evaluate(() => document.querySelector('video').play().then(() => 'played', (e) => e.name));
    check('browse page: nothing plays', refused === 'NotAllowedError', refused);
    await page.evaluate(() => window.__streamShield.reportState());
    const states = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'state').length);
    check('browse page: no playback state is reported', states === 0, states);
    const visible = await page.evaluate(() => document.hidden);
    check('browse page: no background-play visibility override', visible === false);
    await context.close();
}
{
    const { context, page } = await openWatchPage();
    const before = page.url();
    await page.click('#channelLink');
    await sleep(200);
    const opened = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'openBrowse').map((e) => e[1]));
    check('player page: a channel tap opens the browse page', opened.length === 1 && opened[0] === 'https://m.youtube.com/@testchannel', opened);
    check('player page: the video page stays', page.url() === before, page.url());
    await page.fill('#ytSearch', 'lofi beats');
    await page.press('#ytSearch', 'Enter');
    await sleep(200);
    const searched = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'openBrowse').map((e) => e[1]));
    check('player page: a search opens the results in the browse page',
        searched.length === 2 && searched[1] === 'https://m.youtube.com/results?search_query=lofi%20beats', searched);
    check('player page: still on the video page after the search', page.url() === before, page.url());
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Background heartbeat');
{
    const { context, page } = await openWatchPage({ appVisible: false });
    await page.evaluate(() => {
        const r = document.createElement('ytm-button-renderer');
        r.id = 'openAppLater';
        r.innerHTML = '<button>Open App</button>';
        document.querySelector('ytm-mobile-topbar-renderer').appendChild(r);
        window.__bridgeLog.length = 0;
        document.querySelector('video').play();
    });
    await sleep(1800);
    const inBackground = await page.evaluate(() => ({
        hidden: getComputedStyle(document.getElementById('openAppLater')).display === 'none',
        states: window.__bridgeLog.filter((e) => e[0] === 'state').length
    }));
    check('app in background: page tidying is skipped', inBackground.hidden === false, inBackground);
    check('app in background: playback state is still reported', inBackground.states > 0, inBackground);
    await page.evaluate(() => window.__streamShield.setConfig({ appVisible: true }));
    await sleep(100);
    const back = await page.evaluate(() => getComputedStyle(document.getElementById('openAppLater')).display);
    check('back on screen: the page is tidied at once', back === 'none', back);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('App navigation hooks');
{
    const { context, page } = await openWatchPage();
    const pivot = await page.evaluate(() => getComputedStyle(document.getElementById('pivotBar')).display);
    check("m.youtube's own bottom bar is hidden", pivot === 'none', pivot);
    const before = page.url();
    await page.click('#topbarLogo');
    await sleep(200);
    const home = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'home').length);
    check('YouTube logo tap goes to the app (bridge call)', home === 1, home);
    check('YouTube logo tap does not navigate the page', page.url() === before, page.url());
    await page.click('#topbarSearch');
    await sleep(100);
    const home2 = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'home').length);
    check('other top bar buttons are not intercepted', home2 === 1, home2);
    await page.evaluate(() => {
        history.pushState({}, '', '/');
        window.dispatchEvent(new Event('yt-navigate-finish'));
    });
    await sleep(200);
    const home3 = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'home').length);
    check('in-page navigation to the YouTube home page goes to the app', home3 === 2, home3);
    await page.evaluate(() => window.dispatchEvent(new Event('yt-navigate-finish')));
    await sleep(200);
    const home4 = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'home').length);
    check('...reported once, not on every check', home4 === 2, home4);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Ad fallback (ad still starts)');
{
    const { context, page } = await openWatchPage({ speed: 1.25 });
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    const before = await page.evaluate(() => {
        const v = document.querySelector('video');
        return { muted: v.muted, rate: v.playbackRate };
    });
    check('content plays at the configured speed', Math.abs(before.rate - 1.25) < 0.01 && before.muted === false, before);

    await page.evaluate(() => {
        const p = document.getElementById('movie_player');
        p.classList.add('ad-showing');
        // YouTube keeps flipping other classes while an ad plays; must not re-count.
        for (let i = 0; i < 20; i++) p.classList.toggle('ytp-autohide');
    });
    await sleep(150);
    const during = await page.evaluate(() => {
        const v = document.querySelector('video');
        return { muted: v.muted, rate: v.playbackRate, t: v.currentTime, d: v.duration };
    });
    check('ad is muted', during.muted === true, during);
    check("player's own skipAd() is used", (await page.evaluate(() => window.__skipAdCalls)) > 0);
    check('ad is fast-forwarded to its end', during.d - during.t < 0.5, during);
    const skips = (await adEvents(page)).filter((k) => k === 'skip').length;
    check('one ad counts once despite many class mutations', skips === 1, skips);

    await page.evaluate(() => {
        const p = document.getElementById('movie_player');
        p.classList.remove('ad-showing');
        const v = document.querySelector('video');
        v.currentTime = 1;
        v.play();
    });
    await sleep(300);
    const after = await page.evaluate(() => {
        const v = document.querySelector('video');
        return { muted: v.muted, rate: v.playbackRate };
    });
    check('sound restored after the ad', after.muted === false, after);
    check('speed restored after the ad', Math.abs(after.rate - 1.25) < 0.01, after);
    const callsAfterAd = await page.evaluate(() => window.__skipAdCalls);
    await sleep(400);
    check('ad nudging stops once the ad is over', (await page.evaluate(() => window.__skipAdCalls)) === callsAfterAd);
    const adDiag = await page.evaluate(() => { window.__streamShield.reportState(); return window.__bridgeLog.filter((e) => e[0] === 'state').pop()[1]; });
    check('diagnostics record time spent in the ad state', adDiag.adMs >= 100, adDiag);

    await page.evaluate(() => window.__streamShield.setConfig({ adBlock: false }));
    await page.evaluate(() => document.getElementById('movie_player').classList.add('ad-showing'));
    await sleep(150);
    const disabled = await page.evaluate(() => document.querySelector('video').muted);
    check('with ad-block off, ads are left alone', disabled === false, disabled);
    const cosmeticOff = await page.evaluate(() => getComputedStyle(document.getElementById('feedAd')).display);
    check('with ad-block off, cosmetic filter removed', cosmeticOff !== 'none', cosmeticOff);
    await context.close();
}

// The player element re-created while an ad shows (YouTube does this on some navigations): the
// skip timer must stop and sound/speed come back, or the next videos get skipped muted.
{
    const { context, page } = await openWatchPage({ speed: 1.25 });
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    await page.evaluate(() => document.getElementById('movie_player').classList.add('ad-showing'));
    await sleep(150);
    await page.evaluate(() => {
        const old = document.getElementById('movie_player');
        const fresh = document.createElement('div');
        fresh.id = 'movie_player';
        fresh.className = 'html5-video-player';
        fresh.appendChild(document.querySelector('video'));
        old.replaceWith(fresh);
        window.dispatchEvent(new Event('yt-navigate-finish'));   // re-hooks the player now
    });
    await sleep(300);
    await page.evaluate(() => {
        const v = document.querySelector('video');
        v.currentTime = 1;
        v.play();
    });
    await sleep(500);
    const swapped = await page.evaluate(() => {
        const v = document.querySelector('video');
        return { muted: v.muted, rate: v.playbackRate, t: v.currentTime, d: v.duration };
    });
    check('player swapped mid-ad: sound restored', swapped.muted === false, swapped);
    check('player swapped mid-ad: speed restored', Math.abs(swapped.rate - 1.25) < 0.01, swapped);
    check('player swapped mid-ad: content is not fast-forwarded', swapped.d - swapped.t > 1, swapped);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Background play');
{
    const { context, page } = await openWatchPage({ bgPlay: true });
    const res = await page.evaluate(() => {
        document.dispatchEvent(new Event('visibilitychange'));
        window.dispatchEvent(new Event('blur'));
        const search = document.getElementById('search');
        search.focus();
        document.getElementById('other').focus();
        return {
            hidden: document.hidden,
            state: document.visibilityState,
            vis: window.__visibilityEvents,
            inputBlur: window.__inputBlurEvents
        };
    });
    check('document reports visible', res.hidden === false && res.state === 'visible', res);
    check('visibilitychange swallowed', res.vis === 0, res);
    check('element blur still delivered (search box keeps working)', res.inputBlur === 1, res);

    await page.evaluate(() => window.__streamShield.setConfig({ bgPlay: false }));
    const off = await page.evaluate(() => {
        document.dispatchEvent(new Event('visibilitychange'));
        return window.__visibilityEvents;
    });
    check('with background play off, visibility events pass through', off === 1, off);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Speed, SponsorBlock, state reporting');
{
    const { context, page } = await openWatchPage({ speed: 1.0 });
    await page.evaluate(() => window.__streamShield.setConfig({ speed: 1.5 }));
    check('setConfig speed applies immediately', Math.abs(await page.evaluate(() => document.querySelector('video').playbackRate) - 1.5) < 0.01);

    await page.evaluate(() => document.querySelector('video').play());
    await sleep(1700);
    await page.evaluate(() => { document.querySelector('video').playbackRate = 2; });
    await sleep(100);
    const speedLog = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'speed').map((e) => e[1]));
    check('speed change from YouTube\'s own menu is adopted and reported', speedLog.length === 1 && speedLog[0] === '2', speedLog);

    await page.evaluate(() => {
        window.__streamShield.setSponsorSegments('abc123def45', [[3, 8]]);
        document.querySelector('video').currentTime = 3.2;
    });
    await sleep(700);
    const t = await page.evaluate(() => document.querySelector('video').currentTime);
    check('SponsorBlock segment skipped', t >= 8, t);

    await page.evaluate(() => {
        window.__streamShield.setSponsorSegments('someOtherId', [[10, 20]]);
        document.querySelector('video').currentTime = 10.5;
    });
    await sleep(600);
    const t2 = await page.evaluate(() => document.querySelector('video').currentTime);
    check('segments for a different video are ignored', t2 < 20 && t2 >= 10.5, t2);

    await sleep(2500);
    const states = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'state').map((e) => e[1]));
    const last = states[states.length - 1];
    check('state reports video id, title, channel, dimensions', last.videoId === 'abc123def45' && last.title === 'Test video' &&
        last.channel === 'Test Channel' && last.width === 320 && last.height === 180 && last.playing === true, last);
    check('state is only reported on change (not every tick)', states.length <= 8, states.length);
    check('diagnostics: load time, start time and ads removed are reported',
        last.loadMs >= 0 && last.startMs >= last.loadMs && last.startMs < 10000 && last.pruned >= 1, last);

    check('state includes channel id, length, views and category from the player response',
        last.channelId === 'UCtestchannel000000000AA' && last.lengthSec === 30 && last.views === 1234 &&
        last.category === 'Music', last);
    check('while playing, position is reported in 10 s steps', last.pos % 10 === 0 && last.ended === false, last);

    await page.evaluate(() => window.__streamShield.pause());
    await sleep(150);
    const pausedState = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'state').pop()[1]);
    const exactPos = await page.evaluate(() => Math.floor(document.querySelector('video').currentTime));
    check('pause command pauses and reports', pausedState.playing === false);
    check('paused state reports the exact position (for resume)', pausedState.pos === exactPos, [pausedState.pos, exactPos]);
    await page.evaluate(() => window.__streamShield.togglePlay());
    await sleep(200);
    check('togglePlay resumes', await page.evaluate(() => !document.querySelector('video').paused));
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Picture-in-picture layout');
{
    const { context, page } = await openWatchPage();
    await page.setViewportSize({ width: 400, height: 225 });
    await page.evaluate(() => window.__streamShield.setConfig({ pip: true }));
    const inPip = await page.evaluate(() => {
        const p = document.getElementById('movie_player').getBoundingClientRect();
        return { style: !!document.getElementById('streamshield-pip'), w: p.width, h: p.height, top: p.top };
    });
    check('PiP style fills the window with the player', inPip.style && inPip.w === 400 && inPip.h === 225 && inPip.top === 0, inPip);
    const videoBox = await page.evaluate(() => { const r = document.querySelector('video').getBoundingClientRect(); return { w: r.width, h: r.height }; });
    check('PiP: the video itself fills the window', videoBox.w === 400 && videoBox.h === 225, videoBox);

    await page.evaluate(() => {
        const v = document.querySelector('video');
        window.__pauseEvents = 0;
        v.addEventListener('pause', () => window.__pauseEvents++);
        // Mock of YouTube's player API, used for resuming.
        window.__playVideoCalls = 0;
        document.getElementById('movie_player').playVideo = () => { window.__playVideoCalls++; v.play(); };
        return v.play();
    });
    await sleep(200);
    // YouTube's player calling pause() when the window turns into PiP, repeatedly.
    await page.evaluate(() => { for (let i = 0; i < 3; i++) document.querySelector('video').pause(); });
    await sleep(300);
    const pipPause = await page.evaluate(() => ({
        paused: document.querySelector('video').paused,
        pauseEvents: window.__pauseEvents,
        state: (window.__streamShield.reportState(), window.__bridgeLog.filter((e) => e[0] === 'state').pop()[1])
    }));
    check('PiP: page pause calls are refused (no pause/play stutter)', !pipPause.paused && pipPause.pauseEvents === 0, pipPause);
    check('PiP: refused pauses are counted for diagnostics', pipPause.state.pipBlocked === 3, pipPause.state);
    check("PiP: YouTube's player is told to keep playing (keeps live streams loading)",
        (await page.evaluate(() => window.__playVideoCalls)) >= 1);
    await page.evaluate(() => window.__streamShield.togglePlay()); // the PiP window's pause button
    await sleep(1200);
    check("PiP: the user's own pause is respected", await page.evaluate(() => document.querySelector('video').paused));
    await page.evaluate(() => window.__streamShield.togglePlay());
    await sleep(300);
    check('PiP: play button resumes', await page.evaluate(() => !document.querySelector('video').paused));
    check("PiP: resuming goes through YouTube's player API", (await page.evaluate(() => window.__playVideoCalls)) >= 1);

    await page.setViewportSize({ width: 412, height: 800 });
    await page.evaluate(() => { window.__resizeEvents = 0; window.__streamShield.setConfig({ pip: false }); });
    await page.evaluate(() => document.querySelector('video').pause());
    check('outside PiP, the page can pause normally', await page.evaluate(() => document.querySelector('video').paused));
    await sleep(1200);
    const outPip = await page.evaluate(() => ({ style: !!document.getElementById('streamshield-pip'), resizes: window.__resizeEvents }));
    check('PiP style removed on exit', outPip.style === false, outPip);
    check('resize re-dispatched so the player re-lays out (fixes cropped view)', outPip.resizes >= 5, outPip);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Performance');
{
    const { context, page } = await openWatchPage();
    const perf = await page.evaluate(() => {
        const items = [];
        for (let i = 0; i < 3000; i++) {
            items.push(i % 25 === 0
                ? { richItemRenderer: { content: { adSlotRenderer: { slotId: 'ad' + i, payload: 'x'.repeat(200) } } } }
                : { richItemRenderer: { content: { videoRenderer: { videoId: 'v' + i, title: { runs: [{ text: 'Video ' + i }] },
                    thumbnail: { thumbnails: [{ url: 'https://i.ytimg.com/vi/' + i + '/hq.jpg', width: 480, height: 360 }] },
                    description: 'y'.repeat(400) } } } });
        }
        const text = JSON.stringify({ responseContext: {}, contents: { richGridRenderer: { contents: items } } });
        // Reference: the browser's own parser, captured before any page code ran is not possible
        // here, so compare against parsing a copy without the trigger keyword.
        const plain = text.replace('responseContext', 'responseKontext');
        const runs = 5;
        let hooked = 0, base = 0, kept = 0;
        for (let r = 0; r < runs; r++) {
            let t = performance.now(); JSON.parse(plain); base += performance.now() - t;
            t = performance.now(); const out = JSON.parse(text); hooked += performance.now() - t;
            kept = out.contents.richGridRenderer.contents.length;
        }
        return { mb: (text.length / 1e6).toFixed(2), base: base / runs, hooked: hooked / runs, kept };
    });
    console.log(`       ${perf.mb} MB response: parse ${perf.base.toFixed(1)} ms, parse+prune ${perf.hooked.toFixed(1)} ms`);
    check('pruning keeps all 2880 real items', perf.kept === 2880, perf.kept);
    check('pruning overhead on a large feed response is small (< 25 ms)', perf.hooked - perf.base < 25, perf);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Next / previous / autoplay');
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    const viaNext = await page.evaluate(() => window.__streamShield.next());
    check("next() uses the page's own Media Session handler", viaNext === 'mediaSession' &&
        (await page.evaluate(() => window.__nextCalls)) === 1, viaNext);

    await page.evaluate(() => { document.querySelector('video').currentTime = 12; });
    await sleep(200);
    const viaPrev = await page.evaluate(() => window.__streamShield.previous());
    const tAfterPrev = await page.evaluate(() => document.querySelector('video').currentTime);
    check('previous() past 5 s restarts the video (like the YouTube app)', viaPrev === 'restart' && tAfterPrev < 2, [viaPrev, tAfterPrev]);
    const viaPrev2 = await page.evaluate(() => window.__streamShield.previous());
    check('previous() at the start goes to the previous track', viaPrev2 === 'mediaSession' &&
        (await page.evaluate(() => window.__prevCalls)) === 1, viaPrev2);

    // Autoplay: the video ends and the page does not move on by itself.
    await page.evaluate(() => {
        window.__streamShield.internals.setAutoplayDelay(150);
        const v = document.querySelector('video');
        v.currentTime = v.duration - 0.3;
        v.play();
    });
    await page.waitForFunction(() => document.querySelector('video').ended, null, { timeout: 5000 });
    await sleep(500);
    check('autoplay moves on after a video ends', (await page.evaluate(() => window.__nextCalls)) === 2,
        await page.evaluate(() => window.__nextCalls));
    await context.close();
}
{
    const { context, page } = await openWatchPage({ autoplay: false });
    await page.evaluate(() => {
        window.__streamShield.internals.setAutoplayDelay(150);
        const v = document.querySelector('video');
        v.currentTime = v.duration - 0.3;
        v.play();
    });
    await page.waitForFunction(() => document.querySelector('video').ended, null, { timeout: 5000 });
    await sleep(500);
    check('with autoplay off, nothing happens after the video ends', (await page.evaluate(() => window.__nextCalls)) === 0);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Feed previews on m.youtube.com');
{
    // Outside a watch page (here YouTube's home page) YouTube plays feed items as muted
    // previews: they are left alone (not refused, which slowed app start) and never unmuted.
    async function mutedStart(page) {
        return page.evaluate(async () => {
            const v = document.querySelector('video');
            v.muted = true;
            const r = await v.play().then(() => 'played', (e) => 'refused:' + e.name);
            await new Promise((ok) => setTimeout(ok, 400));
            return { r, muted: v.muted, paused: v.paused };
        });
    }
    let { context, page } = await openWatchPage({}, 'https://m.youtube.com/');
    const home = await mutedStart(page);
    check('home page: a muted feed preview is not refused and stays muted', home.r === 'played' && home.muted, home);
    await context.close();

    // After a video, YouTube moves to its home page in-page (its mini-player keeps the video).
    ({ context, page } = await openWatchPage());
    await page.evaluate(() => history.pushState({}, '', '/'));
    const after = await mutedStart(page);
    check("home page after a video: plays (YouTube's mini-player), not unmuted",
        after.r === 'played' && after.muted && !after.paused, after);
    await context.close();

    // YouTube Music plays songs (with sound) from any page.
    ({ context, page } = await openWatchPage({}, 'https://music.youtube.com/'));
    const music = await mutedStart(page);
    check('YouTube Music home page: a song plays with sound', music.r === 'played' && !music.muted, music);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log("YouTube's muted autoplay");
{
    // The page starts the video muted, as m.youtube.com does when it was opened without a tap.
    async function startMuted(page, { api = false } = {}) {
        await page.evaluate(async (useApi) => {
            const v = document.querySelector('video');
            window.__unMuteCalls = 0;
            if (useApi) {
                const p = document.getElementById('movie_player');
                window.__ytMuted = true;
                p.isMuted = () => window.__ytMuted;
                p.unMute = () => { window.__unMuteCalls++; window.__ytMuted = false; v.muted = false; };
            }
            v.muted = true;
            await v.play();
        }, api);
        await sleep(400);
    }
    const isMuted = (page) => page.evaluate(() => document.querySelector('video').muted);

    let { context, page } = await openWatchPage();
    await startMuted(page, { api: true });
    check('a video YouTube started muted plays with sound', (await isMuted(page)) === false);
    check("unmuted through the player's API", (await page.evaluate(() => window.__unMuteCalls)) === 1);
    // YouTube mutes again a moment later (e.g. syncing its own state): unmuted again.
    await page.evaluate(() => { document.querySelector('video').muted = true; });
    await sleep(300);
    check('a later automatic mute is undone too', (await isMuted(page)) === false);
    await context.close();

    // The user mutes with the page's own mute button: kept, also on the next page in this tab.
    ({ context, page } = await openWatchPage());
    await page.evaluate(() => document.querySelector('video').play());
    await page.evaluate(() => {
        const b = document.createElement('button');
        b.className = 'ytp-mute-button';
        b.setAttribute('aria-label', 'Mute (m)');
        b.innerHTML = '<span id="muteIcon">x</span>';
        b.addEventListener('click', () => { document.querySelector('video').muted = true; });
        document.getElementById('movie_player').appendChild(b);
    });
    await page.click('#muteIcon');
    await sleep(400);
    check("the user's own mute is kept", (await isMuted(page)) === true);
    await page.goto('https://m.youtube.com/watch?v=abc123def45');
    await page.waitForFunction(() => { const v = document.querySelector('video'); return v && v.readyState >= 1; });
    await startMuted(page);
    check("the user's mute also holds for the next video in the tab", (await isMuted(page)) === true);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Autoplay while the app is hidden');
{
    // kind: the watch-next response the page saw ('none': none was seen). The mock page's own
    // "next track" handler only counts calls (window.__nextCalls), like a page that cannot move
    // on by itself while nothing is drawn.
    async function endVideo(page, kind, delays = [300, 800]) {
        await page.evaluate(async ({ k, d }) => {
            window.__streamShield.internals.setAdvanceDelays(d[0], d[1]);
            if (k !== 'none') await (await fetch('/youtubei/v1/next?kind=' + k, { method: 'POST' })).json();
            const v = document.querySelector('video');
            v.currentTime = v.duration - 0.3;
            await v.play();
        }, { k: kind, d: delays });
        await page.waitForFunction(() => document.querySelector('video').ended, null, { timeout: 5000 });
    }
    const otherVideo = /watch\?v=(?!abc123def45)/;
    const lastState = (page) => page.evaluate(() => {
        const s = window.__bridgeLog.filter((e) => e[0] === 'state');
        return s.length ? s[s.length - 1][1] : null;
    });

    // Picture-in-picture, a playlist: opens the next video with its playlist position.
    let { context, page } = await openWatchPage({ pip: true });
    await endVideo(page, 'playlist');
    await page.waitForURL(otherVideo, { timeout: 8000 }).catch(() => {});
    check('PiP: opens the next video of the playlist with its position',
        /watch\?v=nextVid0001&list=RDabc&index=4/.test(page.url()), page.url());
    await page.waitForFunction(() => { const v = document.querySelector('video'); return v && !v.paused; }, null, { timeout: 5000 }).catch(() => {});
    check('the next video starts playing on its own', await page.evaluate(() => !document.querySelector('video').paused));
    check('the new page reports how autoplay moved on (diagnostics)', (await lastState(page))?.adv === 'page', await lastState(page));
    await context.close();

    // Screen off, m.youtube.com mix (live-site shape), Autoplay setting off: a mix still continues.
    ({ context, page } = await openWatchPage({ appVisible: false, autoplay: false }));
    await endVideo(page, 'mweb-mix');
    await page.waitForURL(otherVideo, { timeout: 8000 }).catch(() => {});
    check('screen off: the next video of an m.youtube.com mix, even with Autoplay off',
        /watch\?v=nextVid0001&list=RDabc&index=4/.test(page.url()), page.url());
    check('the page\'s own next track handler is not used when the next video is known',
        (await page.evaluate(() => window.__nextCalls)) === 0);
    await context.close();

    // Screen off, a suggestion: follows the Autoplay setting.
    ({ context, page } = await openWatchPage({ appVisible: false, autoplay: true }));
    await endVideo(page, 'suggestion');
    await page.waitForURL(otherVideo, { timeout: 8000 }).catch(() => {});
    check('screen off, Autoplay on: opens the suggested video', /watch\?v=suggVid0001$/.test(page.url()), page.url());
    await context.close();

    ({ context, page } = await openWatchPage({ appVisible: false, autoplay: false }));
    await endVideo(page, 'suggestion');
    await sleep(1500);
    check('screen off, Autoplay off: a suggested video is not opened',
        page.url().includes('abc123def45') && (await page.evaluate(() => window.__nextCalls)) === 0, page.url());
    await context.close();

    // Nothing known about the next video: the page's own next track.
    ({ context, page } = await openWatchPage({ pip: true }));
    await endVideo(page, 'none');
    await sleep(1000);
    check('no next video known: falls back to the page\'s next track',
        (await page.evaluate(() => window.__nextCalls)) === 1 && page.url().includes('abc123def45'), page.url());
    await context.close();

    // A pause from the app (notification) right after the end cancels it.
    ({ context, page } = await openWatchPage({ appVisible: false }));
    await endVideo(page, 'playlist', [600, 800]);
    await page.evaluate(() => window.__streamShield.pause());
    await sleep(1500);
    check('a pause from the app right after the end stops autoplay', page.url().includes('abc123def45'), page.url());
    await context.close();

    // App on screen: left to YouTube's own countdown (and the slower fallback above).
    ({ context, page } = await openWatchPage({ appVisible: true }));
    await endVideo(page, 'playlist');
    await sleep(1500);
    check('app on screen: does not open the next video itself', page.url().includes('abc123def45'), page.url());
    await context.close();

    // YouTube Music, screen off: its own next track first, then the next song's page.
    ({ context, page } = await openWatchPage({ appVisible: false }, 'https://music.youtube.com/watch?v=abc123def45'));
    await endVideo(page, 'music', [300, 900]);
    const music = await page.evaluate(() => window.__streamShield.internals.nextByVideo['abc123def45'] || null);
    check('YouTube Music queue: next song found from the selected entry',
        music && music.endpoint.videoId === 'nextSong001' && music.inPlaylist === true, music);
    await sleep(700);
    check('YouTube Music: first asks the page for its next track',
        (await page.evaluate(() => window.__nextCalls)) === 1 && page.url().includes('abc123def45'), page.url());
    await page.waitForURL(otherVideo, { timeout: 8000 }).catch(() => {});
    check('YouTube Music: song unchanged, so it opens the next song\'s page (no index)',
        /^https:\/\/music\.youtube\.com\/watch\?v=nextSong001&list=RDAMVMabc$/.test(page.url()), page.url());
    await page.waitForFunction(() => { const v = document.querySelector('video'); return v && !v.paused; }, null, { timeout: 5000 }).catch(() => {});
    check('YouTube Music: the next song starts playing', await page.evaluate(() => !document.querySelector('video').paused));
    await context.close();
}


// ---------------------------------------------------------------------------------------------
console.log('YouTube Music queues (Music tab For you)');
{
    const queues = (page) => page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'musicQueue').map((e) => e[1]));
    const fetchNext = (page, kind) => page.evaluate(async (k) => {
        await (await fetch('/youtubei/v1/next?kind=' + k, { method: 'POST' })).json();
    }, kind);
    let { context, page } = await openWatchPage({}, 'https://music.youtube.com/watch?v=radioSong02');
    await fetchNext(page, 'music-radio');
    let q = await queues(page);
    const first = q[0];
    check('the radio is reported under the song it started from', q.length === 1 && first.seed === 'seedSong001' &&
        first.list === 'RDAMVMseedSong001', q);
    check('songs once each, without the seed, in queue order',
        first && first.items.map((i) => i.id).join() === 'radioSong01,radioSong02,radioSong03,radioSong04', first && first.items);
    const s3 = first && first.items.find((i) => i.id === 'radioSong03');
    check('title, artist, artist channel and length are read',
        s3 && s3.title === 'Song 3' && s3.artist === 'Artist 3' && s3.channelId === 'UCartist0000000000000003' && s3.dur === 3723, s3);
    await fetchNext(page, 'music-radio');
    check('the same list is reported once', (await queues(page)).length === 1);
    await fetchNext(page, 'music');
    check('a queue of two songs is not reported', (await queues(page)).length === 1, await queues(page));
    await context.close();

    ({ context, page } = await openWatchPage({}, 'https://m.youtube.com/watch?v=abc123def45'));
    await fetchNext(page, 'music-radio');
    check('not reported from m.youtube.com', (await queues(page)).length === 0);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log("Taps on the page's play/pause button");
async function addPlayPauseButton(page, { handled = false, hidden = false, overlay = false } = {}) {
    await page.evaluate(({ handled, hidden, overlay }) => {
        const player = document.getElementById('movie_player');
        player.style.position = 'relative';
        const controls = document.createElement('div');
        controls.className = 'player-controls-content';
        controls.style.cssText = 'position:absolute;inset:0;opacity:' + (hidden ? '0' : '1');
        const b = document.createElement('button');
        b.id = 'pp';
        b.className = 'icon-button player-control-play-pause-icon';
        b.setAttribute('aria-label', 'Pause video');
        b.style.cssText = 'position:absolute;left:100px;top:40px;width:60px;height:60px';
        if (handled) b.addEventListener('click', () => {
            const v = document.querySelector('video');
            if (v.paused) v.play(); else v.pause();
        });
        controls.appendChild(b);
        player.appendChild(controls);
        if (overlay) {
            const o = document.createElement('div');
            o.id = 'leftoverBackdrop';
            o.style.cssText = 'position:fixed;inset:0;background:transparent;z-index:99999';
            document.body.appendChild(o);
        }
    }, { handled, hidden, overlay });
    const box = await page.locator('#pp').boundingBox();
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}
const lastState = (page) => page.evaluate(() => {
    const s = window.__bridgeLog.filter((e) => e[0] === 'state');
    return s.length ? s[s.length - 1][1] : null;
});
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    const at = await addPlayPauseButton(page);
    await page.mouse.click(at.x, at.y);
    await sleep(700);
    const paused = await page.evaluate(() => document.querySelector('video').paused);
    const st = await lastState(page);
    check('a tap the page missed is applied by the app (pause)', paused === true, paused);
    check('the fix is counted for diagnostics', st && st.tapFix === 1 && st.tapTop === 'the button itself', st && [st.tapFix, st.tapTop]);
    await page.mouse.click(at.x, at.y);
    await sleep(700);
    check('... and play again', (await page.evaluate(() => document.querySelector('video').paused)) === false);
    await context.close();
}
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    const at = await addPlayPauseButton(page, { handled: true });
    await page.mouse.click(at.x, at.y);
    await sleep(700);
    const st = await lastState(page);
    check('a tap the page handles is left alone', (await page.evaluate(() => document.querySelector('video').paused)) === true &&
        st && st.tapFix === 0, st && st.tapFix);
    await context.close();
}
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    const at = await addPlayPauseButton(page, { hidden: true });
    await page.mouse.click(at.x, at.y);
    await sleep(700);
    check('a tap where the controls are faded out does nothing', (await page.evaluate(() => document.querySelector('video').paused)) === false);
    await context.close();
}
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    const at = await addPlayPauseButton(page, { overlay: true });
    await page.mouse.click(at.x, at.y);
    await sleep(700);
    const st = await lastState(page);
    check('a tap swallowed by an invisible overlay still pauses', (await page.evaluate(() => document.querySelector('video').paused)) === true);
    check('the overlay is named in diagnostics', st && st.tapTop === 'div#leftoverBackdrop', st && st.tapTop);
    check('state carries the page copy of the PiP flag', st && st.cfgPip === false, st && st.cfgPip);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Subscribe button');
{
    const { context, page } = await openWatchPage();
    await page.click('#subBtn');
    await sleep(150);
    const taps = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'subscribeTap').map((e) => e[1]));
    check("Subscribe goes to the app with the video's channel", taps.length === 1 &&
        taps[0].id === 'UCtestchannel000000000AA' && taps[0].name === 'Test Channel', taps);
    check("YouTube's sign-in handler never runs", (await page.evaluate(() => window.__signIn)) === 0);
    await page.click('#subsTab');
    check('other buttons ("Subscriptions") are not touched', (await page.evaluate(() => window.__subsTab)) === 1);
    await page.evaluate(() => {
        window.__subscribedKeys = { UCtestchannel000000000AA: true };
        window.__streamShield.refreshSubscribeButtons();
    });
    const shown = await page.evaluate(() => [document.getElementById('subLabel').textContent,
        document.getElementById('subBtn').getAttribute('data-ss-sub')]);
    check('a subscribed channel shows "Subscribed"', shown[0] === 'Subscribed' && shown[1] === '1', shown);
    await page.evaluate(() => {
        window.__subscribedKeys = {};
        window.__streamShield.refreshSubscribeButtons();
    });
    const back = await page.evaluate(() => document.getElementById('subLabel').textContent);
    check('... and "Subscribe" after unsubscribing', back === 'Subscribe', back);
    await context.close();
}
{
    // Channel page in the browse page: the channel comes from YouTube's page data.
    const { context, page } = await openWatchPage({ role: 'browse' });
    await page.evaluate(() => {
        JSON.parse(JSON.stringify({ responseContext: {}, metadata: { channelMetadataRenderer: {
            externalId: 'UCchannelpage0000000000A', title: 'Channel Page', vanityChannelUrl: 'http://www.youtube.com/@channelpage' } } }));
        history.pushState({}, '', '/@channelpage/videos');
    });
    await page.click('#subBtn');
    await sleep(150);
    const taps = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'subscribeTap').map((e) => e[1]));
    check('channel page: Subscribe sends the channel id from the page data', taps.length === 1 &&
        taps[0].id === 'UCchannelpage0000000000A' && taps[0].path === '/@channelpage' && taps[0].name === 'Channel Page', taps);
    await context.close();
}

{
    // m.youtube's current owner row: the button deep inside wrappers, the avatar link higher up.
    const { context, page } = await openWatchPage({ role: 'browse' });
    await page.evaluate(() => {
        const row = document.createElement('div');
        row.className = 'owner-row';
        row.innerHTML = '<a href="/@deepchannel"><img alt=""></a><div class="modern-subscribe-button-container">' +
            '<div class="modern-subscribe-button"><div class="ytwAnimatedSubscribeButtonHost"><yt-animated-action>' +
            '<div><div><div><button id="deepSub" class="ytSpecButtonShapeNextHost">Subscribe</button></div></div></div>' +
            '</yt-animated-action></div></div></div>';
        document.body.appendChild(row);
    });
    await page.click('#deepSub');
    await sleep(150);
    const taps = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'subscribeTap').map((e) => e[1]));
    check('a deeply wrapped Subscribe button finds the nearest channel link', taps.length === 1 && taps[0].path === '/@deepchannel', taps);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Like / Dislike buttons');
{
    const { context, page } = await openWatchPage();
    // m.youtube.com's markup: a segmented like/dislike in the action bar, and a comment's like.
    await page.evaluate(() => {
        window.__ytPrompt = 0;
        const bar = document.createElement('ytm-slim-video-action-bar-renderer');
        bar.innerHTML = '<segmented-like-dislike-button-view-model>' +
            '<like-button-view-model><button id="likeBtn" aria-label="like this video along with 1,234 other people">' +
            '<span id="likeIcon">L</span><span>1.2K</span></button></like-button-view-model>' +
            '<dislike-button-view-model><button id="dislikeBtn" aria-label="Dislike this video">D</button></dislike-button-view-model>' +
            '</segmented-like-dislike-button-view-model>';
        const comment = document.createElement('ytm-comment-renderer');
        comment.innerHTML = '<button id="commentLike" aria-label="Like this comment along with 5 other people">L</button>';
        document.body.append(bar, comment);
        for (const id of ['likeBtn', 'dislikeBtn', 'commentLike']) {
            document.getElementById(id).addEventListener('click', () => window.__ytPrompt++);
        }
    });
    const taps = () => page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'likeTap').map((e) => e[1]));
    await page.click('#likeIcon');
    await sleep(100);
    await page.waitForTimeout(650);
    await page.click('#dislikeBtn');
    await sleep(100);
    check('Like goes to the app with the video id', JSON.stringify((await taps())[0]) === JSON.stringify({ videoId: 'abc123def45', kind: 'like' }), await taps());
    check('Dislike goes to the app', (await taps())[1] && (await taps())[1].kind === 'dislike', await taps());
    check("YouTube's sign-in prompt never sees those taps", (await page.evaluate(() => window.__ytPrompt)) === 0);
    await page.click('#commentLike');
    await sleep(100);
    check("a comment's Like is left to YouTube", (await taps()).length === 2 && (await page.evaluate(() => window.__ytPrompt)) === 1);

    await page.evaluate(() => {
        window.__likeStates = { abc123def45: 'like' };
        window.__streamShield.refreshLikeButtons();
    });
    const shown = await page.evaluate(() => ({
        like: [document.getElementById('likeBtn').getAttribute('data-ss-like'), document.getElementById('likeBtn').getAttribute('aria-pressed')],
        dislike: [document.getElementById('dislikeBtn').getAttribute('data-ss-like'), document.getElementById('dislikeBtn').getAttribute('aria-pressed')],
        comment: document.getElementById('commentLike').getAttribute('data-ss-like')
    }));
    check("the buttons show the app's state (liked)", JSON.stringify(shown) === JSON.stringify({ like: ['1', 'true'], dislike: ['0', 'false'], comment: null }), shown);
    await page.evaluate(() => {
        window.__likeStates = { abc123def45: 'dislike' };
        window.__streamShield.refreshLikeButtons();
    });
    const flipped = await page.evaluate(() => [document.getElementById('likeBtn').getAttribute('aria-pressed'), document.getElementById('dislikeBtn').getAttribute('aria-pressed')]);
    check('and follow it when it changes (disliked)', JSON.stringify(flipped) === JSON.stringify(['false', 'true']), flipped);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Pull-down gesture');
async function drag(page, selector, dx, dy, steps = 8) {
    await page.evaluate(({ selector, dx, dy, steps }) => {
        const el = document.querySelector(selector);
        const r = el.getBoundingClientRect();
        const x0 = r.left + Math.min(20, r.width / 2);
        const y0 = r.top + Math.min(10, r.height / 2);
        const touch = (x, y) => new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
        const fire = (type, t) => el.dispatchEvent(new TouchEvent(type, {
            bubbles: true, cancelable: true, touches: t ? [t] : [], changedTouches: t ? [t] : [], targetTouches: t ? [t] : []
        }));
        fire('touchstart', touch(x0, y0));
        for (let i = 1; i <= steps; i++) fire('touchmove', touch(x0 + dx * i / steps, y0 + dy * i / steps));
        el.dispatchEvent(new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [], changedTouches: [touch(x0 + dx, y0 + dy)] }));
    }, { selector, dx, dy, steps });
    await sleep(80);
    return page.evaluate(() => {
        const ends = window.__bridgeLog.filter((e) => e[0] === 'pullEnd').map((e) => e[1]);
        window.__bridgeLog.length = 0;
        return ends;
    });
}
{
    const { context, page } = await openWatchPage({ pull: 'refresh' });
    let ends = await drag(page, '.slim-video-information-title', 0, 160);
    check('refresh: a pull from the top of the page is reported', ends.length === 1 && ends[0].mode === 'refresh' &&
        ends[0].distance > 120 && ends[0].cancelled === false, ends);
    ends = await drag(page, '.slim-video-information-title', 160, 20);
    check('refresh: a sideways drag is not a pull', ends.length === 0, ends);
    ends = await drag(page, '.slim-video-information-title', 0, -160);
    check('refresh: an upward drag is not a pull', ends.length === 0, ends);
    ends = await drag(page, 'video', 0, 160);
    check('refresh: a drag on the video is left to the player', ends.length === 0, ends);
    await page.evaluate(() => { document.body.style.minHeight = '4000px'; window.scrollTo(0, 300); });
    ends = await drag(page, '#realItem', 0, 160);
    check('refresh: not when the page is scrolled down', ends.length === 0, ends);
    await page.evaluate(() => window.__streamShield.setConfig({ pull: 'off' }));
    await page.evaluate(() => window.scrollTo(0, 0));
    ends = await drag(page, '.slim-video-information-title', 0, 160);
    check('off: nothing is reported', ends.length === 0, ends);
    await context.close();
}
{
    const { context, page } = await openWatchPage({ pull: 'minimize' });
    const ends = await drag(page, 'video', 0, 200);
    check('minimize: a pull on the video is reported', ends.length === 1 && ends[0].mode === 'minimize' && ends[0].distance > 150, ends);
    await page.evaluate(() => window.__streamShield.setConfig({ pip: true }));
    const inPip = await drag(page, 'video', 0, 200);
    check('minimize: never in picture-in-picture', inPip.length === 0, inPip);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Browse page fade');
{
    const { context, page } = await openWatchPage({ role: 'browse' });
    await page.evaluate(() => window.dispatchEvent(new Event('state-navigatestart')));
    const fading = await page.evaluate(() => document.documentElement.classList.contains('ss-nav'));
    await page.evaluate(() => history.pushState({}, '', '/results?search_query=x'));
    await sleep(200);
    const done = await page.evaluate(() => document.documentElement.classList.contains('ss-nav'));
    check('the page fades while YouTube navigates, and comes back', fading === true && done === false, [fading, done]);
    await context.close();
}
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => window.dispatchEvent(new Event('state-navigatestart')));
    const fading = await page.evaluate(() => document.documentElement.classList.contains('ss-nav'));
    check('the player page never fades', fading === false);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Resume after reload (YouTube Music)');
{
    const { context, page } = await openWatchPage();
    await page.evaluate(() => {
        document.getElementById('movie_player').loadVideoById = function (args) {
            window.__loaded = args;
            const v = document.querySelector('video');
            v.currentTime = args.startSeconds;
            v.play();
        };
        window.__streamShield.resumeMusic('abc123def45', 6);
    });
    await sleep(900);
    const r = await page.evaluate(() => ({
        log: window.__bridgeLog.filter((e) => e[0] === 'resume').map((e) => e[1]),
        loaded: window.__loaded,
        t: document.querySelector('video').currentTime
    }));
    check("resumeMusic plays the song through the player's API", r.log[0] === 'ok' && r.loaded.videoId === 'abc123def45' && r.t >= 5.5, r);
    await page.evaluate(() => { document.querySelector('video').pause(); window.__streamShield.seekWhenReady('abc123def45', 12); });
    await sleep(200);
    const t = await page.evaluate(() => document.querySelector('video').currentTime);
    check('seekWhenReady seeks once the video is there', Math.abs(t - 12) < 0.6, t);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Idempotence');
{
    const { context, page } = await openWatchPage();
    const v = await page.evaluate((src) => { (0, eval)(src); return window.__streamShield.version; }, shieldJs);
    check('re-injection is a no-op', v === 2);
    await context.close();
}

await browser.close();
console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures ? 1 : 0);
