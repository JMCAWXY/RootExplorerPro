// Minimal HTTPS downloader used to bootstrap the Android toolchain.
// Node's OpenSSL TLS stack works in this environment where Schannel does not.
// Usage: node fetch.mjs <url> <outFile>
import { createWriteStream, mkdirSync, existsSync, statSync } from 'node:fs';
import { dirname } from 'node:path';
import https from 'node:https';

const [, , url, out] = process.argv;
if (!url || !out) {
  console.error('usage: node fetch.mjs <url> <outFile>');
  process.exit(2);
}

const MAX_REDIRECTS = 10;

function download(target, dest, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > MAX_REDIRECTS) return reject(new Error('too many redirects'));
    const req = https.get(target, { timeout: 60000, headers: { 'User-Agent': 'node-fetch' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        const next = new URL(res.headers.location, target).toString();
        console.error(`redirect -> ${next}`);
        return resolve(download(next, dest, redirects + 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} for ${target}`));
      }
      const total = Number(res.headers['content-length'] || 0);
      let got = 0;
      let lastPct = -5;
      const ws = createWriteStream(dest);
      res.on('data', (c) => {
        got += c.length;
        if (total) {
          const pct = Math.floor((got / total) * 100);
          if (pct >= lastPct + 5) {
            lastPct = pct;
            console.error(`  ${pct}% (${(got / 1048576).toFixed(1)}/${(total / 1048576).toFixed(1)} MiB)`);
          }
        }
      });
      res.pipe(ws);
      ws.on('finish', () => ws.close(() => resolve({ bytes: got, total })));
      ws.on('error', reject);
      res.on('error', reject);
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
  });
}

if (existsSync(out) && statSync(out).size > 0) {
  console.log(`already exists, skipping: ${out} (${statSync(out).size} bytes)`);
  process.exit(0);
}
mkdirSync(dirname(out), { recursive: true });
console.log(`GET ${url}`);
download(url, out)
  .then((r) => {
    console.log(`OK ${out} ${r.bytes} bytes${r.total ? ` / ${r.total}` : ''}`);
  })
  .catch((e) => {
    console.error(`FAIL ${e.message}`);
    process.exit(1);
  });
