// Page-side timeline recorder, installed at document start next to shield.js.
(function () {
  if (window.__probe) return;
  var t0 = Date.now() - Math.round(performance.now());
  var P = window.__probe = { t0: t0, ev: [] };
  function rec(type, extra) {
    var v = document.querySelector('video');
    var e = { t: Date.now(), type: type, vid: (new URL(location.href)).searchParams.get('v') };
    if (v) { e.ct = Math.round(v.currentTime * 10) / 10; e.dur = Math.round(v.duration || 0); }
    if (extra) for (var k in extra) e[k] = extra[k];
    P.ev.push(e);
    if (P.ev.length > 3000) P.ev.splice(0, 1000);
  }
  P.rec = rec;
  ['loadstart', 'loadedmetadata', 'loadeddata', 'canplay', 'playing', 'pause', 'waiting', 'ended', 'seeking', 'seeked', 'emptied', 'stalled', 'ratechange', 'volumechange']
    .forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (e.target && e.target.tagName === 'VIDEO') rec(type, type === 'ratechange' ? { rate: e.target.playbackRate } : type === 'volumechange' ? { muted: e.target.muted } : null);
      }, true);
    });
  var lastAd = '';
  var observed = null;
  setInterval(function () {
    var p = document.getElementById('movie_player');
    if (!p || p === observed) return;
    observed = p;
    new MutationObserver(function () {
      var ad = (p.className.match(/\bad-[a-z-]+/g) || []).join(' ');
      if (ad !== lastAd) { lastAd = ad; rec('adclass', { cls: ad }); }
    }).observe(p, { attributes: true, attributeFilter: ['class'] });
    rec('player-found');
  }, 300);
  ['yt-navigate-start', 'yt-navigate-finish', 'state-navigatestart', 'state-navigateend'].forEach(function (type) {
    window.addEventListener(type, function () { rec(type); });
  });
  rec('init', { url: location.href });
})();
