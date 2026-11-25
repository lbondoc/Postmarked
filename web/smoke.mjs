// Smoke test for the website. Serves webApp's production build, opens it in Chromium, and fails if the page
// logs a console error, throws, or doesn't show the sign-in screen.
// Run after `./gradlew :webApp:wasmJsBrowserDistribution`:  node web/smoke.mjs
// With --share it opens a share link (#/s/<token>) instead, with the `share` Edge Function and the image links
// stubbed, and fails unless the read-only gallery renders with no sign-in screen.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

// createRequire so a globally installed Playwright (NODE_PATH) is found; this repo has no package.json.
const { chromium } = createRequire(import.meta.url)('playwright');

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, '../webApp/build/dist/wasmJs/productionExecutable');
const screenshot = path.resolve(here, '../webApp/build/smoke.png');

// Messages that aren't ours to fix. Each needs a reason.
const knownNoise = [
  // Skiko (Compose's renderer) reads Kotlin's wasm memory the old way; it goes away when Compose updates Skiko.
  'Accessing `memory` via `wasmExports` is deprecated',
];

const shareMode = process.argv.includes('--share');
const SHARE_TOKEN = 'AbCdEfGhIjKlMnOpQrStUv';
// What the `share` function returns for a live link (see supabase/functions/share), with stub image links.
const sharedTrip = {
  trip: { id: '11111111-1111-1111-1111-111111111111', name: 'Smoke Coast Trip', start_date: '2026-09-12', end_date: '2026-09-13' },
  notes: [{ day: '2026-09-12', body: 'Fog lifted at the bridge' }],
  media: [
    { id: 'p1', kind: 'photo', captured_at: '2026-09-12T15:00:00Z', utc_offset_minutes: -420, lat: 37.8, lng: -122.48,
      profile: { display_name: 'Maya' }, thumb: 'https://images.stub.invalid/p1-thumb.png', display: 'https://images.stub.invalid/p1.png' },
    { id: 'p2', kind: 'photo', captured_at: '2026-09-13T18:00:00Z', utc_offset_minutes: -420, lat: 36.6, lng: -121.9,
      profile: { display_name: 'Jun' }, thumb: 'https://images.stub.invalid/p2-thumb.png', display: 'https://images.stub.invalid/p2.png' },
  ],
};
// A 1 × 1 PNG for every stubbed image.
const PIXEL = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64');
const cors = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'POST, OPTIONS',
};

const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json',
  '.wasm': 'application/wasm', '.ttf': 'font/ttf', '.otf': 'font/otf', '.png': 'image/png', '.svg': 'image/svg+xml',
};

function serve(root) {
  const server = http.createServer((req, res) => {
    const url = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const file = path.join(root, url.endsWith('/') ? `${url}index.html` : url);
    if (!file.startsWith(root)) { res.writeHead(403).end(); return; }
    fs.readFile(file, (err, body) => {
      if (err) { res.writeHead(404).end(); return; }
      res.writeHead(200, { 'content-type': types[path.extname(file)] ?? 'application/octet-stream' }).end(body);
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

if (!fs.existsSync(path.join(dist, 'index.html'))) {
  console.error(`No build at ${dist}. Run ./gradlew :webApp:wasmJsBrowserDistribution first.`);
  process.exit(1);
}

const server = await serve(dist);
const browser = await chromium.launch();
const problems = [];
let ok = false;
try {
  const page = await browser.newPage();
  page.on('console', (m) => {
    if (m.type() === 'error' && !knownNoise.some((n) => m.text().includes(n))) problems.push(`console error: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));

  if (shareMode) {
    await page.route('**/functions/v1/share', (route) => route.request().method() === 'OPTIONS'
      ? route.fulfill({ status: 204, headers: cors })
      : route.fulfill({ status: 200, headers: { ...cors, 'content-type': 'application/json' }, body: JSON.stringify(sharedTrip) }));
    await page.route('https://images.stub.invalid/**', (route) =>
      route.fulfill({ status: 200, headers: { ...cors, 'content-type': 'image/png' }, body: PIXEL }));
    await page.goto(`http://127.0.0.1:${server.address().port}/#/s/${SHARE_TOKEN}`);
    // Compose draws on a canvas; the accessibility tree (isA11YEnabled in Main.kt) is what holds the text.
    await page.getByRole('heading', { name: 'Smoke Coast Trip' }).waitFor({ timeout: 60_000 });
    await page.getByRole('heading', { name: /^Day 1/ }).waitFor({ timeout: 10_000 });
    await page.getByRole('button', { name: /^Photo from/ }).first().waitFor({ timeout: 10_000 });
    if (await page.getByRole('button', { name: 'Sign in' }).count() > 0) throw new Error('the sign-in screen showed on a share link');
  } else {
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    // Compose draws on a canvas; the accessibility tree (isA11YEnabled in Main.kt) is what holds the text.
    await page.getByRole('button', { name: 'Sign in' }).waitFor({ timeout: 60_000 });
  }
  await page.waitForTimeout(1_000); // let late errors arrive
  ok = true;
  await page.screenshot({ path: screenshot });
} catch (e) {
  problems.push(`${shareMode ? 'shared trip' : 'sign-in screen'} didn't render: ${e.message.split('\n')[0]}`);
} finally {
  await browser.close();
  server.close();
}

for (const p of problems) console.error(p);
if (!ok || problems.length > 0) process.exit(1);
console.log(`smoke test passed: ${shareMode ? 'shared trip gallery' : 'sign-in screen'} rendered with no console errors (screenshot: ${screenshot})`);
