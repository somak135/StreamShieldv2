/*
 * StreamShield page script for m.youtube.com / www.youtube.com.
 *
 * Injected at document start (WebViewCompat.addDocumentStartJavaScript) so the hooks are in place
 * before YouTube's own scripts run. Falls back to evaluateJavascript on older WebViews, which is
 * why everything below is idempotent.
 *
 * Responsibilities:
 *   1. Remove ad payloads from YouTube JSON (player response, feed/search/watch-next renderers) so
 *      the player never schedules an ad. This is the same technique uBlock Origin / Brave use.
 *      Covers fetch, XHR (text and responseType 'json'), JSON.parse, inline globals, and batched
 *      / wrapped responses. Also shortens YouTube's 17 s native-code timers (uBO rule).
 *   2. Fallback: if an ad still starts, mute it, press skip, fast-forward to the end, then restore.
 *   3. Cosmetic CSS for leftover ad slots; hide YouTube's "Open App" / "Sign in" buttons and
 *      m.youtube's bottom bar (the app has its own tabs); the YouTube logo opens the app's Home;
 *      close "use the app" prompts (also while the app is hidden, if one could hold playback).
 *   4. Background play: keep the page "visible" so YouTube does not pause when the app is hidden.
 *   5. Playback speed, SponsorBlock segment skipping, PiP layout, play/pause/next/previous
 *      commands (through the page's own Media Session handlers when it has them) and autoplay,
 *      also while the app is hidden (the next video is taken from the watch-next responses).
 *      YouTube Music's queues are reported to the app (the Music tab's "For you" uses them).
 *   6. Report compact playback state to the app (only when it changes), including the video's
 *      channel, length and position from the player response.
 *   7. Taps on the page's play/pause control that the page missed are applied by the app.
 *   8. Pull-down gestures (refresh / minimize) are reported to the app, which draws them.
 *   9. YouTube's Subscribe, Like and Dislike buttons work in the app (no account).
 *  10. Browse page: a short fade while YouTube swaps pages.
 *
 * Native side talks to this script through window.__streamShield.* and receives events through
 * window.StreamShieldBridge (an @JavascriptInterface object).
 */
(function () {
    'use strict';
    if (window.__streamShield) {
        return;
    }

    var bridge = window.StreamShieldBridge || null;

    // role: 'player' (the page that plays videos and YouTube Music) or 'browse' (search results,
    // channels...; video taps there are handed to the player page and nothing plays).
    // appVisible: false while the app is in the background (the heartbeat then skips page tidying
    // and slows down; playback state is still reported for the notification and autoplay).
    // pull: what a pull-down from the top of the page does in the app: 'refresh', 'minimize'
    // (the video page shrinks to the mini-player) or 'off'.
    var config = { adBlock: true, bgPlay: true, speed: 1.0, pip: false, sponsorSkip: true, autoplay: true, role: 'player', appVisible: true, pull: 'off' };
    try {
        if (bridge && bridge.getConfig) {
            var initial = JSON.parse(String(bridge.getConfig()));
            for (var k in initial) {
                if (Object.prototype.hasOwnProperty.call(initial, k)) config[k] = initial[k];
            }
        }
    } catch (e) {}

    function report(method, arg) {
        try {
            if (bridge && typeof bridge[method] === 'function') bridge[method](arg);
        } catch (e) {}
    }

    // The page's own Media Session handlers (YouTube and YouTube Music register "nexttrack" /
    // "previoustrack" for their queue and autoplay). Captured at document start so the app's
    // next/previous buttons (notification, lock screen, PiP) can trigger exactly what the page does.
    var mediaHandlers = {};
    (function hookMediaSession() {
        var ms = navigator.mediaSession;
        if (!ms || typeof ms.setActionHandler !== 'function') return;
        var nativeSet = ms.setActionHandler.bind(ms);
        try {
            ms.setActionHandler = function (action, handler) {
                mediaHandlers[action] = typeof handler === 'function' ? handler : null;
                try { return nativeSet(action, handler); } catch (e) {}
            };
        } catch (e) {}
    })();

    function mediaMetadata() {
        try {
            var m = navigator.mediaSession && navigator.mediaSession.metadata;
            return m ? { title: String(m.title || ''), artist: String(m.artist || '') } : null;
        } catch (e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // 1. JSON ad pruning
    // ---------------------------------------------------------------------------------------
    var PLAYER_AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams', 'adBreakParams'];
    // Renderers that only ever wrap ads in browse/search/next responses.
    var AD_RENDERER_KEYS = {
        adSlotRenderer: 1,
        promotedSparklesWebRenderer: 1,
        promotedSparklesTextSearchRenderer: 1,
        promotedVideoRenderer: 1,
        compactPromotedVideoRenderer: 1,
        compactPromotedItemRenderer: 1,
        inFeedAdLayoutRenderer: 1,
        displayAdRenderer: 1,
        searchPyvRenderer: 1,
        bannerPromoRenderer: 1,
        statementBannerRenderer: 1,
        brandVideoSingletonRenderer: 1,
        brandVideoShelfRenderer: 1,
        companionAdRenderer: 1,
        actionCompanionAdRenderer: 1,
        mealbarPromoRenderer: 1,
        adBreakServiceRenderer: 1,
        reelShelfAdRenderer: 1
    };
    var MAX_WALK_DEPTH = 40;

    function isAdItem(item) {
        if (!item || typeof item !== 'object') return false;
        for (var key in item) {
            if (AD_RENDERER_KEYS[key]) return true;
        }
        // Shorts feed: ads are ordinary reel entries flagged in their endpoint.
        var reel = item.command && item.command.reelWatchEndpoint;
        if (reel && reel.adClientParams && reel.adClientParams.isAd) return true;
        // Feed cells wrap the real renderer: {richItemRenderer: {content: {adSlotRenderer: ...}}}
        var rich = item.richItemRenderer;
        if (rich && rich.content && typeof rich.content === 'object') {
            for (var k2 in rich.content) {
                if (AD_RENDERER_KEYS[k2]) return true;
            }
        }
        // Mobile section items: {itemSectionRenderer: {contents: [{adSlotRenderer: ...}]}}
        var section = item.itemSectionRenderer;
        if (section && Array.isArray(section.contents) && section.contents.length > 0) {
            for (var i = 0; i < section.contents.length; i++) {
                if (!isAdItem(section.contents[i])) return false;
            }
            return true;
        }
        return false;
    }

    // Returns the number of ad entries removed.
    function stripAdRenderers(node, depth) {
        if (!node || typeof node !== 'object' || depth > MAX_WALK_DEPTH) return 0;
        var removed = 0;
        if (Array.isArray(node)) {
            for (var i = node.length - 1; i >= 0; i--) {
                if (isAdItem(node[i])) {
                    node.splice(i, 1);
                    removed++;
                } else {
                    removed += stripAdRenderers(node[i], depth + 1);
                }
            }
            return removed;
        }
        for (var key in node) {
            if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
            var value = node[key];
            if (value && typeof value === 'object') {
                if (AD_RENDERER_KEYS[key]) {
                    delete node[key];
                    removed++;
                } else {
                    removed += stripAdRenderers(value, depth + 1);
                }
            }
        }
        return removed;
    }

    function stripPlayerAds(obj) {
        if (!obj || typeof obj !== 'object') return 0;
        var removed = 0;
        for (var i = 0; i < PLAYER_AD_KEYS.length; i++) {
            var key = PLAYER_AD_KEYS[i];
            if (Object.prototype.hasOwnProperty.call(obj, key)) {
                if (Array.isArray(obj[key]) ? obj[key].length > 0 : !!obj[key]) removed++;
                delete obj[key];
            }
        }
        // Anti-adblock enforcement dialog ("Ad blockers are not allowed on YouTube").
        var aux = obj.auxiliaryUi;
        if (aux && aux.messageRenderers && aux.messageRenderers.enforcementMessageViewModel) {
            delete aux.messageRenderers.enforcementMessageViewModel;
        }
        // "Get the best experience - Watch in YouTube app" dialog (uBO annoyances filter prunes
        // the same key). Not an ad, so not counted.
        if (aux && aux.messageRenderers && aux.messageRenderers.upsellDialogRenderer) {
            delete aux.messageRenderers.upsellDialogRenderer;
        }
        return removed;
    }

    var prunedObjects = typeof WeakSet === 'function' ? new WeakSet() : null;

    function isPlayerResponse(obj) {
        return !!obj && typeof obj === 'object' && !Array.isArray(obj) &&
            ('playabilityStatus' in obj || 'streamingData' in obj || 'videoDetails' in obj);
    }

    // Responses nested in a wrapper: www's /watch?pbj=1 items and m.youtube's in-app navigation
    // return {playerResponse: {...}, response: {...}} (the watch page's player + up-next data).
    var WRAPPER_KEYS = ['playerResponse', 'response', 'watchNextResponse'];

    function wrappedResponses(obj) {
        var found = [];
        for (var i = 0; i < WRAPPER_KEYS.length; i++) {
            var inner = obj[WRAPPER_KEYS[i]];
            if (inner && typeof inner === 'object' && !Array.isArray(inner) &&
                (isPlayerResponse(inner) || 'responseContext' in inner)) {
                found.push(inner);
            }
        }
        return found;
    }

    // Prunes a parsed YouTube InnerTube response in place. Only touches objects that look like
    // YouTube responses so unrelated JSON is never walked. Batched responses are arrays of such
    // objects (or of wrappers); each element is pruned.
    function pruneResponse(obj) {
        if (!config.adBlock || !obj || typeof obj !== 'object') return obj;
        if (Array.isArray(obj)) {
            for (var a = 0; a < obj.length && a < 16; a++) {
                var item = obj[a];
                if (item && typeof item === 'object' && !Array.isArray(item)) pruneResponse(item);
            }
            return obj;
        }
        if (prunedObjects && prunedObjects.has(obj)) return obj;
        var looksLikePlayer = isPlayerResponse(obj);
        var inner = looksLikePlayer ? [] : wrappedResponses(obj);
        if (!looksLikePlayer && !('responseContext' in obj) && inner.length === 0) return obj;
        if (prunedObjects) prunedObjects.add(obj);

        // Player ads first (also from wrapped player responses), so the renderer walk below has
        // less to visit.
        var adsRemoved = stripPlayerAds(obj);
        for (var i = 0; i < inner.length; i++) {
            if (isPlayerResponse(inner[i])) adsRemoved += stripPlayerAds(inner[i]);
            if (prunedObjects) prunedObjects.add(inner[i]);
        }
        if (!looksLikePlayer) {
            adsRemoved += stripAdRenderers(obj, 0);
        }
        if (adsRemoved > 0) {
            diag.pruned += adsRemoved;
            sinceTap.pruned += adsRemoved;
            report('onAdBlocked', 'prune');
        }
        return obj;
    }

    // Metadata of the current video from the player response (videoDetails + microformat), so the
    // app can show channel, length and views without scraping the page. Independent of ad-block.
    var details = null;
    // The channel page being shown (channel id, name, path), from YouTube's own page data; used
    // by the Subscribe button (section 7).
    var pageChannel = null;

    function noteChannel(obj) {
        var m = obj.metadata && obj.metadata.channelMetadataRenderer;
        if (!m && obj.response && obj.response.metadata) m = obj.response.metadata.channelMetadataRenderer;
        if (!m || !m.externalId) return;
        var id = String(m.externalId);
        pageChannel = {
            id: id,
            name: String(m.title || ''),
            path: channelPath(String(m.vanityChannelUrl || '')) || '/channel/' + id
        };
    }

    function noteDetails(obj) {
        if (!obj || typeof obj !== 'object') return;
        if (Array.isArray(obj)) {
            for (var a = 0; a < obj.length && a < 16; a++) noteDetails(obj[a]);
            return;
        }
        try { noteChannel(obj); } catch (e) {}
        if (!isBrowsePage()) try {
            captureNextVideo(obj);
            if (obj.response) captureNextVideo(obj.response);
            if (obj.watchNextResponse) captureNextVideo(obj.watchNextResponse);
        } catch (e) {}
        var pr = obj.videoDetails ? obj : (obj.playerResponse && obj.playerResponse.videoDetails ? obj.playerResponse : null);
        var vd = pr && pr.videoDetails;
        if (!vd || !vd.videoId) return;
        var mf = pr.microformat && pr.microformat.playerMicroformatRenderer;
        details = {
            videoId: String(vd.videoId),
            title: String(vd.title || ''),
            author: String(vd.author || ''),
            channelId: String(vd.channelId || ''),
            lengthSeconds: parseInt(vd.lengthSeconds, 10) || 0,
            viewCount: parseInt(vd.viewCount, 10) || 0,
            category: mf && mf.category ? String(mf.category) : ''
        };
    }

    // ---------------------------------------------------------------------------------------
    // Next video, for autoplay while the app is hidden (section 4). Watch-next responses say what
    // plays next: autoplay.autoplay.sets[0].nextVideo (playlists, mixes) or .autoplayVideo
    // (suggestions), and the queue: playlistPanelRenderer (YouTube Music) or, on m.youtube.com,
    // singleColumnWatchNextResults.playlist.playlist. Keyed by the video the response is for.
    // Same approach as the original StreamShield (its commits 84418cd and d4e373e).
    // ---------------------------------------------------------------------------------------
    var nextByVideo = {};
    var nextByVideoKeys = [];

    function endpointOf(node) {
        var we = node && (node.watchEndpoint || (node.navigationEndpoint && node.navigationEndpoint.watchEndpoint));
        return we && typeof we.videoId === 'string' ? we : null;
    }

    // A queue entry is {playlistPanelVideoRenderer} or, on YouTube Music, a song/video pair:
    // {playlistPanelVideoWrapperRenderer: {primaryRenderer: {playlistPanelVideoRenderer}}}.
    function queueItem(entry) {
        if (!entry || typeof entry !== 'object') return null;
        if (entry.playlistPanelVideoRenderer) return entry.playlistPanelVideoRenderer;
        var w = entry.playlistPanelVideoWrapperRenderer;
        return (w && w.primaryRenderer && w.primaryRenderer.playlistPanelVideoRenderer) || null;
    }

    function queueItemId(item) {
        var e = endpointOf(item);
        return e ? e.videoId : (item && typeof item.videoId === 'string' ? item.videoId : '');
    }

    function captureNextVideo(obj) {
        if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return;
        var current = endpointOf(obj.currentVideoEndpoint);
        // YouTube Music's watch-next response is recognised by its renderer when it does not name
        // the current video; the queue entry marked "selected" is the current song then.
        var musicNext = !!(obj.contents && obj.contents.singleColumnMusicWatchNextResultsRenderer);
        if (!current && !musicNext) return;   // only watch-next responses
        var found = { playlist: null, autoplay: null };
        var queues = [];
        (function walk(node, depth) {
            if (!node || typeof node !== 'object' || depth > MAX_WALK_DEPTH) return;
            if (Array.isArray(node)) {
                for (var i = 0; i < node.length; i++) walk(node[i], depth + 1);
                return;
            }
            for (var key in node) {
                if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
                var value = node[key];
                if (!value || typeof value !== 'object') continue;
                if (key === 'nextVideo' && !found.playlist) found.playlist = endpointOf(value);
                else if (key === 'autoplayVideo' && !found.autoplay) found.autoplay = endpointOf(value);
                else if ((key === 'playlistPanelRenderer' || key === 'playlist') && Array.isArray(value.contents) &&
                    queues.length < 4) {
                    queues.push(value.contents);
                }
                walk(value, depth + 1);
            }
        })(obj, 0);

        var currentId = current ? current.videoId : '';
        var q, i;
        for (q = 0; q < queues.length && !currentId; q++) {
            for (i = 0; i < queues[q].length; i++) {
                var sel = queueItem(queues[q][i]);
                if (sel && sel.selected === true) { currentId = queueItemId(sel); break; }
            }
        }
        if (!currentId) return;
        if (musicNext) {
            try { reportMusicQueue(queues, currentId); } catch (e) {}
        }
        // The current video's entry, found by id: contents can be a window of a long playlist, and
        // currentIndex is the position in the whole playlist, not in contents.
        var panel = null;
        for (q = 0; q < queues.length && !panel; q++) {
            for (i = 0; i < queues[q].length; i++) {
                if (queueItemId(queueItem(queues[q][i])) !== currentId) continue;
                panel = endpointOf(queueItem(queues[q][i + 1]));
                break;
            }
        }
        var next = found.playlist || panel || found.autoplay;
        if (!next || next.videoId === currentId) return;
        if (!nextByVideo[currentId]) {
            nextByVideoKeys.push(currentId);
            if (nextByVideoKeys.length > 50) delete nextByVideo[nextByVideoKeys.shift()];
        }
        // YouTube Music always plays on through its queue (a radio when it runs out), so a song
        // counts as part of a playlist there.
        nextByVideo[currentId] = { endpoint: next, inPlaylist: !!(found.playlist || panel || musicNext) };
    }

    // YouTube Music's queue for a song: its radio (playlist "RDAMVM" + the song it started from)
    // or the album / playlist being played. The app keeps these lists for the Music tab's "For
    // you" as the songs YouTube Music plays after the seed song (no extra requests to YouTube).
    // Each list is reported once, and again only if it grew.
    var MUSIC_QUEUE_MAX = 50;
    var reportedQueues = Object.create(null);

    function runsText(t) {
        if (!t) return '';
        if (typeof t.simpleText === 'string') return t.simpleText;
        if (!Array.isArray(t.runs)) return '';
        return t.runs.map(function (r) { return (r && r.text) || ''; }).join('');
    }

    function artistChannelOf(item) {
        var lists = [item.shortBylineText, item.longBylineText];
        for (var l = 0; l < lists.length; l++) {
            var runs = lists[l] && lists[l].runs;
            if (!Array.isArray(runs)) continue;
            for (var r = 0; r < runs.length; r++) {
                var be = runs[r] && runs[r].navigationEndpoint && runs[r].navigationEndpoint.browseEndpoint;
                if (be && /^UC[A-Za-z0-9_-]{22}$/.test(be.browseId || '')) return be.browseId;
            }
        }
        return '';
    }

    function durationSeconds(text) {
        var parts = String(text || '').trim().split(':');
        if (parts.length < 2 || parts.length > 3) return 0;
        var sec = 0;
        for (var i = 0; i < parts.length; i++) {
            var n = parseInt(parts[i], 10);
            if (isNaN(n)) return 0;
            sec = sec * 60 + n;
        }
        return sec;
    }

    function reportMusicQueue(queues, currentId) {
        if (!isMusicSite() || isBrowsePage() || !queues.length) return;
        var contents = queues[0];
        for (var q = 0; q < queues.length; q++) {
            for (var c = 0; c < queues[q].length; c++) {
                if (queueItemId(queueItem(queues[q][c])) === currentId) { contents = queues[q]; q = queues.length; break; }
            }
        }
        var listId = '';
        var items = [];
        var ids = Object.create(null);
        for (var i = 0; i < contents.length && items.length <= MUSIC_QUEUE_MAX; i++) {
            var item = queueItem(contents[i]);
            var id = queueItemId(item);
            if (!/^[A-Za-z0-9_-]{11}$/.test(id) || ids[id]) continue;
            ids[id] = true;
            var e = endpointOf(item);
            if (!listId && e && typeof e.playlistId === 'string') listId = e.playlistId;
            items.push({
                id: id,
                title: runsText(item.title),
                artist: runsText(item.shortBylineText) || runsText(item.longBylineText).split(' \u2022 ')[0],
                channelId: artistChannelOf(item),
                dur: durationSeconds(runsText(item.lengthText))
            });
        }
        var radio = /^RDAMVM([A-Za-z0-9_-]{11})$/.exec(listId);
        var seed = radio ? radio[1] : currentId;
        items = items.filter(function (it) { return it.id !== seed; }).slice(0, MUSIC_QUEUE_MAX);
        if (items.length < 3 || (reportedQueues[seed] || 0) >= items.length) return;
        reportedQueues[seed] = items.length;
        report('onMusicQueue', JSON.stringify({ seed: seed, list: listId, items: items }));
    }

    // Expose pure helpers for tests.
    var internals = { nextByVideo: nextByVideo };
    internals.setAutoplayDelay = function (ms) { AUTOPLAY_DELAY_MS = ms; };
    internals.setAdvanceDelays = function (first, musicFallback) {
        ADVANCE_DELAY_MS = first;
        MUSIC_PAGE_FALLBACK_MS = musicFallback;
    };
    internals.diag = function () { return diag; };

    (function installJsonHooks() {
        var nativeParse = JSON.parse;
        JSON.parse = function (text, reviver) {
            var result = nativeParse.apply(this, arguments);
            if (result && typeof result === 'object' && typeof text === 'string' &&
                text.length > 64 && text.indexOf('responseContext') !== -1) {
                try { noteDetails(result); } catch (e) {}
                if (config.adBlock) {
                    try { pruneResponse(result); } catch (e) {}
                }
            }
            return result;
        };
        try { JSON.parse.toString = function () { return nativeParse.toString(); }; } catch (e) {}

        if (window.Response && Response.prototype && Response.prototype.json) {
            var nativeJson = Response.prototype.json;
            Response.prototype.json = function () {
                return nativeJson.apply(this, arguments).then(function (data) {
                    try { noteDetails(data); } catch (e) {}
                    try { pruneResponse(data); } catch (e) {}
                    return data;
                });
            };
        }

        // XMLHttpRequest with responseType 'json': the browser parses the body itself, so neither
        // hook above sees it. Prune the parsed object when the page reads xhr.response (the same
        // object is returned on every read, and pruneResponse skips objects it has done).
        var xhrProto = window.XMLHttpRequest && XMLHttpRequest.prototype;
        var responseDesc = xhrProto && Object.getOwnPropertyDescriptor(xhrProto, 'response');
        if (responseDesc && responseDesc.get && responseDesc.configurable) {
            try {
                Object.defineProperty(xhrProto, 'response', {
                    configurable: true,
                    enumerable: responseDesc.enumerable,
                    get: function () {
                        var value = responseDesc.get.call(this);
                        if (value && typeof value === 'object' && this.responseType === 'json' && this.readyState === 4) {
                            try { noteDetails(value); } catch (e) {}
                            try { pruneResponse(value); } catch (e) {}
                        }
                        return value;
                    }
                });
            } catch (e) {}
        }

        // Inline <script> globals. They are declared with `var ytInitialPlayerResponse = {...}`,
        // which goes through this setter because the property already exists on window.
        ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
            var stored = window[name];
            try {
                Object.defineProperty(window, name, {
                    configurable: true,
                    enumerable: true,
                    get: function () { return stored; },
                    set: function (value) {
                        try { noteDetails(value); } catch (e) {}
                        try { pruneResponse(value); } catch (e) {}
                        stored = value;
                    }
                });
                if (stored) {
                    noteDetails(stored);
                    pruneResponse(stored);
                }
            } catch (e) {}
        });
    })();

    // Start-up timing for in-page navigation: YouTube changes the URL with history.pushState once
    // the next page's data has arrived, so the user's tap is the real start. Ads pruned and timers
    // shortened since that tap belong to the new video.
    var lastTapAt = 0;
    var sinceTap = { pruned: 0, boosted: 0 };
    var navStartAt = 0;        // YouTube's own navigation-start events (see markNavigationStart)
    var NAV_WINDOW_MS = 10000;
    window.addEventListener('click', function () {
        lastTapAt = Date.now();
        sinceTap = { pruned: 0, boosted: 0 };
    }, true);

    function onUrlChanged() {
        setTimeout(endNavFade, 80);
        if (!diag) return;
        var id = currentVideoId();
        if (id === diag.videoId) return;   // same video (e.g. a replaceState adding &t=)
        var now = Date.now();
        var fresh;
        if (now - lastTapAt < NAV_WINDOW_MS) {
            fresh = newDiag(id, lastTapAt);
            fresh.pruned = sinceTap.pruned;
            fresh.boosted = sinceTap.boosted;
        } else if (now - navStartAt < NAV_WINDOW_MS && !diag.videoId) {
            fresh = newDiag(id, diag.navAt);
            fresh.pruned = diag.pruned;
            fresh.boosted = diag.boosted;
        } else {
            fresh = newDiag(id, now);    // e.g. autoplay moving on
        }
        diag = fresh;
    }

    (function hookHistory() {
        ['pushState', 'replaceState'].forEach(function (name) {
            var original = history[name];
            if (typeof original !== 'function') return;
            try {
                history[name] = function () {
                    var before = location.href;
                    var result = original.apply(this, arguments);
                    if (location.href !== before) {
                        try { onUrlChanged(); } catch (e) {}
                    }
                    return result;
                };
            } catch (e) {}
        });
        window.addEventListener('popstate', function () {
            try { onUrlChanged(); } catch (e) {}
        });
    })();

    // YouTube's player can hold playback back with a 17 s timer whose callback is a bound
    // (native-code) function. uBlock Origin's YouTube filters shorten exactly these timers
    // ("nano-stb, [native code], 17000, 0.001": 17000 ms x 0.001 = 17 ms); same rule here.
    // Only delays of exactly 17000 ms with a native-code callback are touched.
    var BOOST_DELAY_MS = 17000;
    (function installTimerBoost() {
        var nativeSetTimeout = window.setTimeout;
        var fnToString = Function.prototype.toString;
        window.setTimeout = function (callback, delay) {
            if (config.adBlock && delay === BOOST_DELAY_MS && typeof callback === 'function') {
                try {
                    if (fnToString.call(callback).indexOf('[native code]') !== -1) {
                        arguments[1] = Math.round(BOOST_DELAY_MS * 0.001);
                        if (diag) diag.boosted++;
                        sinceTap.boosted++;
                    }
                } catch (e) {}
            }
            return nativeSetTimeout.apply(this, arguments);
        };
        try { window.setTimeout.toString = function () { return fnToString.call(nativeSetTimeout); }; } catch (e) {}
    })();

    // ---------------------------------------------------------------------------------------
    // 2. Background play: keep the page visible and swallow visibility/pause triggers.
    // ---------------------------------------------------------------------------------------
    function isBrowsePage() {
        return config.role === 'browse';
    }

    function keepAlive() {
        return !isBrowsePage() && !!(config.bgPlay || config.pip);
    }

    (function installVisibilityHooks() {
        function override(target, prop, fakeValue) {
            var proto = Object.getPrototypeOf(target);
            var desc = null;
            while (proto && !desc) {
                desc = Object.getOwnPropertyDescriptor(proto, prop);
                proto = Object.getPrototypeOf(proto);
            }
            if (!desc || !desc.get) return;
            try {
                Object.defineProperty(target, prop, {
                    configurable: true,
                    get: function () { return keepAlive() ? fakeValue : desc.get.call(target); }
                });
            } catch (e) {}
        }
        override(document, 'hidden', false);
        override(document, 'webkitHidden', false);
        override(document, 'visibilityState', 'visible');
        override(document, 'webkitVisibilityState', 'visible');

        var nativeHasFocus = document.hasFocus;
        document.hasFocus = function () {
            return keepAlive() ? true : nativeHasFocus.call(document);
        };

        // Registered at document start, so these capture listeners run before any of YouTube's.
        var swallowed = ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide', 'freeze'];
        swallowed.forEach(function (type) {
            window.addEventListener(type, function (event) {
                // Only page-level events; element blur (e.g. the search box) must still work.
                var t = event.target;
                if (keepAlive() && (t === window || t === document)) event.stopImmediatePropagation();
            }, true);
        });
    })();

    // ---------------------------------------------------------------------------------------
    // 3. Cosmetic filtering
    // ---------------------------------------------------------------------------------------
    var COSMETIC_CSS = [
        'ytm-promoted-sparkles-web-renderer',
        'ytm-promoted-sparkles-text-search-renderer',
        'ytm-promoted-video-renderer',
        'ytm-companion-ad-renderer',
        'ytm-companion-slot',
        'ytm-in-feed-ad-layout-renderer',
        'ytm-ad-slot-renderer',
        'ad-slot-renderer',
        'ytm-rich-item-renderer:has(> ad-slot-renderer)',
        'ytm-rich-item-renderer:has(ytm-ad-slot-renderer)',
        'ytm-item-section-renderer:has(> ytm-ad-slot-renderer)',
        'ytm-mealbar-promo-renderer',
        'ytmusic-mealbar-promo-renderer',
        'ytm-statement-banner-renderer',
        'ytm-brand-video-singleton-renderer',
        'ytm-paid-content-overlay-renderer',
        'ytm-search-pyv-renderer',
        'ytd-ad-slot-renderer',
        'ytd-in-feed-ad-layout-renderer',
        'ytd-banner-promo-renderer',
        'ytd-promoted-sparkles-web-renderer',
        'ytd-player-legacy-desktop-watch-ads-renderer',
        'ytd-enforcement-message-view-model',
        '#player-ads',
        '#masthead-ad',
        '.ytp-ad-overlay-container',
        '.ytp-ad-overlay-slot',
        '.ytp-ad-image-overlay',
        '.ytp-ad-text-overlay',
        '.ytp-ad-action-interstitial',
        '.ytp-featured-product',
        '.ytm-promoted-sparkles-web-renderer'
    ].join(',\n') + ' { display: none !important; }';

    // Kept as in the original app (known to work in Android PiP): everything but the player is
    // hidden and both the player and the <video> are pinned to the viewport.
    var PIP_CSS = [
        'html, body { overflow: hidden !important; background: #000 !important;',
        '  margin: 0 !important; padding: 0 !important; }',
        'ytm-mobile-topbar-renderer, #header-bar, .player-controls-content, #player-control-overlay,',
        '.ytp-chrome-top, .ytp-chrome-bottom, ytm-single-column-watch-next-results-renderer,',
        'ytm-item-section-renderer, ytm-comments-entry-point-header-renderer',
        '{ display: none !important; opacity: 0 !important; }',
        '#player-container-id, #player, #movie_player, .html5-video-player, .html5-main-video, video {',
        '  position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important;',
        '  bottom: 0 !important; width: 100vw !important; height: 100vh !important;',
        '  max-width: 100vw !important; max-height: 100vh !important; z-index: 2147483647 !important;',
        '  background: #000 !important; object-fit: contain !important; }'
    ].join('\n');

    function ensureStyle(id, css, enabled) {
        var root = document.head || document.documentElement;
        var el = document.getElementById(id);
        if (!enabled) {
            if (el) el.remove();
            return;
        }
        if (!root) return;
        if (!el) {
            el = document.createElement('style');
            el.id = id;
            el.textContent = css;
            root.appendChild(el);
        } else if (el.parentNode !== root && root === document.head) {
            root.appendChild(el);
        }
    }

    // m.youtube.com's own bottom bar (Home / Shorts / Subscriptions / You): the app shows its own
    // tabs under the page instead. YouTube Music's bar is left alone (Home / Explore / Library are
    // its own sections).
    var APP_UI_CSS = 'ytm-pivot-bar-renderer { display: none !important; }';
    var isMobileYouTube = location.hostname === 'm.youtube.com';

    function applyStyles() {
        ensureStyle('streamshield-cosmetic', COSMETIC_CSS, config.adBlock);
        ensureStyle('streamshield-pip', PIP_CSS, config.pip);
        ensureStyle('streamshield-app-ui', APP_UI_CSS, isMobileYouTube);
    }

    // The YouTube logo in m.youtube's top bar opens the app's Home tab rather than YouTube's
    // signed-out home page. Registered on window in the capture phase at document start, so it
    // runs before YouTube's own click handling.
    var HOME_LOGO_LABEL = /^YouTube( Home)?$/i;

    function homeLogoFor(target) {
        if (!isMobileYouTube || !target || !target.closest) return null;
        var bar = target.closest('ytm-mobile-topbar-renderer, #header-bar');
        if (!bar) return null;
        var logo = target.closest('ytm-home-logo');
        if (logo) return logo;
        var el = target.closest('a, button, [role="button"]');
        if (!el || !bar.contains(el)) return null;
        var href = el.getAttribute('href');
        var aria = (el.getAttribute('aria-label') || '').trim();
        if (href === '/' || href === 'https://m.youtube.com/' || HOME_LOGO_LABEL.test(aria)) return el;
        return null;
    }

    window.addEventListener('click', function (e) {
        if (!homeLogoFor(e.target)) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        report('onHomeLogo', location.href);
    }, true);

    // ---- player page / browse page routing ----------------------------------------------
    // Browse page: a tap on a video opens it in the player page (this page stays where it is).
    // Player page: a tap on a channel, playlist, hashtag or search link opens it in the browse
    // page, and the video keeps playing in the mini-player.
    var BROWSE_PATH = /^\/(@|channel\/|c\/|user\/|results|hashtag\/|playlist|feed\/|source\/)/;

    function isVideoPath(path) {
        return path === '/watch' || /^\/(shorts|embed)\/[^\/]/.test(path) || /^\/live\/[A-Za-z0-9_-]{6,}/.test(path);
    }

    function youTubeLinkFor(target) {
        var a = target && target.closest && target.closest('a[href]');
        if (!a) return null;
        var u;
        try { u = new URL(a.getAttribute('href'), location.href); } catch (e) { return null; }
        var host = u.hostname;
        if (host === 'youtu.be') return { url: u.href, video: true, browse: false };
        if (host !== 'm.youtube.com' && host !== 'www.youtube.com' && host !== 'youtube.com') return null;
        return { url: u.href, video: isVideoPath(u.pathname), browse: BROWSE_PATH.test(u.pathname) };
    }

    window.addEventListener('click', function (e) {
        if (!isMobileYouTube) return;
        var link = youTubeLinkFor(e.target);
        if (!link) return;
        if (isBrowsePage() && link.video) {
            e.preventDefault();
            e.stopImmediatePropagation();
            report('onOpenVideo', link.url);
        } else if (!isBrowsePage() && link.browse) {
            e.preventDefault();
            e.stopImmediatePropagation();
            report('onOpenBrowse', link.url);
        } else if (isBrowsePage() && !link.video) {
            startNavFade();
        }
    }, true);

    // Player page: a search typed into YouTube's search box opens the results in the browse page.
    function isSearchInput(el) {
        if (!el || el.tagName !== 'INPUT') return false;
        var hints = (el.type || '') + ' ' + (el.name || '') + ' ' + (el.className || '') + ' ' +
            (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('placeholder') || '');
        return /search/i.test(hints);
    }

    function openSearchInBrowse(input, e) {
        var q = (input.value || '').trim();
        if (!q) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        try { input.blur(); } catch (err) {}
        report('onOpenBrowse', location.origin + '/results?search_query=' + encodeURIComponent(q));
    }

    window.addEventListener('keydown', function (e) {
        if (isBrowsePage() || !isMobileYouTube || e.key !== 'Enter' || !isSearchInput(e.target)) return;
        openSearchInBrowse(e.target, e);
    }, true);

    window.addEventListener('submit', function (e) {
        if (isBrowsePage() || !isMobileYouTube || !e.target || !e.target.querySelector) return;
        var input = e.target.querySelector('input');
        if (isSearchInput(input)) openSearchInBrowse(input, e);
    }, true);

    // YouTube's "Open App" and "Sign in" buttons in the top bars are pointless in this app (it has
    // no accounts). They are matched by label (English UI) rather than class names, which YouTube
    // changes often. The bot-check "Sign in" inside the player is page content and is left alone.
    // Only the handful of buttons inside the top bar are checked, from tick(), so this stays cheap.
    var OPEN_APP_LABEL = /^(open (the )?app|sign in)$/i;
    // m.youtube.com's top bar, or YouTube Music's nav bar / page header.
    var TOP_BARS = 'ytm-mobile-topbar-renderer, #header-bar, ytmusic-nav-bar, header';

    function hideOpenAppButton() {
        var bars = document.querySelectorAll(TOP_BARS);
        for (var b = 0; b < bars.length; b++) hideOpenAppIn(bars[b]);
    }

    // "Use the app" prompts that still appear (other sources than the player response): press the
    // prompt's own close button, or hide it. Found by the label of the button that opens the app
    // ("Watch in YouTube app", "Open App", "Get the app", "Open YouTube Music", "Listen in the
    // YouTube Music app"; English UI), or by a dialog's own text about the app when its button
    // says something else. YouTube Music shows these after a page load (pull-to-refresh, autoplay
    // opening the next song's page). Only short labels and small dialogs are read.
    var APP_UPSELL_LABEL = new RegExp('^(' + [
        '(watch|continue|open|listen|play) (in|on|with) (the )?(youtube( music)? )?app',
        '(open|get|use) (the )?(youtube( music)? )?app',
        'open youtube music'
    ].join('|') + ')$', 'i');
    var CLOSE_LABEL = new RegExp('^(' + [
        'close', 'dismiss', 'not now', 'no,? thanks', 'cancel', 'maybe later',
        '(continue|stay) (in|on|with) (the )?(browser|web|mobile web|website)',
        'use (the )?(browser|web|website)( version)?'
    ].join('|') + ')$', 'i');
    var APP_PROMPTS = '[role="dialog"], [aria-modal="true"], dialog, tp-yt-paper-dialog, ' +
        'ytmusic-mealbar-promo-renderer, ytm-mealbar-promo-renderer';
    var APP_PROMPT_TEXT = /\b(youtube( music)? app|the app|open app|get the app)\b/i;

    function dismissAppUpsell() {
        if (!document.body) return false;
        if (!closeUpsellByButton() && !closeAppPrompt()) return false;
        if (isBrowsePage()) return true;
        // The prompt holds back playback; start the video unless the user paused it (or, after
        // autoplay moved on, wherever it is: resumeAfterAdvance does the same).
        setTimeout(function () {
            var v = findVideo();
            if (v && v.paused && !v.ended && !userPaused && (v.currentTime < 2 || lastAdvanceAt)) resumeVideo(v);
        }, 300);
        return true;
    }

    function closeUpsellByButton() {
        // Buttons, plus links only inside dialogs (feeds have hundreds of links).
        var buttons = document.querySelectorAll(
            'button, [role="button"], [role="dialog"] a, [aria-modal="true"] a, dialog a'
        );
        for (var i = 0; i < buttons.length; i++) {
            var text = (buttons[i].textContent || '').trim();
            if (!text || text.length > 40 || !APP_UPSELL_LABEL.test(text)) continue;
            if (buttons[i].offsetParent === null && getComputedStyle(buttons[i]).position !== 'fixed') continue;
            if (buttons[i].closest(TOP_BARS)) continue;   // hideOpenAppButton() takes care of those
            if (closeDialogAround(buttons[i])) return true;
        }
        return false;
    }

    // Text of a small element with its pieces kept apart ("…app" + "Open" is not "appOpen").
    function spacedText(el) {
        var parts = [];
        var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
        for (var n = walker.nextNode(); n; n = walker.nextNode()) parts.push(n.nodeValue);
        return parts.join(' ').replace(/\s+/g, ' ').trim();
    }

    function closeAppPrompt() {
        var boxes = document.querySelectorAll(APP_PROMPTS);
        for (var i = 0; i < boxes.length; i++) {
            var box = boxes[i];
            if (box.style.display === 'none' || box.closest(TOP_BARS)) continue;
            var r = box.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) continue;
            if ((box.textContent || '').length > 1000) continue;
            var text = spacedText(box);
            if (!text || text.length > 600 || !APP_PROMPT_TEXT.test(text)) continue;
            var video = findVideo();
            if (video && box.contains(video)) continue;
            var controls = box.querySelectorAll('button, [role="button"]');
            for (var j = 0; j < controls.length; j++) {
                var label = (controls[j].getAttribute('aria-label') || controls[j].textContent || '').trim();
                if (CLOSE_LABEL.test(label)) {
                    controls[j].click();
                    return true;
                }
            }
            box.style.setProperty('display', 'none', 'important');
            hideOverlayBackdrops();
            return true;
        }
        return false;
    }

    function closeDialogAround(el) {
        var video = findVideo();
        var box = el.parentElement;
        for (var depth = 0; box && box !== document.body && depth < 8; depth++, box = box.parentElement) {
            if (video && box.contains(video)) break;   // walked out of the dialog
            var controls = box.querySelectorAll('button, [role="button"]');
            for (var j = 0; j < controls.length; j++) {
                var c = controls[j];
                if (c === el || c.contains(el)) continue;
                var label = (c.getAttribute('aria-label') || c.textContent || '').trim();
                if (CLOSE_LABEL.test(label)) {
                    c.click();
                    return true;
                }
            }
        }
        var dialog = el.closest('[role="dialog"], dialog, [aria-modal="true"]');
        if (dialog) {
            dialog.style.setProperty('display', 'none', 'important');
            hideOverlayBackdrops();
            return true;
        }
        return false;
    }

    // A dialog hidden by hand can leave its full-screen backdrop behind, which (even when
    // transparent) takes every tap meant for the page underneath.
    var BACKDROP_SELECTOR = 'c3-overlay, .c3-overlay, tp-yt-iron-overlay-backdrop, iron-overlay-backdrop, ' +
        '.dialog-backdrop, [class*="overlay-backdrop"]';

    function hideOverlayBackdrops() {
        var list = document.querySelectorAll(BACKDROP_SELECTOR);
        for (var i = 0; i < list.length; i++) {
            if (getComputedStyle(list[i]).position === 'fixed') list[i].style.setProperty('display', 'none', 'important');
        }
    }

    function hideOpenAppIn(bar) {
        var candidates = bar.querySelectorAll('button, a, [role="button"]');
        for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            var text = (el.textContent || '').trim();
            var aria = (el.getAttribute('aria-label') || '').trim();
            if (!OPEN_APP_LABEL.test(text) && !OPEN_APP_LABEL.test(aria)) continue;
            var target = (el.closest && el.closest('ytm-button-renderer')) || el;
            if (target.style.display !== 'none') target.style.setProperty('display', 'none', 'important');
        }
    }

    // ---------------------------------------------------------------------------------------
    // 4. Player hooks: ad fallback, speed, SponsorBlock, state reporting
    // ---------------------------------------------------------------------------------------
    var hookedPlayer = null;
    var hookedVideo = null;
    var playerObserver = null;
    var adActive = false;
    var savedMuted = false;
    var settingRate = false;
    var lastLoadAt = 0;
    var userPaused = false;      // pause requested by the user through the app (notification, PiP button)
    var pipKeeper = null;
    var adTimer = null;
    // Start-up diagnostics for the current video (shown in the app's settings).
    function newDiag(videoId, navAt) {
        return { videoId: videoId, navAt: navAt, loadMs: -1, startMs: -1, adMs: 0, adSince: 0, pruned: 0, boosted: 0 };
    }
    var diag = newDiag('', Date.now() - Math.round((window.performance && performance.now()) || 0));
    // PiP pauses: refused page calls (YouTube's own code) vs pauses that came from the browser.
    var pipStats = { blocked: 0, system: 0, stalls: 0 };
    var allowPauseCall = false;   // true while the app itself pauses
    var lastPauseCallAt = 0;

    // In PiP the only legitimate pause is the user's (through the app's PiP button). YouTube's
    // player pauses the video when it enters the PiP window; refusing that call is what keeps
    // playback smooth (resuming afterwards made it stutter: pause, play, pause...).
    (function installPauseGuard() {
        var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
        if (!proto || !proto.pause) return;
        var nativePause = proto.pause;
        proto.pause = function () {
            if (config.pip && !userPaused && !allowPauseCall && this.tagName === 'VIDEO' && !this.ended) {
                pipStats.blocked++;
                resyncPlayer();
                return;
            }
            lastPauseCallAt = Date.now();
            return nativePause.apply(this, arguments);
        };
    })();

    // The refused pause leaves YouTube's player believing it is paused, and a "paused" player
    // stops downloading: invisible for normal videos (long buffer), but live streams run dry
    // every few seconds. Tell the player to play again (at most every 2 s).
    var lastResyncAt = 0;
    function resyncPlayer() {
        var now = Date.now();
        if (now - lastResyncAt < 2000) return;
        lastResyncAt = now;
        setTimeout(function () {
            var player = hookedPlayer || findPlayer();
            if (config.pip && !userPaused && player && typeof player.playVideo === 'function') {
                try { player.playVideo(); } catch (e) {}
            }
        }, 0);
    }

    // Browse page: refuse playback (feed previews, or a watch page reached before the app moved
    // the video to the player page).
    (function installBrowsePlayBlock() {
        if (!isBrowsePage()) return;
        var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
        if (!proto || !proto.play) return;
        proto.play = function () {
            try { this.pause(); } catch (e) {}
            return Promise.reject(new DOMException('Videos play in the player page', 'NotAllowedError'));
        };
    })();

    // m.youtube.com outside a watch page (no video id in the address): YouTube plays feed items
    // there as muted previews. Those are never unmuted (see ensureSound). The player page no
    // longer opens on YouTube's home page (BrowserController), so normally it never shows one.
    // Refusing the previews' play() here instead coincided with a ~30 s black start screen on a
    // phone (YouTube's preview code probably kept retrying); do not reintroduce that.
    function outsideWatchPage() {
        return isMobileYouTube && !currentVideoId();
    }

    function pauseVideo(v) {
        allowPauseCall = true;
        try { v.pause(); } finally { allowPauseCall = false; }
    }

    // Resume through YouTube's player API when available so its own state stays in sync;
    // calling video.play() behind its back makes the player pause it again.
    function resumeVideo(v) {
        var player = hookedPlayer || findPlayer();
        if (player && typeof player.playVideo === 'function') {
            try { player.playVideo(); } catch (e) {}
        }
        if (v && v.paused) v.play().catch(function () {});
    }

    // ---- taps on the page's own play/pause button ----------------------------------------
    // Reported from a phone: YouTube's play/pause on the video stopped reacting while the app's
    // mini-player could still pause. A tap on a visible play/pause control that has not changed
    // playback after PLAY_TAP_CHECK_MS is applied by the app; each such fix is counted for
    // Settings > Diagnostics together with the element that was on top at the tapped point.
    var PLAY_TAP_CHECK_MS = 400;
    var PLAY_PAUSE_LABEL = /^(pause|play)( video)?( \(k\))?$/i;
    var PLAYER_AREA = '#movie_player, .html5-video-player, #player-container-id, ytm-custom-control, ' +
        'ytmusic-player-bar, ytmusic-player';
    var tapFix = { count: 0, top: '' };
    var pendingPlayTap = null;
    var lastPointerAt = 0;

    function isPlayPauseControl(el) {
        var cls = typeof el.className === 'string' ? el.className : '';
        if (/play-pause|ytp-play-button/.test(cls) || el.id === 'play-pause-button') return true;
        return PLAY_PAUSE_LABEL.test((el.getAttribute('aria-label') || el.getAttribute('title') || '').trim());
    }

    // Laid out, not display:none / visibility:hidden, and not faded out (YouTube hides its
    // controls by fading them).
    function shownOnScreen(el) {
        var r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) return false;
        var opacity = 1;
        for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
            var cs = getComputedStyle(n);
            if (cs.display === 'none' || cs.visibility === 'hidden') return false;
            var o = parseFloat(cs.opacity);
            opacity *= isNaN(o) ? 1 : o;
            if (opacity < 0.5) return false;
            if (n === document.body) break;
        }
        return true;
    }

    function playPauseControlAt(x, y) {
        var areas = document.querySelectorAll(PLAYER_AREA);
        for (var a = 0; a < areas.length; a++) {
            var box = areas[a].getBoundingClientRect();
            if (x < box.left || x > box.right || y < box.top || y > box.bottom) continue;
            var controls = areas[a].querySelectorAll('button, [role="button"]');
            for (var i = 0; i < controls.length; i++) {
                var el = controls[i];
                if (!isPlayPauseControl(el)) continue;
                var r = el.getBoundingClientRect();
                if (x < r.left - 6 || x > r.right + 6 || y < r.top - 6 || y > r.bottom + 6) continue;
                if (shownOnScreen(el)) return el;
            }
        }
        return null;
    }

    function describeElement(el, control) {
        if (!el) return 'nothing';
        if (el === control || (control && control.contains(el))) return 'the button itself';
        var cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/)[0] : '';
        return (el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (cls ? '.' + cls : '')).slice(0, 60);
    }

    window.addEventListener('pointerdown', function (e) {
        lastPointerAt = Date.now();
        lastPointerTarget = e.target;
        pendingPlayTap = null;
        if (isBrowsePage() || e.isPrimary === false) return;
        var control = playPauseControlAt(e.clientX, e.clientY);
        var v = control && findVideo();
        if (!v || v.ended || adActive) return;
        pendingPlayTap = { at: Date.now(), x: e.clientX, y: e.clientY, wantPause: !v.paused, control: control };
    }, true);

    window.addEventListener('pointerup', function (e) {
        var tap = pendingPlayTap;
        pendingPlayTap = null;
        if (!tap || Date.now() - tap.at > 800 || Math.abs(e.clientX - tap.x) > 16 || Math.abs(e.clientY - tap.y) > 16) return;
        userPaused = tap.wantPause;
        setTimeout(function () {
            var v = findVideo();
            if (!v || v.ended || adActive || tap.wantPause === v.paused) return;   // the page handled it
            tapFix.count++;
            tapFix.top = describeElement(document.elementFromPoint(tap.x, tap.y), tap.control);
            if (tap.wantPause) pauseVideo(v); else resumeVideo(v);
            reportState(true);
        }, PLAY_TAP_CHECK_MS);
    }, true);

    // ---- sound: undo YouTube's muted autoplay ---------------------------------------------
    // YouTube starts a video muted ("Tap to unmute") when its page was opened without a tap on
    // it: the app's own page loads (the first video after opening the app, links opened in the
    // app) and autoplay while the screen is off. The WebView allows sound without a tap, so the
    // video is unmuted when it plays, through the player's API so YouTube's own state follows.
    // A mute the user chose on the page (a tap on a mute/volume control) is kept, also for later
    // videos in this tab, until they unmute. Ads keep the fallback's muting (section 4).
    var lastPointerTarget = null;
    var userMuted = false;
    var USER_MUTED_FLAG = 'streamshield.userMuted';
    var MAX_UNMUTES_PER_VIDEO = 5;
    var unmutes = { id: '', count: 0 };
    var unmuting = false;
    try { userMuted = sessionStorage.getItem(USER_MUTED_FLAG) === '1'; } catch (e) {}

    function isMuteControl(el) {
        for (var n = el, depth = 0; n && n.nodeType === 1 && depth < 6; n = n.parentElement, depth++) {
            var cls = typeof n.className === 'string' ? n.className : '';
            var label = (n.getAttribute('aria-label') || n.getAttribute('title') || '') + ' ' + cls;
            if (/mute|volume/i.test(label)) return true;
        }
        return false;
    }

    function setUserMuted(muted) {
        userMuted = muted;
        try {
            if (muted) sessionStorage.setItem(USER_MUTED_FLAG, '1');
            else sessionStorage.removeItem(USER_MUTED_FLAG);
        } catch (e) {}
    }

    function ensureSound(video) {
        // Only the video of a watch page (or a song): feed previews stay as YouTube started them.
        if (!video || adActive || userMuted || isBrowsePage() || outsideWatchPage()) return;
        var player = hookedPlayer || findPlayer();
        var playerMuted = false;
        try { playerMuted = !!(player && typeof player.isMuted === 'function' && player.isMuted()); } catch (e) {}
        if (!video.muted && video.volume > 0 && !playerMuted) return;
        // At most a few times per video, so the app never fights the page in a loop.
        var id = playingVideoId();
        if (unmutes.id !== id) unmutes = { id: id, count: 0 };
        if (unmutes.count >= MAX_UNMUTES_PER_VIDEO) return;
        unmutes.count++;
        unmuting = true;
        try {
            if (player && typeof player.unMute === 'function') player.unMute();
            if (video.volume === 0) {
                if (player && typeof player.setVolume === 'function') player.setVolume(100);
                video.volume = 1;
            }
            video.muted = false;
        } catch (e) {
        } finally {
            unmuting = false;
        }
    }

    function onVolumeChange(video) {
        if (adActive || unmuting) return;
        var muted = video.muted || video.volume === 0;
        if (Date.now() - lastPointerAt < 1500 && isMuteControl(lastPointerTarget)) {
            setUserMuted(muted);   // the user's own choice on the page
            return;
        }
        if (muted && !video.paused) ensureSound(video);
    }

    var sponsor = { videoId: '', segments: [] };
    var lastStateJson = '';
    var lastPos = 0;

    function currentVideoId() {
        try {
            var url = new URL(location.href);
            var v = url.searchParams.get('v');
            if (v) return v;
            var m = url.pathname.match(/^\/(?:shorts|embed|live)\/([A-Za-z0-9_-]{6,})/);
            return m ? m[1] : '';
        } catch (e) {
            return '';
        }
    }

    function findPlayer() {
        return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    }

    function findVideo() {
        var player = hookedPlayer || findPlayer();
        return (player && player.querySelector('video')) || document.querySelector('video');
    }

    function isAdShowing(player) {
        if (!player || !player.classList) return false;
        return player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
    }

    function setRate(video, rate) {
        if (!video || Math.abs(video.playbackRate - rate) < 0.01) return;
        settingRate = true;
        try { video.playbackRate = rate; } catch (e) {}
        settingRate = false;
    }

    function clickSkipButtons() {
        var buttons = document.querySelectorAll(
            '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
            '.ytp-ad-skip-button-slot button, .ytp-ad-overlay-close-button'
        );
        for (var i = 0; i < buttons.length; i++) {
            try { buttons[i].click(); } catch (e) {}
        }
    }

    function fastForwardAd() {
        var video = findVideo();
        var player = hookedPlayer || findPlayer();
        if (player && typeof player.skipAd === 'function') {
            try { player.skipAd(); } catch (e) {}
        }
        clickSkipButtons();
        if (!video) return;
        video.muted = true;
        setRate(video, 16);
        var d = video.duration;
        if (isFinite(d) && d > 0 && video.currentTime < d - 0.1) {
            try { video.currentTime = d; } catch (e) {}
        }
    }

    function onAdStateChanged() {
        var showing = config.adBlock && isAdShowing(hookedPlayer);
        if (showing && !adActive) {
            adActive = true;
            diag.adSince = Date.now();
            var video = findVideo();
            savedMuted = video ? video.muted : false;
            report('onAdBlocked', 'skip');
            fastForwardAd();
            // Media events can stall while an ad loads; keep nudging, but only while it lasts.
            if (!adTimer) adTimer = setInterval(fastForwardAd, 100);
        } else if (showing) {
            fastForwardAd();
        } else if (!showing && adActive) {
            adActive = false;
            if (adTimer) { clearInterval(adTimer); adTimer = null; }
            if (diag.adSince) { diag.adMs += Date.now() - diag.adSince; diag.adSince = 0; }
            var v = findVideo();
            if (v) {
                v.muted = savedMuted;
                setRate(v, config.speed);
                // Muted before the ad by YouTube's muted autoplay (not by the user): sound on.
                if (!v.paused) ensureSound(v);
            }
        }
    }

    function applySpeed() {
        if (adActive) return;
        setRate(findVideo(), config.speed);
    }

    function checkSponsorSegments(video) {
        if (!config.sponsorSkip || adActive || !sponsor.segments.length) return;
        if (sponsor.videoId !== currentVideoId()) return;
        var t = video.currentTime;
        for (var i = 0; i < sponsor.segments.length; i++) {
            var seg = sponsor.segments[i];
            if (t >= seg[0] && t < seg[1] - 0.5) {
                try { video.currentTime = seg[1]; } catch (e) {}
                report('onSponsorSkipped', String(Math.round(seg[1] - seg[0])));
                return;
            }
        }
    }

    function textOf(selector) {
        var el = document.querySelector(selector);
        var text = el && (el.textContent || '').trim();
        return text || '';
    }

    function reportState(force) {
        if (isBrowsePage()) return;
        var video = hookedVideo;
        var playing = !!(video && !video.paused && !video.ended && !adActive);
        var videoId = currentVideoId();
        if (!videoId && isMusicSite() && details && video && !video.ended) {
            videoId = details.videoId;
        }
        var d = details && details.videoId === videoId ? details : null;
        // The player response names title and channel; the page is read only as a fallback.
        var title = d && d.title ? '' : textOf('.slim-video-information-title, ytm-slim-video-metadata-section-renderer h2, h1.title') ||
            (document.title || '').replace(/ - YouTube( Music)?$/, '').replace(/^YouTube( Music)?$/, '');
        var channel = d && d.author ? '' : textOf('ytm-slim-owner-renderer .slim-owner-channel-name, .slim-owner-icon-and-title .subhead, #channel-name a');
        if (video && !adActive) {
            // Paused: exact position (used to resume). Playing: 10 s steps, so the state is not
            // re-reported every tick.
            var t = video.currentTime || 0;
            lastPos = video.paused || video.ended ? Math.floor(t) : Math.floor(t / 10) * 10;
        }
        var mm = mediaMetadata();
        var state = {
            videoId: videoId,
            url: location.href,
            title: (d && d.title) || title || (mm && mm.title) || '',
            channel: (d && d.author) || channel || (mm && mm.artist) || '',
            channelId: d ? d.channelId : '',
            lengthSec: d ? d.lengthSeconds : 0,
            views: d ? d.viewCount : 0,
            category: d ? d.category : '',
            pos: lastPos,
            ended: !!(video && video.ended),
            duration: video && isFinite(video.duration) ? Math.round(video.duration) : 0,
            playing: playing,
            width: video ? video.videoWidth : 0,
            height: video ? video.videoHeight : 0,
            // Start-up diagnostics: ms from navigation to first frame, ms spent in ad state,
            // ad entries removed from YouTube's responses.
            loadMs: diag.loadMs,
            startMs: diag.startMs,
            pipBlocked: pipStats.blocked,
            pipSystem: pipStats.system,
            pipStalls: pipStats.stalls,
            adMs: diag.adMs,
            pruned: diag.pruned,
            boosted: diag.boosted,
            // The page's copy of the app's PiP flag (the app re-sends its config if they differ).
            cfgPip: !!config.pip,
            tapFix: tapFix.count,
            tapTop: tapFix.top,
            // Last time autoplay moved on while the app was hidden, and how.
            adv: lastAdvance.how,
            advAt: lastAdvance.at
        };
        var json = JSON.stringify(state);
        if (!force && json === lastStateJson) return;
        lastStateJson = json;
        report('onState', json);
    }

    function hookVideo(video) {
        if (!video || video === hookedVideo) return;
        hookedVideo = video;
        video.addEventListener('loadedmetadata', function () {
            lastLoadAt = Date.now();
            applyPendingSeek();
            trackNavigation();
            if (diag.loadMs < 0 && diag.videoId && diag.videoId === currentVideoId()) {
                diag.loadMs = Date.now() - diag.navAt;
            }
            applySpeed();
            onAdStateChanged();
            cancelAutoAdvance();
            resumeAfterAdvance(video);
            reportState();
        });
        video.addEventListener('durationchange', function () { if (adActive) fastForwardAd(); });
        video.addEventListener('playing', function () {
            userPaused = false;
            cancelAutoAdvance();
            if (lastAdvanceAt && !adActive) clearAdvanceFlag();
            var id = currentVideoId();
            if (id && diag.videoId === id && diag.startMs < 0 && !adActive) {
                diag.startMs = Date.now() - diag.navAt;
            }
            applySpeed();
            ensureSound(video);
            reportState();
        });
        video.addEventListener('volumechange', function () { onVolumeChange(video); });
        video.addEventListener('pause', function () {
            // Paused right after a tap in the page (its own controls): the user's choice, so the
            // app does not start it again (e.g. after closing an app-upsell dialog).
            if (!config.pip && !allowPauseCall && Date.now() - lastPointerAt < 1500) userPaused = true;
            reportState();
            if (config.pip && !userPaused && !video.ended) {
                // Not a page call (those are refused above): the browser paused it.
                if (Date.now() - lastPauseCallAt > 200) pipStats.system++;
                resumeVideo(video);
            }
        });
        video.addEventListener('ended', function () {
            reportState();
            if (hiddenForAutoplay()) scheduleAutoAdvance(video);
            else scheduleAutoplay();
        });
        video.addEventListener('waiting', function () {
            if (config.pip) pipStats.stalls++;
        });
        video.addEventListener('ratechange', function () {
            if (settingRate || adActive || Math.abs(video.playbackRate - config.speed) < 0.01) return;
            var rate = video.playbackRate;
            if (!video.paused && Date.now() - lastLoadAt > 1500 && rate >= 0.25 && rate <= 3) {
                // Changed from YouTube's own speed menu: adopt it so the app stays in sync.
                config.speed = rate;
                report('onSpeedChanged', String(rate));
            } else {
                // YouTube resetting the rate while loading a new video: keep the user's speed.
                setRate(video, config.speed);
            }
        });
        video.addEventListener('timeupdate', function () {
            if (adActive) fastForwardAd();
            else checkSponsorSegments(video);
        });
        applySpeed();
        // A page opened by autoplay may have loaded the video before this hook was attached.
        resumeAfterAdvance(video);
        reportState();
    }

    // ---- next / previous / autoplay ------------------------------------------------------

    function upNextLink() {
        var current = currentVideoId();
        var links = document.querySelectorAll(
            'ytm-single-column-watch-next-results-renderer a[href*="watch?v="], #related a[href*="watch?v="], a[href*="/watch?v="]'
        );
        for (var i = 0; i < links.length; i++) {
            var m = (links[i].getAttribute('href') || '').match(/[?&]v=([A-Za-z0-9_-]{6,})/);
            if (m && m[1] !== current) return links[i];
        }
        return null;
    }

    function next() {
        userPaused = false;
        if (mediaHandlers.nexttrack) {
            try { mediaHandlers.nexttrack({ action: 'nexttrack' }); return 'mediaSession'; } catch (e) {}
        }
        var player = hookedPlayer || findPlayer();
        if (player && typeof player.nextVideo === 'function') {
            try { player.nextVideo(); return 'player'; } catch (e) {}
        }
        var link = upNextLink();
        if (link) {
            link.click();
            return 'link';
        }
        return '';
    }

    function previous() {
        userPaused = false;
        var video = findVideo();
        // Like the YouTube app: past the first few seconds, "previous" restarts the video.
        if (video && video.currentTime > 5) {
            video.currentTime = 0;
            return 'restart';
        }
        if (mediaHandlers.previoustrack) {
            try { mediaHandlers.previoustrack({ action: 'previoustrack' }); return 'mediaSession'; } catch (e) {}
        }
        var player = hookedPlayer || findPlayer();
        if (player && typeof player.previousVideo === 'function') {
            try { player.previousVideo(); return 'player'; } catch (e) {}
        }
        if (video) video.currentTime = 0;
        return 'restart';
    }

    // On screen: if the page has not moved on by itself a few seconds after a video ended
    // (YouTube's own autoplay can be off), go to the next one. Hidden: scheduleAutoAdvance().
    var autoplayTimer = null;
    function scheduleAutoplay() {
        if (autoplayTimer) clearTimeout(autoplayTimer);
        if (!config.autoplay || adActive) return;
        var endedId = currentVideoId();
        autoplayTimer = setTimeout(function () {
            autoplayTimer = null;
            var v = findVideo();
            if (config.autoplay && !adActive && !userPaused && currentVideoId() === endedId && v && v.ended) next();
        }, AUTOPLAY_DELAY_MS);
    }
    var AUTOPLAY_DELAY_MS = 4000;

    // ---- autoplay while the app is hidden ----------------------------------------------------
    // With the screen off or the app in the background nothing is drawn, so YouTube's "Up next"
    // countdown (driven by on-screen animation) never finishes; in picture-in-picture it is hidden.
    // The original StreamShield found this and verified the remedy with the screen off: when a
    // video ends there, move on without the page's countdown.
    //  - m.youtube.com: open the video the watch-next response names (keeps the mix or playlist
    //    and the position in it). next() is only a fallback: this player does not know the
    //    playlist, and its nextVideo() leaves a mix for an unrelated suggestion.
    //  - YouTube Music: its own "next track" first (keeps the queue as the user arranged it); if
    //    the song has still not changed, open the next song's page.
    // The video opened this way is started even though nobody tapped it (resumeAfterAdvance).
    var ADVANCE_DELAY_MS = 1500;
    var MUSIC_PAGE_FALLBACK_MS = 4000;
    var ADVANCE_RESUME_MS = 30000;
    var ADVANCE_FLAG = 'streamshield.autoAdvanceAt';
    var ADVANCE_LOG = 'streamshield.lastAdvance';
    var advanceTimers = [];
    var lastAdvanceAt = 0;                    // set while a video opened by autoplay should start
    var lastAdvance = { how: '', at: 0 };     // for Settings > Diagnostics
    try {
        lastAdvanceAt = Number(sessionStorage.getItem(ADVANCE_FLAG)) || 0;
        var savedAdvance = JSON.parse(sessionStorage.getItem(ADVANCE_LOG) || 'null');
        if (savedAdvance && typeof savedAdvance.how === 'string') lastAdvance = savedAdvance;
    } catch (e) {}

    function hiddenForAutoplay() {
        return !!config.pip || config.appVisible === false;
    }

    function isMusicSite() {
        return location.hostname === 'music.youtube.com';
    }

    // On YouTube Music the address often has no video id (the song plays under another page).
    function playingVideoId() {
        return currentVideoId() || (isMusicSite() && details ? details.videoId : '');
    }

    function nextVideoUrl(info) {
        var e = info.endpoint;
        var url = location.origin + '/watch?v=' + encodeURIComponent(e.videoId);
        if (e.playlistId) url += '&list=' + encodeURIComponent(e.playlistId);
        // m.youtube.com's index parameter counts from 1, the endpoint's from 0.
        if (typeof e.index === 'number' && !isMusicSite()) url += '&index=' + (e.index + 1);
        return url;
    }

    function cancelAutoAdvance() {
        advanceTimers.forEach(clearTimeout);
        advanceTimers = [];
        if (autoplayTimer) { clearTimeout(autoplayTimer); autoplayTimer = null; }
    }

    function noteAdvance(how) {
        lastAdvance = { how: how, at: Date.now() };
        if (how !== 'none') lastAdvanceAt = lastAdvance.at;
        try {
            if (how !== 'none') sessionStorage.setItem(ADVANCE_FLAG, String(lastAdvanceAt));
            sessionStorage.setItem(ADVANCE_LOG, JSON.stringify(lastAdvance));
        } catch (e) {}
        reportState(true);
    }

    function clearAdvanceFlag() {
        lastAdvanceAt = 0;
        try { sessionStorage.removeItem(ADVANCE_FLAG); } catch (e) {}
    }

    function scheduleAutoAdvance(video) {
        cancelAutoAdvance();
        if (adActive || userPaused) return;
        var id = playingVideoId();
        var music = isMusicSite();
        var info = nextByVideo[id];
        // Mixes, playlists and YouTube Music's queue always continue (as on YouTube); a suggested
        // video only with the app's Autoplay setting on.
        if (!config.autoplay && !music && !(info && info.inPlaylist)) return;
        function stillStuck() {
            return playingVideoId() === id && video.ended && !userPaused && !adActive;
        }
        advanceTimers.push(setTimeout(function () {
            if (!stillStuck()) return;
            if (!hiddenForAutoplay()) { scheduleAutoplay(); return; }   // back on screen meanwhile
            if (music) {
                noteAdvance('queue');
                next();
                advanceTimers.push(setTimeout(function () {
                    if (!stillStuck() || !hiddenForAutoplay()) return;
                    var later = nextByVideo[id];
                    if (later) {
                        noteAdvance('page');
                        location.assign(nextVideoUrl(later));
                    } else {
                        noteAdvance('none');
                    }
                }, MUSIC_PAGE_FALLBACK_MS));
                return;
            }
            var known = nextByVideo[id];
            if (known) {
                if (!config.autoplay && !known.inPlaylist) return;
                noteAdvance('page');
                location.assign(nextVideoUrl(known));
                return;
            }
            noteAdvance(next() ? 'player' : 'none');
        }, ADVANCE_DELAY_MS));
    }

    // After autoplay moved on (possibly to a freshly loaded page), start the new video even
    // though nobody tapped it. Called when a video loads, when it is hooked and on each tick.
    function resumeAfterAdvance(video) {
        if (!lastAdvanceAt) return;
        if (Date.now() - lastAdvanceAt > ADVANCE_RESUME_MS || userPaused) { clearAdvanceFlag(); return; }
        if (!video || video.ended || adActive || video.readyState < 1) return;
        if (!video.paused) { clearAdvanceFlag(); return; }
        if (!(hiddenForAutoplay() || config.bgPlay)) return;
        setTimeout(function () {
            if (lastAdvanceAt && video.paused && !video.ended && !userPaused && !adActive) resumeVideo(video);
        }, 400);
    }

    function hookPlayer() {
        var player = findPlayer();
        if (player && player !== hookedPlayer) {
            if (playerObserver) playerObserver.disconnect();
            hookedPlayer = player;
            // adActive is kept: if the new player shows no ad, onAdStateChanged() below restores
            // sound and speed and stops the skip timer (resetting it here left the timer running).
            if (typeof MutationObserver === 'function') {
                // Only the player's own class attribute: cheap, and it is where YouTube flags ads.
                playerObserver = new MutationObserver(onAdStateChanged);
                playerObserver.observe(player, { attributes: true, attributeFilter: ['class'] });
            }
            onAdStateChanged();
        }
        var video = findVideo();
        if (video) hookVideo(video);
    }

    function trackNavigation() {
        var id = currentVideoId();
        if (!id || id === diag.videoId) return;
        if (!diag.videoId) {
            // First video since a navigation start (or page load): keep its start time and the
            // ads pruned from responses fetched on the way.
            diag.videoId = id;
        } else {
            // Video changed without a navigation event (e.g. autoplay): start timing now.
            diag = newDiag(id, Date.now());
        }
    }

    function markNavigationStart() {
        navStartAt = Date.now();
        diag = newDiag('', navStartAt);
        startNavFade();
    }

    // In-page navigation to m.youtube's home ("/") from another page (e.g. a Home link in a menu):
    // same as tapping the logo. A full page load of "/" (the app's own loads) is not reported.
    var lastPath = location.pathname;
    function checkHomeNavigation() {
        var path = location.pathname;
        if (isMobileYouTube && path === '/' && lastPath !== '/') report('onHomeLogo', location.href);
        lastPath = path;
    }

    // Something should be playing but is not: autoplay just moved on, or the page paused the
    // video in the last minute without the user asking through the app.
    var HELD_CHECK_MS = 60000;
    function playbackMayBeHeld() {
        if (lastAdvanceAt) return true;
        var v = hookedVideo;
        return !!(v && v.paused && !v.ended && !userPaused && !adActive &&
            Date.now() - lastPauseCallAt < HELD_CHECK_MS);
    }

    function inForeground() {
        return config.appVisible !== false || !!config.pip;
    }

    function tick() {
        if (inForeground()) {
            checkHomeNavigation();
            applyStyles();
            hideOpenAppButton();
            dismissAppUpsell();
            markSubscribeButtons();
            markLikeButtons();
        } else if (!isBrowsePage() && playbackMayBeHeld()) {
            // A "use the app" prompt that appeared while hidden (e.g. on the next song's page)
            // would hold playback until the app came back.
            dismissAppUpsell();
        }
        if (isBrowsePage()) return;
        trackNavigation();
        hookPlayer();
        applyPendingSeek();
        if (lastAdvanceAt) resumeAfterAdvance(hookedVideo);
        reportState();
    }

    // Heartbeat: 1.5 s while the app is on screen; 5 s in the background (only playback state);
    // the browse page does nothing while it is not shown.
    var HEARTBEAT_MS = 1500;
    var BACKGROUND_HEARTBEAT_MS = 5000;
    var HIDDEN_BROWSE_HEARTBEAT_MS = 10000;
    function heartbeat() {
        var hiddenBrowse = isBrowsePage() && document.hidden;
        if (!hiddenBrowse) tick();
        var delay = hiddenBrowse ? HIDDEN_BROWSE_HEARTBEAT_MS : (inForeground() ? HEARTBEAT_MS : BACKGROUND_HEARTBEAT_MS);
        setTimeout(heartbeat, delay);
    }

    function start() {
        tick();
        // YouTube is a single-page app: re-check after its own navigation events and on a slow
        // heartbeat for title changes and player re-creation (tick() skips page tidying while
        // the app is hidden).
        ['yt-navigate-finish', 'state-navigateend', 'yt-page-data-updated', 'popstate'].forEach(function (type) {
            window.addEventListener(type, function () {
                setTimeout(tick, 50);
                setTimeout(endNavFade, type === 'popstate' ? 250 : 60);
            });
        });
        ['yt-navigate-start', 'state-navigatestart'].forEach(function (type) {
            window.addEventListener(type, markNavigationStart);
        });
        setTimeout(heartbeat, HEARTBEAT_MS);
    }


    // ---------------------------------------------------------------------------------------
    // 6. Pull-down gesture (the app's pull-to-refresh, and pull-down-to-minimize on videos)
    // ---------------------------------------------------------------------------------------
    // The page keeps handling every touch (nothing is prevented); this only watches for a
    // vertical pull that starts at the top of the page (or, to minimize, on the video) and
    // reports its distance in CSS px. The app draws the indicator and decides what happens.
    var PULL_SLOP = 12;
    var PULL_TOP_BARS = 'ytm-mobile-topbar-renderer, #header-bar, ytmusic-nav-bar, header';
    var PULL_VIDEO_AREA = '#player-container-id, #player, #movie_player, .html5-video-player, video';
    var pull = null;
    var pullFrame = 0;

    function pullMode() {
        if (config.pip) return 'off';
        return config.pull === 'refresh' || config.pull === 'minimize' ? config.pull : 'off';
    }

    function pageAtTop() {
        var se = document.scrollingElement || document.documentElement;
        return (window.scrollY || window.pageYOffset || 0) <= 0 && (!se || se.scrollTop <= 0);
    }

    // Inside a box that scrolls on its own and is not at its top (a list in a sheet, etc.).
    function insideScrolledBox(el) {
        for (var n = el; n && n.nodeType === 1 && n !== document.body && n !== document.documentElement; n = n.parentElement) {
            if (n.scrollTop > 0 && n.scrollHeight > n.clientHeight) {
                var oy = getComputedStyle(n).overflowY;
                if (oy === 'auto' || oy === 'scroll') return true;
            }
        }
        return false;
    }

    function fixedAncestor(el) {
        for (var n = el; n && n.nodeType === 1 && n !== document.body; n = n.parentElement) {
            if (getComputedStyle(n).position === 'fixed') return n;
        }
        return null;
    }

    function pullStartsAt(target, mode) {
        if (!target || target.nodeType !== 1 || !target.closest) return false;
        if (document.fullscreenElement || document.webkitFullscreenElement) return false;
        var onVideo = !!target.closest(PULL_VIDEO_AREA);
        if (mode === 'minimize' && onVideo) return true;
        if (mode === 'refresh' && onVideo) return false;
        if (!pageAtTop() || insideScrolledBox(target)) return false;
        // Dialogs, sheets and YouTube Music's player page are fixed on top of the page: their own
        // gestures win. The top bars are fixed too but a pull may start there.
        var fixed = fixedAncestor(target);
        return !fixed || !!(fixed.matches && fixed.matches(PULL_TOP_BARS));
    }

    function endPull(cancelled) {
        var p = pull;
        pull = null;
        if (!p || !p.active) return;
        report('onPullEnd', JSON.stringify({
            mode: p.mode,
            distance: Math.round(p.distance || 0),
            velocity: Math.round((p.vy || 0) * 1000) / 1000,
            cancelled: !!cancelled
        }));
    }

    window.addEventListener('touchstart', function (e) {
        if (pull) endPull(true);
        var mode = pullMode();
        if (mode === 'off' || !e.touches || e.touches.length !== 1) return;
        if (!pullStartsAt(e.target, mode)) return;
        var t = e.touches[0];
        pull = { mode: mode, x0: t.clientX, y0: t.clientY, startY: 0, active: false, distance: 0,
            lastY: t.clientY, lastT: Date.now(), vy: 0 };
    }, { capture: true, passive: true });

    window.addEventListener('touchmove', function (e) {
        if (!pull) return;
        if (!e.touches || e.touches.length !== 1) { endPull(true); return; }
        var t = e.touches[0];
        var dx = t.clientX - pull.x0;
        var dy = t.clientY - pull.y0;
        if (!pull.active) {
            // Decide once: sideways or upwards is not a pull.
            if (Math.abs(dx) > PULL_SLOP && Math.abs(dx) >= Math.abs(dy)) { pull = null; return; }
            if (dy < -PULL_SLOP) { pull = null; return; }
            if (dy <= PULL_SLOP || dy < Math.abs(dx) * 1.3) return;
            if (pull.mode === 'refresh' && !pageAtTop()) { pull = null; return; }
            pull.active = true;
            pull.startY = t.clientY;
        }
        var now = Date.now();
        var dt = now - pull.lastT;
        if (dt > 0) pull.vy = 0.7 * ((t.clientY - pull.lastY) / dt) + 0.3 * pull.vy;
        pull.lastY = t.clientY;
        pull.lastT = now;
        pull.distance = Math.max(0, t.clientY - pull.startY);
        if (!pullFrame) {
            pullFrame = requestAnimationFrame(function () {
                pullFrame = 0;
                if (pull && pull.active) report('onPull', pull.mode + ':' + Math.round(pull.distance));
            });
        }
    }, { capture: true, passive: true });

    window.addEventListener('touchend', function () { endPull(false); }, { capture: true, passive: true });
    window.addEventListener('touchcancel', function () { endPull(true); }, { capture: true, passive: true });

    // ---------------------------------------------------------------------------------------
    // 7. Subscribe buttons: subscriptions are kept by the app (there is no Google account)
    // ---------------------------------------------------------------------------------------
    // A tap on YouTube's Subscribe button (video page, channel page, channel rows) goes to the
    // app instead of YouTube's sign-in prompt, and buttons show the app's subscribed state.
    var SUBSCRIBE_TEXT = /^subscribed?\b/i;
    var SUBSCRIBE_ARIA = /^(un)?subscribed?\b/i;
    var SUBSCRIBE_HOSTS = 'ytm-subscribe-button-renderer, yt-subscribe-button-view-model, ' +
        'ytmusic-subscribe-button-renderer, [class*="subscribe-button"]';
    var CHANNEL_LINK = 'a[href^="/channel/UC"], a[href^="/@"], a[href*="youtube.com/channel/UC"], a[href*="youtube.com/@"]';
    var CHANNEL_ITEM = 'ytm-compact-channel-renderer, ytm-channel-renderer, ytm-rich-item-renderer, ' +
        'ytm-video-with-context-renderer, ytmusic-responsive-list-item-renderer, ytmusic-two-row-item-renderer, ' +
        'ytm-slim-owner-renderer, ytm-video-owner-renderer';
    // Where Subscribe buttons are looked for to show their state (each heartbeat, on screen).
    var SUBSCRIBE_SCAN = (SUBSCRIBE_HOSTS + ', ytm-slim-owner-renderer, ytm-video-owner-renderer, ' +
        'ytm-c4-tabbed-header-renderer, ytm-page-header-renderer, ytm-compact-channel-renderer, ytm-channel-renderer')
        .split(', ').map(function (h) { return h + ' button, ' + h + ' [role="button"]'; }).join(', ') +
        ', button[aria-label^="Subscribe"], button[aria-label^="Unsubscribe"]';
    var SUBSCRIBED_CSS = '[data-ss-sub="1"] { background: rgba(127,127,127,0.28) !important; color: inherit !important; }';
    var subCache = {};
    var lastSubscribeTap = 0;

    function channelPath(href) {
        try {
            var u = new URL(href, location.href);
            var m = u.pathname.match(/^\/(channel\/UC[A-Za-z0-9_-]{22}|@[^\/?#]+)/);
            return m ? '/' + m[1] : '';
        } catch (e) {
            return '';
        }
    }

    function idFromPath(path) {
        var m = /^\/channel\/(UC[A-Za-z0-9_-]{22})$/.exec(path || '');
        return m ? m[1] : '';
    }

    function subscribeButtonFor(target) {
        var el = target && target.closest && target.closest('button, [role="button"], yt-button-shape');
        if (!el) return null;
        var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
        var aria = (el.getAttribute('aria-label') || '').trim();
        var textMatch = text.length <= 40 && SUBSCRIBE_TEXT.test(text);
        if (!textMatch && !SUBSCRIBE_ARIA.test(aria)) return null;
        // Outside YouTube's subscribe components only a plain "Subscribe" / "Subscribed" label.
        if (!el.closest(SUBSCRIBE_HOSTS) && !(text.length <= 24 && textMatch)) return null;
        return el;
    }

    // The channel's name in a card or row: the text of one of its channel links, else a heading
    // (the avatar link has no text).
    function channelNameIn(item, btn) {
        var clean = function (t) { return (t || '').replace(/\s+/g, ' ').trim(); };
        var links = item.querySelectorAll(CHANNEL_LINK);
        for (var i = 0; i < links.length; i++) {
            var t = clean(links[i].textContent) || clean(links[i].getAttribute('aria-label'));
            if (t && !btn.contains(links[i]) && t.charAt(0) !== '@') return t.slice(0, 80);
        }
        var heads = item.querySelectorAll('h1, h2, h3, h4, [role="heading"], [class*="headline"], [class*="title"]');
        for (var j = 0; j < heads.length; j++) {
            if (btn.contains(heads[j])) continue;
            var h = clean(heads[j].textContent);
            if (h && h.length <= 80) return h;
        }
        return '';
    }

    function channelFromInitialData() {
        try {
            var d = window.ytInitialData;
            if (typeof d === 'string') d = JSON.parse(d);
            var m = d && d.metadata && d.metadata.channelMetadataRenderer;
            if (m && m.externalId) return { id: String(m.externalId), name: String(m.title || '') };
        } catch (e) {}
        return null;
    }

    var LIST_ITEM = 'ytm-compact-channel-renderer, ytm-channel-renderer, ytm-rich-item-renderer, ' +
        'ytm-video-with-context-renderer, ytmusic-responsive-list-item-renderer, ytmusic-two-row-item-renderer';

    function channelForButton(btn) {
        var inListItem = !!btn.closest(LIST_ITEM);
        // Video page (and YouTube Music's player page): the current video's channel, from the
        // player response (exact id).
        var onVideo = location.pathname === '/watch' || /^\/shorts\//.test(location.pathname);
        if (!isBrowsePage() && onVideo && !inListItem && details && details.channelId &&
            (details.videoId === currentVideoId() || location.hostname === 'music.youtube.com')) {
            return { id: details.channelId, path: '/channel/' + details.channelId, name: details.author };
        }
        // A channel card, row or owner line with its own link (search results, lists, video page).
        var item = btn.closest(CHANNEL_ITEM);
        var link = item && item.querySelector(CHANNEL_LINK);
        if (link) {
            var lp = channelPath(link.getAttribute('href'));
            if (lp) return { id: idFromPath(lp), path: lp, name: channelNameIn(item, btn) };
        }
        // Channel page (also a YouTube Music artist page, whatever is playing).
        var here = channelPath(location.href);
        if (here) {
            var known = pageChannel && (pageChannel.path === here || '/channel/' + pageChannel.id === here) ? pageChannel : null;
            if (!known) known = channelFromInitialData();
            return {
                id: (known && known.id) || idFromPath(here),
                path: here,
                name: (known && known.name) || (document.title || '').replace(/ - YouTube( Music)?$/, '')
            };
        }
        // Anywhere else (e.g. a Short's overlay): the nearest channel link around the button.
        var box = btn.parentElement;
        // (m.youtube's subscribe button sits about 8 levels below the row with the avatar link.)
        for (var depth = 0; box && box !== document.body && depth < 12; depth++, box = box.parentElement) {
            var near = box.querySelector(CHANNEL_LINK);
            var np = near && channelPath(near.getAttribute('href'));
            if (np) return { id: idFromPath(np), path: np, name: channelNameIn(box, btn) };
        }
        return null;
    }

    function subscribedState(ch) {
        var key = ch.id || ch.path;
        if (!key || !bridge || typeof bridge.isSubscribed !== 'function') return null;
        var cached = subCache[key];
        if (cached && Date.now() - cached.at < 4000) return cached.value;
        var value = null;
        try { value = !!bridge.isSubscribed(JSON.stringify({ id: ch.id || '', path: ch.path || '' })); } catch (e) {}
        subCache[key] = { value: value, at: Date.now() };
        return value;
    }

    function showSubscribed(btn, subscribed) {
        var walker = document.createTreeWalker(btn, NodeFilter.SHOW_TEXT, null);
        var node;
        while ((node = walker.nextNode())) {
            var v = node.nodeValue || '';
            if (SUBSCRIBE_TEXT.test(v.trim())) {
                var label = subscribed ? 'Subscribed' : 'Subscribe';
                var next = v.replace(/subscribed?/i, label);
                if (next !== v) node.nodeValue = next;
                break;
            }
        }
        var flag = subscribed ? '1' : '0';
        if (btn.getAttribute('data-ss-sub') !== flag) btn.setAttribute('data-ss-sub', flag);
    }

    function markSubscribeButtons() {
        if (!document.body) return;
        ensureStyle('streamshield-subscribe', SUBSCRIBED_CSS, true);
        var list = document.querySelectorAll(SUBSCRIBE_SCAN);
        for (var i = 0; i < list.length && i < 12; i++) {
            var btn = subscribeButtonFor(list[i]);
            if (!btn) continue;
            var ch = channelForButton(btn);
            if (!ch) continue;
            var state = subscribedState(ch);
            if (state !== null) showSubscribed(btn, state);
        }
    }

    function onSubscribeEvent(e) {
        var btn = subscribeButtonFor(e.target);
        if (!btn) return;
        // YouTube's own handlers (they open its sign-in prompt) never see this tap.
        e.stopImmediatePropagation();
        if (e.type !== 'click') return;
        e.preventDefault();
        if (Date.now() - lastSubscribeTap < 600) return;
        lastSubscribeTap = Date.now();
        var ch = channelForButton(btn) || { id: '', path: '', name: '' };
        subCache = {};
        try {
            // For logcat when a channel cannot be identified: the button's surroundings.
            var trail = [];
            for (var n = btn, d = 0; n && n !== document.body && d < 8; n = n.parentElement, d++) {
                trail.push(n.tagName.toLowerCase() + (n.className && typeof n.className === 'string' ? '.' + n.className.trim().split(/\s+/)[0] : ''));
            }
            console.info('[StreamShield] subscribe tap ' + JSON.stringify(ch) + ' in ' + trail.join(' < '));
        } catch (err) {}
        report('onSubscribeTap', JSON.stringify({ id: ch.id || '', path: ch.path || '', name: ch.name || '' }));
    }

    ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'touchstart', 'touchend', 'click'].forEach(function (type) {
        window.addEventListener(type, onSubscribeEvent, true);
    });

    // ---------------------------------------------------------------------------------------
    // 9. Like / Dislike on a video page: kept by the app (there is no Google account)
    // ---------------------------------------------------------------------------------------
    // A tap on YouTube's Like or Dislike for the video (not a comment's) goes to the app instead
    // of YouTube's sign-in prompt, and the buttons show the app's state (aria-pressed, a tint).
    var LIKE_HOSTS = 'like-button-view-model, dislike-button-view-model, segmented-like-dislike-button-view-model, ' +
        'ytm-like-button-renderer, ytm-segmented-like-dislike-button-renderer, ytm-slim-video-action-bar-renderer';
    var LIKE_SCAN = LIKE_HOSTS.split(', ').map(function (h) { return h + ' button'; }).join(', ') +
        ', button[aria-label*="like this video" i]';
    var LIKE_LABEL = /^(i )?like this video\b|^like$/i;
    var DISLIKE_LABEL = /^(i )?dislike this video\b|^dislike$/i;
    var COMMENT_AREA = 'ytm-comment-renderer, ytm-comment-thread-renderer, ytm-comments-entry-point-header-renderer, ' +
        'ytm-engagement-panel-section-list-renderer, ytm-comment-action-buttons-renderer';
    var LIKED_CSS = '[data-ss-like="1"], [data-ss-like="1"] * { color: #3ea6ff !important; fill: #3ea6ff !important; }';
    var likeCache = { id: '', value: null, at: 0 };
    var lastLikeTap = 0;

    function likeKindOf(btn) {
        var label = (btn.getAttribute('aria-label') || btn.getAttribute('title') || '').trim();
        if (DISLIKE_LABEL.test(label)) return 'dislike';
        if (LIKE_LABEL.test(label)) return 'like';
        // Unlabelled buttons inside YouTube's own like / dislike components.
        if (btn.closest('dislike-button-view-model')) return 'dislike';
        if (btn.closest('like-button-view-model')) return 'like';
        return '';
    }

    function likeButtonFor(target) {
        if (isBrowsePage() || !isMobileYouTube) return null;
        if (location.pathname !== '/watch' && !/^\/shorts\//.test(location.pathname)) return null;
        var btn = target && target.closest && target.closest('button, [role="button"]');
        if (!btn || btn.closest(COMMENT_AREA)) return null;
        var kind = likeKindOf(btn);
        if (!kind) return null;
        var label = (btn.getAttribute('aria-label') || '').trim();
        if (!/this video/i.test(label) && !btn.closest(LIKE_HOSTS)) return null;
        return { btn: btn, kind: kind };
    }

    /** 'like', 'dislike', '' (neither) or null (unknown) for the video, from the app. */
    function likeState(videoId) {
        if (!videoId || !bridge || typeof bridge.likeState !== 'function') return null;
        if (likeCache.id === videoId && Date.now() - likeCache.at < 4000) return likeCache.value;
        var value = null;
        try { value = String(bridge.likeState(videoId) || ''); } catch (e) {}
        likeCache = { id: videoId, value: value, at: Date.now() };
        return value;
    }

    function markLikeButtons() {
        if (!document.body || !isMobileYouTube || isBrowsePage()) return;
        var id = currentVideoId();
        var state = id ? likeState(id) : null;
        if (state === null) return;
        ensureStyle('streamshield-like', LIKED_CSS, true);
        var list = document.querySelectorAll(LIKE_SCAN);
        for (var i = 0; i < list.length && i < 12; i++) {
            var found = likeButtonFor(list[i]);
            if (!found) continue;
            var on = found.kind === state;
            if (found.btn.getAttribute('data-ss-like') !== (on ? '1' : '0')) found.btn.setAttribute('data-ss-like', on ? '1' : '0');
            if (found.btn.getAttribute('aria-pressed') !== String(on)) found.btn.setAttribute('aria-pressed', String(on));
        }
    }

    function onLikeEvent(e) {
        var found = likeButtonFor(e.target);
        if (!found) return;
        // YouTube's own handlers (they open its sign-in prompt) never see this tap.
        e.stopImmediatePropagation();
        if (e.type !== 'click') return;
        e.preventDefault();
        if (Date.now() - lastLikeTap < 600) return;
        lastLikeTap = Date.now();
        var id = currentVideoId();
        if (!id) return;
        likeCache = { id: '', value: null, at: 0 };
        report('onLikeTap', JSON.stringify({ videoId: id, kind: found.kind }));
    }

    ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'touchstart', 'touchend', 'click'].forEach(function (type) {
        window.addEventListener(type, onLikeEvent, true);
    });

    // ---------------------------------------------------------------------------------------
    // 8. Browse page: a short fade while YouTube swaps the page (search -> channel, Back, ...)
    // ---------------------------------------------------------------------------------------
    var FADE_CSS = 'html.ss-nav body { opacity: 0.55 !important; transition: opacity 120ms ease-out !important; }\n' +
        'body { transition: opacity 180ms ease-in; }';
    var fadeTimer = null;

    function startNavFade() {
        if (!isBrowsePage() || !document.documentElement || !document.body) return;
        ensureStyle('streamshield-fade', FADE_CSS, true);
        document.documentElement.classList.add('ss-nav');
        clearTimeout(fadeTimer);
        fadeTimer = setTimeout(endNavFade, 2500);
    }

    function endNavFade() {
        clearTimeout(fadeTimer);
        fadeTimer = null;
        if (document.documentElement) document.documentElement.classList.remove('ss-nav');
    }

    // ---- YouTube Music: resume a song after the app reloaded the page -----------------------
    var pendingSeek = null;

    function applyPendingSeek() {
        if (!pendingSeek) return;
        if (Date.now() > pendingSeek.until) { pendingSeek = null; return; }
        var v = findVideo();
        var id = currentVideoId() || (details && details.videoId) || '';
        if (!v || id !== pendingSeek.id || v.readyState < 1) return;
        try { v.currentTime = pendingSeek.pos; } catch (e) {}
        pendingSeek = null;
    }

    if (document.readyState === 'loading') {
        // Styles as soon as <head> exists (avoids ad slots flashing in), the rest once parsed.
        if (document.head) {
            applyStyles();
        } else if (typeof MutationObserver === 'function') {
            var headWatcher = new MutationObserver(function () {
                if (document.head) {
                    headWatcher.disconnect();
                    applyStyles();
                }
            });
            headWatcher.observe(document, { childList: true, subtree: true });
        }
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }

    // ---------------------------------------------------------------------------------------
    // 5. Native -> page API
    // ---------------------------------------------------------------------------------------
    function nudgeLayout() {
        // YouTube sizes the player from window size in JS; after PiP / fullscreen transitions the
        // WebView settles over a few frames, so re-dispatch resize until it has.
        [0, 120, 300, 600, 1000].forEach(function (delay) {
            setTimeout(function () { window.dispatchEvent(new Event('resize')); }, delay);
        });
    }

    window.__streamShield = {
        version: 2,
        internals: internals,
        setConfig: function (next) {
            var wasPip = config.pip;
            var wasAdBlock = config.adBlock;
            var wasForeground = inForeground();
            for (var key in next) {
                if (Object.prototype.hasOwnProperty.call(next, key)) config[key] = next[key];
            }
            applyStyles();
            // Back on screen: tidy the page now rather than at the next (slow) heartbeat.
            if (!wasForeground && inForeground() && document.body) tick();
            if (wasAdBlock !== config.adBlock) onAdStateChanged();
            applySpeed();
            if (wasPip !== config.pip) {
                if (config.pip) {
                    var v = findVideo();
                    if (v && v.paused && !v.ended && !adActive && !userPaused) resumeVideo(v);
                    // Backstop for pauses that do not fire a 'pause' event (cheap; PiP only).
                    if (!pipKeeper) {
                        pipKeeper = setInterval(function () {
                            var pv = findVideo();
                            if (config.pip && !userPaused && pv && pv.paused && !pv.ended && pv.readyState >= 2) {
                                resumeVideo(pv);
                            }
                        }, 500);
                    }
                } else if (pipKeeper) {
                    clearInterval(pipKeeper);
                    pipKeeper = null;
                }
                nudgeLayout();
            }
        },
        getConfig: function () { return JSON.parse(JSON.stringify(config)); },
        setSponsorSegments: function (videoId, segments) {
            sponsor = { videoId: String(videoId || ''), segments: Array.isArray(segments) ? segments : [] };
        },
        play: function () {
            userPaused = false;
            resumeVideo(findVideo());
        },
        pause: function () {
            userPaused = true;
            cancelAutoAdvance();
            clearAdvanceFlag();
            var v = findVideo();
            if (v) pauseVideo(v);
        },
        togglePlay: function () {
            var v = findVideo();
            if (!v) return;
            if (v.paused) {
                userPaused = false;
                resumeVideo(v);
            } else {
                userPaused = true;
                cancelAutoAdvance();
                clearAdvanceFlag();
                pauseVideo(v);
            }
        },
        exitFullscreen: function () {
            try {
                if (document.fullscreenElement) document.exitFullscreen();
                else if (document.webkitFullscreenElement) document.webkitExitFullscreen();
            } catch (e) {}
        },
        nudgeLayout: nudgeLayout,
        next: next,
        previous: previous,
        reportState: function () { reportState(true); },
        // The app changed a subscription: show the new state on the page's buttons.
        refreshSubscribeButtons: function () {
            subCache = {};
            markSubscribeButtons();
        },
        // The app's likes / dislikes changed: show the new state on the page's buttons.
        refreshLikeButtons: function () {
            likeCache = { id: '', value: null, at: 0 };
            markLikeButtons();
        },
        // YouTube Music after the app reloaded the page: play the song that was playing, from
        // where it was, through the player's own API (the page stays where it is). Reports
        // 'ok' once it plays, otherwise 'fail' (the app then opens the song's page).
        resumeMusic: function (videoId, pos) {
            var id = String(videoId || '');
            var start = Number(pos) || 0;
            var tries = 0;
            var called = false;
            (function attempt() {
                var p = findPlayer();
                if (!called && p && typeof p.loadVideoById === 'function') {
                    called = true;
                    userPaused = false;
                    try { p.loadVideoById({ videoId: id, startSeconds: start }); } catch (e) {
                        try { p.loadVideoById(id, start); } catch (e2) {}
                    }
                }
                var v = findVideo();
                var now = currentVideoId() || (details && details.videoId) || '';
                if (called && v && !v.paused && now === id) { report('onResumeResult', 'ok'); return; }
                if (++tries < 24) setTimeout(attempt, 250); else report('onResumeResult', 'fail');
            })();
        },
        // Seek to [pos] once the video [videoId] has loaded (within 20 s).
        seekWhenReady: function (videoId, pos) {
            pendingSeek = { id: String(videoId || ''), pos: Number(pos) || 0, until: Date.now() + 20000 };
            applyPendingSeek();
        }
    };
})();
