// node ump.mjs <seconds>: attaches to the page, records every googlevideo videoplayback exchange
// (time, method, request body size, response size) and decodes small UMP responses
// (varint part type, varint size, payload; payload printed as protobuf fields).
const secs = Number(process.argv[2] || 30);
const targets = await (await fetch('http://localhost:9222/json')).json();
const t = targets.find((x) => x.type === 'page' && x.url.includes('youtube.com'));
const ws = new WebSocket(t.webSocketDebuggerUrl);
await new Promise((r) => { ws.onopen = r; });
let id = 0; const pending = new Map(); const reqs = new Map(); const t0 = Date.now();
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
function varint(b, i) { let v = 0, s = 0, x; do { x = b[i++]; v += (x & 0x7f) * 2 ** s; s += 7; } while (x & 0x80); return [v, i]; }
function umpVarint(b, i) { // UMP uses its own length-prefixed varint
  const f = b[i]; let n = f < 128 ? 1 : f < 192 ? 2 : f < 224 ? 3 : f < 240 ? 4 : 5;
  if (n === 1) return [f, i + 1];
  if (n === 2) return [(f & 0x3f) + 64 * b[i + 1], i + 2];
  if (n === 3) return [(f & 0x1f) + 32 * (b[i + 1] + 256 * b[i + 2]), i + 3];
  if (n === 4) return [(f & 0x0f) + 16 * (b[i + 1] + 256 * (b[i + 2] + 256 * b[i + 3])), i + 4];
  return [b[i + 1] + 256 * (b[i + 2] + 256 * (b[i + 3] + 256 * b[i + 4])), i + 5];
}
function proto(b, depth = 0) {
  const out = []; let i = 0;
  try {
    while (i < b.length) {
      let key; [key, i] = varint(b, i); const f = Math.floor(key / 8), w = key & 7;
      if (w === 0) { let v; [v, i] = varint(b, i); out.push(f + '=' + v); }
      else if (w === 2) { let n; [n, i] = varint(b, i); const sub = b.slice(i, i + n); i += n;
        const txt = Buffer.from(sub).toString('utf8'); out.push(f + ':' + (/^[\x20-\x7e]*$/.test(txt) && n ? JSON.stringify(txt.slice(0, 60)) : depth < 2 ? '{' + proto(sub, depth + 1) + '}' : n + 'B')); }
      else if (w === 5) { i += 4; out.push(f + '=f32'); } else if (w === 1) { i += 8; out.push(f + '=f64'); } else return out.join(' ') + ' ?';
    }
  } catch { out.push('!'); }
  return out.join(' ');
}
function ump(b) { const parts = []; let i = 0; while (i < b.length) { let ty, sz; [ty, i] = umpVarint(b, i); [sz, i] = umpVarint(b, i); const p = b.slice(i, i + sz); i += sz; parts.push('type' + ty + '(' + sz + 'B)' + (sz < 400 && ty !== 21 ? ' ' + proto(p) : '')); } return parts.join('\n      '); }
ws.onmessage = async (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); return; }
  const p = d.params;
  if (d.method === 'Network.requestWillBeSent' && /videoplayback/.test(p.request.url)) {
    reqs.set(p.requestId, { start: Date.now(), method: p.request.method, post: p.request.postData ? p.request.postData.length : (p.request.hasPostData ? 'yes' : 0), rn: new URL(p.request.url).searchParams.get('rn') });
  } else if (d.method === 'Network.loadingFinished' && reqs.has(p.requestId)) {
    const r = reqs.get(p.requestId);
    const body = await send('Network.getResponseBody', { requestId: p.requestId });
    const buf = body.result ? Buffer.from(body.result.body, body.result.base64Encoded ? 'base64' : 'utf8') : Buffer.alloc(0);
    console.log(`${((r.start - t0) / 1000).toFixed(2)}s -> ${((Date.now() - t0) / 1000).toFixed(2)}s ${r.method} rn=${r.rn} post=${r.post} resp=${buf.length}B`);
    if (buf.length && buf.length < 5000) console.log('      ' + ump(buf));
    else if (buf.length) { const types = []; let i = 0; while (i < buf.length && types.length < 12) { let ty, sz; [ty, i] = umpVarint(buf, i); [sz, i] = umpVarint(buf, i); types.push(ty + (ty !== 21 && sz < 300 ? '{' + proto(buf.slice(i, i + sz)) + '}' : '(' + sz + ')')); i += sz; } console.log('      parts: ' + types.join(' ')); }
  }
};
await send('Network.enable', { maxResourceBufferSize: 20e6, maxTotalBufferSize: 100e6 });
console.log('t0 attached');
setTimeout(() => process.exit(0), secs * 1000);
