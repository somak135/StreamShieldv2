// Usage: node cdp.mjs <file.js | -e code>
// Evaluates an async function body in the app's YouTube page through raw CDP (Android WebView
// does not support Playwright's connectOverCDP) and prints the JSON result.
import { readFileSync } from 'node:fs';
const body = process.argv[2] === '-e' ? process.argv[3] : readFileSync(process.argv[2], 'utf8');
const targets = await (await fetch('http://localhost:9222/json')).json();
const t = targets.find((x) => x.type === 'page' && x.url.includes('youtube.com')) || targets.find((x) => x.type === 'page');
if (!t) { console.log('no page'); process.exit(2); }
const ws = new WebSocket(t.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id !== 1) return;
  const r = d.result;
  if (r.exceptionDetails) console.log('EXCEPTION', JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails));
  else console.log(JSON.stringify(r.result.value, null, 1));
  ws.close(); process.exit(0);
};
ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: {
  expression: `(async () => {${body}})()`, awaitPromise: true, returnByValue: true, userGesture: true } }));
