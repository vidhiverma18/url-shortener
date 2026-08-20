#!/usr/bin/env node
/*
 * Regenerates the screenshots in docs/03-demo-walkthrough.md by driving the demo console in
 * headless Chrome over the DevTools Protocol.
 *
 * Documentation screenshots rot silently: the UI changes, the images do not, and nobody
 * notices until a reviewer is looking at a picture of something that no longer exists. Making
 * them reproducible is the only way that stays honest.
 *
 * No dependencies — Node 22+ has a global WebSocket, which is the only thing CDP needs.
 *
 *   docker compose up -d && mvn spring-boot:run     # app on :8080
 *   node scripts/capture-console.mjs
 *
 * The run also fails loudly on any page exception or CSP violation, so it doubles as a smoke
 * test that the console works under the production Content Security Policy.
 */

import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE_URL = process.env.CONSOLE_URL ?? 'http://localhost:8080';
const OUT_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'docs', 'images');
const PORT = 9222;

// Rendered at this scale rather than captured at 1x and upscaled, so text stays natively
// crisp. 2 looks marginally better and costs roughly twice the bytes in the repository for a
// difference nobody notices in a document.
const SCALE = 1.5;

const CHROME_CANDIDATES = [
  process.env.CHROME,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ── Chrome ───────────────────────────────────────────────────────────────── */

async function launchChrome() {
  const { existsSync } = await import('node:fs');
  const binary = CHROME_CANDIDATES.find((path) => existsSync(path));
  if (!binary) throw new Error('No Chrome found. Set CHROME to its path.');

  const chrome = spawn(binary, [
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--window-size=1560,1180',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=/tmp/console-capture-profile-${process.pid}`,
    BASE_URL,
  ], { stdio: 'ignore' });

  // The first entries in /json/list are extension background pages, not the tab — connecting
  // to one of those looks exactly like a hung browser.
  for (let attempt = 0; attempt < 40; attempt++) {
    await sleep(500);
    try {
      const targets = await (await fetch(`http://localhost:${PORT}/json/list`)).json();
      const page = targets.find((t) => t.type === 'page' && t.url.includes(new URL(BASE_URL).host));
      if (page) return { chrome, wsUrl: page.webSocketDebuggerUrl };
    } catch {
      // Chrome has not opened the port yet.
    }
  }
  throw new Error('Chrome did not expose a page target for ' + BASE_URL);
}

/* ── CDP ──────────────────────────────────────────────────────────────────── */

function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  const pending = new Map();
  const problems = [];
  let nextId = 1;

  ws.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve: ok, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : ok(msg.result);
      return;
    }
    if (msg.method === 'Log.entryAdded') {
      const { level, source, text } = msg.params.entry;
      // Refusals are the point of several scenarios, so a 4xx in the network log is a pass,
      // not a problem. Anything from the security source is a CSP violation and is not.
      const expected = source === 'network' && /status of (4\d\d|410)/.test(text);
      if (level === 'error' && !expected) problems.push(`[${source}] ${text}`);
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      problems.push(`[exception] ${msg.params.exceptionDetails.text}`);
    }
  });

  const send = (method, params = {}) =>
    new Promise((ok, reject) => {
      const id = nextId++;
      pending.set(id, { resolve: ok, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });

  const ready = new Promise((ok, reject) => {
    ws.addEventListener('open', ok);
    ws.addEventListener('error', () => reject(new Error('websocket failed')));
  });

  return { ws, send, ready, problems };
}

/* ── Capture ──────────────────────────────────────────────────────────────── */

async function main() {
  mkdirSync(OUT_DIR, { recursive: true });
  const { chrome, wsUrl } = await launchChrome();
  const { ws, send, ready, problems } = connect(wsUrl);
  await ready;

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride',
    { width: 1560, height: 1180, deviceScaleFactor: SCALE, mobile: false });

  const evaluate = async (expression, label = expression) => {
    const { result, exceptionDetails } =
      await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (exceptionDetails) {
      // Naming the step matters: a bare "Uncaught" from a missing selector tells you nothing
      // about which of thirty interactions went wrong.
      problems.push(`[eval] ${label}: ${exceptionDetails.exception?.description ?? exceptionDetails.text}`);
    }
    return result?.value;
  };

  const click = async (selector, settle = 1200) => {
    const found = await evaluate(`(() => {
      const node = document.querySelector(${JSON.stringify(selector)});
      if (!node) return false;
      node.click();
      return true;
    })()`, `click ${selector}`);
    if (!found) problems.push(`[missing] no element matched ${selector}`);
    await sleep(settle);
  };

  // The wire and audit panels scroll internally, so anything past their fold is simply not
  // painted and a clip of them comes out half black. Lifted for close-ups, restored for the
  // full-page shot where the capped height is what a visitor actually sees.
  const unclip = (on) => evaluate(`
    for (const node of document.querySelectorAll('#wire, .audit')) {
      node.style.maxHeight = ${on ? "'none'" : "''"};
    }`);

  const shot = async (name, selector) => {
    const params = { format: 'png', captureBeyondViewport: true };
    if (selector) {
      const rect = await evaluate(`(() => {
        const node = document.querySelector(${JSON.stringify(selector)});
        const r = node.getBoundingClientRect();
        return { x: r.x + scrollX, y: r.y + scrollY, width: r.width, height: r.height };
      })()`);
      const pad = 14;
      params.clip = {
        x: Math.max(0, rect.x - pad),
        y: Math.max(0, rect.y - pad),
        width: rect.width + pad * 2,
        height: rect.height + pad * 2,
        scale: SCALE,
      };
    }
    const { data } = await send('Page.captureScreenshot', params);
    writeFileSync(`${OUT_DIR}/${name}.png`, Buffer.from(data, 'base64'));
    console.log('captured', name);
  };

  // A stale link list from a previous run would make the first screenshot a lie.
  await evaluate('localStorage.clear()');
  await send('Page.navigate', { url: BASE_URL });
  await sleep(1800);

  await shot('console-signed-out');

  await click('[data-login="admin"]');
  await shot('console-session', '#sessionDetail');

  // Cleared before each scenario so every screenshot shows exactly one flow rather than a
  // pile of unrelated history.
  const clearWire = () => click('#clearWire', 200);

  await clearWire();
  await click('.scenario[data-scenario="dedup"]', 2200);
  await unclip(true);
  await shot('console-dedup', '#wire');

  // Expand the newest exchange so the response headers every API call carries are visible.
  await click('.wire .entry:nth-child(2) .entry-head', 400);
  await shot('console-headers', '.wire .entry:nth-child(2)');
  await click('.wire .entry:nth-child(2) .entry-head', 300);

  await clearWire();
  await click('.scenario[data-scenario="blocked"]', 2200);
  await click('.wire .entry:nth-child(2) .entry-head', 400);
  await shot('console-blocked', '#wire');
  await click('.wire .entry:nth-child(2) .entry-head', 300);

  await clearWire();
  await click('.scenario[data-scenario="retired"]', 2400);
  await shot('console-retired', '#wire');
  await unclip(false);

  // Analytics need a moment: clicks are buffered and flushed about once a second.
  await evaluate(`document.getElementById('url').value = 'https://example.com/analytics-demo-${Date.now()}'`);
  await click('#createBtn', 1500);
  await click('#links .link:first-child .link-actions button:nth-child(2)', 3000);
  await click('#links .link:first-child .link-actions button:nth-child(3)', 1500);
  await shot('console-analytics', '#statsCard');

  await clearWire();
  await click('.scenario[data-scenario="quarantine"]', 3200);
  await unclip(true);
  await shot('console-quarantine', '#wire');

  await click('#auditBtn', 1500);
  await shot('console-audit', '#adminCard');
  await unclip(false);

  await shot('console-full');

  // Last, because it drains the rate-limit bucket for this principal. Left capped: 26
  // exchanges unrolled would be a screenshot nobody reads, and the top of the panel already
  // shows the verdict and the mix of 201s and 429s.
  await clearWire();
  await click('.scenario[data-scenario="ratelimit"]', 3500);
  await shot('console-ratelimit', '#wire');

  // Very last: revocation signs the session out.
  await clearWire();
  await click('.scenario[data-scenario="revoked"]', 2500);
  await unclip(true);
  await shot('console-revoked', '#wire');
  await unclip(false);

  ws.close();
  chrome.kill();

  if (problems.length) {
    console.error('\nPage problems detected:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log('\nNo page exceptions or CSP violations.');
  process.exit(0);
}

main().catch((error) => { console.error(error); process.exit(1); });
