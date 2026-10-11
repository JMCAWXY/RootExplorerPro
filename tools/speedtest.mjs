// Measure real throughput from candidate Gradle distribution mirrors by reading the first
// few MiB of each URL, so we can pick a fast source instead of waiting on a slow CDN.
import https from 'node:https';

const CANDIDATES = [
  ['github-cdn', 'https://services.gradle.org/distributions/gradle-8.14.3-bin.zip'],
  ['tencent', 'https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip'],
  ['tencent-all', 'https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-all.zip'],
  ['huawei', 'https://repo.huaweicloud.com/gradle/gradle-8.14.3-bin.zip'],
  ['aliyun', 'https://mirrors.aliyun.com/gradle/gradle-8.14.3-bin.zip'],
  ['npm-mirror', 'https://registry.npmmirror.com/-/binary/gradle/v8.14.3/gradle-8.14.3-bin.zip'],
];

const BUDGET_BYTES = 3 * 1024 * 1024;
const TIMEOUT_MS = 15000;

function probe(name, url) {
  return new Promise((resolve) => {
    const started = Date.now();
    let bytes = 0;
    let settled = false;
    const done = (note) => {
      if (settled) return;
      settled = true;
      const secs = (Date.now() - started) / 1000;
      const kbps = secs > 0 ? bytes / 1024 / secs : 0;
      resolve({ name, bytes, secs, kbps, note });
    };
    const req = https.get(url, { timeout: TIMEOUT_MS }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        return done(`HTTP ${res.statusCode}`);
      }
      res.on('data', (c) => {
        bytes += c.length;
        if (bytes >= BUDGET_BYTES) {
          res.destroy();
          done('ok');
        }
      });
      res.on('end', () => done('ended'));
      res.on('error', () => done('stream-error'));
    });
    req.on('error', (e) => done('ERR ' + (e.code || e.message)));
    req.on('timeout', () => { req.destroy(); done('timeout'); });
  });
}

const results = await Promise.all(CANDIDATES.map(([n, u]) => probe(n, u)));
results.sort((a, b) => b.kbps - a.kbps);
for (const r of results) {
  console.log(
    `${r.name.padEnd(14)} ${String(Math.round(r.kbps)).padStart(7)} KiB/s  ` +
    `${(r.bytes / 1048576).toFixed(2)} MiB in ${r.secs.toFixed(1)}s  [${r.note}]`,
  );
}
