// Determine the minimum Gradle version an AGP release declares, by reading the published
// Gradle module metadata for the plugin marker. This avoids guessing from release notes.
const BASE = 'https://maven.aliyun.com/repository/google/';

function get(url) {
  return new Promise((resolve) => {
    import('node:https').then(({ default: https }) => {
      const req = https.get(url, { timeout: 25000 }, (res) => {
        let d = '';
        res.on('data', (c) => (d += c));
        res.on('end', () => resolve({ status: res.statusCode, body: d }));
      });
      req.on('error', (e) => resolve({ status: 0, body: '', err: e.code || e.message }));
      req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: '', err: 'timeout' }); });
    });
  });
}

const versions = process.argv.slice(2);
for (const v of versions) {
  const path = `com/android/tools/build/gradle/${v}/gradle-${v}.module`;
  const { status, body } = await get(BASE + path);
  console.log(`\n=== AGP ${v} (HTTP ${status}) ===`);
  if (status !== 200) continue;
  // Look for anything that mentions a Gradle version requirement.
  const hits = body.match(/"group"\s*:\s*"org\.gradle[^}]*\}/g) || [];
  for (const h of hits) console.log('  ', h.replace(/\s+/g, ' ').slice(0, 300));
  const verHits = body.match(/"(requires|prefers|strictly)"\s*:\s*"[^"]*"/g) || [];
  const gradleish = verHits.filter((x) => /gradle/i.test(x));
  if (gradleish.length) console.log('   version constraints:', gradleish.join(', '));
  const kv = body.match(/"org\.gradle\.plugin\.api\.version"[^,]*/g);
  if (kv) console.log('   api version:', kv.join(' '));
}
