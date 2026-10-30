// Background CDP monitor for the app's WebView: installs init.js at document start and logs
// interesting network requests and console lines as JSON lines to stdout.
// Reconnects when the page target goes away (app restart, new WebView).
import { readFileSync } from 'node:fs';
const init = readFileSync(new URL('./init.js', import.meta.url), 'utf8');
const INTERESTING = process.env.ALL ? /^https?:/ : /googlevideo\.com\/videoplayback|\/youtubei\/v1\/(player|next|reel|browse)|pagead|doubleclick|\/api\/stats\/(ads|qoe|watchtime)|get_midroll|ptracking|ad_status|googlesyndication|youtube\.com\/watch|\/generate_204/;
const log = (o) => console.log(JSON.stringify({ t: Date.now(), ...o }));
async function session() {
  let targets;
  try { targets = await (await fetch('http://localhost:9222/json')).json(); } catch { return; }
  const t = targets.find((x) => x.type === 'page' && x.url.includes('youtube.com'));
  if (!t) return;
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const send = (method, params = {}) => ws.send(JSON.stringify({ id: ++id, method, params }));
  const reqs = new Map();
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    const p = d.params;
    if (d.method === 'Network.requestWillBeSent' && INTERESTING.test(p.request.url)) {
      const u = p.request.url;
      const short = u.replace(/^https?:\/\//, '').slice(0, 160);
      const extra = {};
      if (/videoplayback/.test(u)) {
        const q = new URL(u).searchParams;
        Object.assign(extra, { itag: q.get('itag'), range: q.get('range'), rn: q.get('rn'), ctier: q.get('ctier'), oad: q.get('oad'), ctype: q.get('ctype') });
      }
      reqs.set(p.requestId, { url: short, start: Date.now() });
      log({ ev: 'req', id: p.requestId, url: short, ...extra });
    } else if (d.method === 'Network.loadingFinished' && reqs.has(p.requestId)) {
      const r = reqs.get(p.requestId); reqs.delete(p.requestId);
      log({ ev: 'done', id: p.requestId, ms: Date.now() - r.start, bytes: p.encodedDataLength });
    } else if (d.method === 'Network.loadingFailed' && reqs.has(p.requestId)) {
      const r = reqs.get(p.requestId); reqs.delete(p.requestId);
      log({ ev: 'fail', id: p.requestId, url: r.url, err: p.errorText, blocked: p.blockedReason });
    } else if (d.method === 'Runtime.consoleAPICalled') {
      const text = p.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 300);
      if (/shield|StreamShield|ad/i.test(text)) log({ ev: 'console', text });
    } else if (d.method === 'Page.frameNavigated' && !p.frame.parentId) {
      log({ ev: 'nav', url: p.frame.url });
    }
  };
  send('Page.enable'); send('Network.enable'); send('Runtime.enable');
  send('Page.addScriptToEvaluateOnNewDocument', { source: init });
  send('Runtime.evaluate', { expression: init });
  log({ ev: 'attached', url: t.url });
  await new Promise((r) => { ws.onclose = r; ws.onerror = r; });
  log({ ev: 'detached' });
}
for (;;) { await session(); await new Promise((r) => setTimeout(r, 2000)); }
