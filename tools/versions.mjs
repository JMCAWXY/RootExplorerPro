// Query the reachable Maven mirror for the newest stable version of each artifact we plan to use.
// Prints raw evidence, so version pins in the build files are verified rather than guessed.
const HOSTS = [
  'https://maven.aliyun.com/repository/google/',
  'https://maven.aliyun.com/repository/public/',
];

const ARTIFACTS = [
  'com/android/tools/build/gradle',
  'org/jetbrains/kotlin/kotlin-gradle-plugin',
  'org/jetbrains/kotlin/compose-compiler-gradle-plugin',
  'androidx/compose/compose-bom',
  'androidx/core/core-ktx',
  'androidx/activity/activity-compose',
  'androidx/lifecycle/lifecycle-runtime-compose',
  'androidx/lifecycle/lifecycle-viewmodel-compose',
  'androidx/navigation/navigation-compose',
  'androidx/datastore/datastore-preferences',
  'androidx/documentfile/documentfile',
  'androidx/room/room-runtime',
  'com/google/devtools/ksp/symbol-processing-api',
  'org/apache/commons/commons-compress',
  'com/github/junrar/junrar',
  'org/jetbrains/kotlinx/kotlinx-coroutines-android',
];

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

const isStable = (v) =>
  /^\d+(\.\d+)*$/.test(v) && !/(alpha|beta|rc|dev|snapshot|eap|preview|M\d)/i.test(v);

function cmp(a, b) {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] || 0, y = pb[i] || 0;
    if (x !== y) return x - y;
  }
  return 0;
}

for (const art of ARTIFACTS) {
  let out = `${art.padEnd(55)} `;
  let found = false;
  for (const host of HOSTS) {
    const { status, body } = await get(`${host}${art}/maven-metadata.xml`);
    if (status === 200) {
      const all = [...body.matchAll(/<version>([^<]+)<\/version>/g)].map((m) => m[1]);
      const stable = all.filter(isStable).sort(cmp);
      out += `latest_stable=${stable[stable.length - 1] || 'none'}`;
      found = true;
      break;
    }
  }
  if (!found) out += 'NOT-FOUND-ON-MIRROR';
  console.log(out);
}
