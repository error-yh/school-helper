// Bounded local audit. It cannot prove that arbitrary prose contains no personal data.
// Never print the private needles, matched content, or connected-device identifiers.
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const repo = path.resolve(__dirname, '../..');
const workspace = path.resolve(repo, '../..');
const runId = process.argv[2];
if (!/^[A-Za-z0-9_-]{1,80}$/.test(runId || '')) throw Error('Unique audit RunId required');
const offline = process.argv[3] === '--offline';
if (process.argv[3] && !offline) throw Error('Unknown audit option');
const evidence = path.join(repo, 'docs/stage-3/evidence');
const destination = path.join(evidence, runId + '.json');
if (fs.existsSync(destination)) throw Error('Audit evidence already exists');
const hash = bytes => createHash('sha256').update(bytes).digest('hex').toUpperCase();
const needlesFile = path.join(workspace, '.private/stage-3/privacy-needles.json');
let needles = [];
if (fs.existsSync(needlesFile)) {
  needles = JSON.parse(fs.readFileSync(needlesFile, 'utf8').replace(/^\uFEFF/, ''));
  if (!Array.isArray(needles) || needles.some(v => typeof v !== 'string' || v.length < 2)) {
    throw Error('Private audit configuration invalid');
  }
}
const adb = path.join(workspace, '.tools/android-sdk/platform-tools/adb.exe');
let deviceNeedles = [];
if (!offline) try {
  const result = execFileSync(adb, ['devices'], { encoding: 'utf8', windowsHide: true,
    env: { ...process.env, ANDROID_SDK_HOME: path.join(workspace, '.private'),
      ANDROID_USER_HOME: path.join(workspace, '.private/android-user') }, stdio: ['ignore', 'pipe', 'pipe'] });
  deviceNeedles = result.split(/\r?\n/).filter(v => /^\S+\s+device$/.test(v)).map(v => v.split(/\s+/)[0]);
} catch { throw Error('Device identifier audit unavailable; no successful audit recorded'); }
function files(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    if (entry.isSymbolicLink()) throw Error('Unexpected evidence symlink');
    const location = path.join(directory, entry.name);
    return entry.isDirectory() ? files(location) : [location];
  });
}
let scanned = 0, deviceFound = false, configuredNeedleFound = false, privateKeyFound = false, hashes = 0;
for (const file of files(evidence)) {
  if (!/\.(json|xml|log|txt|tsv|csv|md)$/i.test(file)) continue;
  if (fs.statSync(file).size > 32 * 1024 * 1024) throw Error('Evidence text exceeds audit limit');
  const raw = fs.readFileSync(file);
  const body = raw.toString('utf8');
  scanned++;
  deviceFound ||= deviceNeedles.some(v => body.includes(v));
  configuredNeedleFound ||= needles.some(v => body.includes(v));
  privateKeyFound ||= /-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/.test(body);
  if (file.endsWith('.json')) {
    const metadata = JSON.parse(body.replace(/^\uFEFF/, ''));
    if (metadata && !Array.isArray(metadata) && metadata.logSha256) {
      const log = file.slice(0, -5) + '.log';
      if (!fs.existsSync(log) || hash(fs.readFileSync(log)) !== metadata.logSha256.toUpperCase()) {
        throw Error('Evidence log integrity check failed');
      }
      hashes++;
    }
  }
}
const passed = !(deviceFound || configuredNeedleFound || privateKeyFound);
fs.writeFileSync(destination, JSON.stringify({ textFilesScanned: scanned,
  deviceAuditPerformed: !offline,
  connectedIdentifiersChecked: offline ? null : deviceNeedles.length, privateNeedlesConfigured: needles.length,
  deviceIdentifierFound: offline ? null : deviceFound, configuredPrivateNeedleFound: configuredNeedleFound,
  privateKeyFound, logHashesVerified: hashes, passed,
  limitation: (offline ? 'Offline audit: no adb/device access or device-identifier comparison. ' : '') +
    'Exact configured identifiers and private-key headers only; manual source/prose/screenshot review still required.'
}, null, 2) + '\n', { flag: 'wx' });
process.stdout.write(`Evidence audit passed=${passed}; text files=${scanned}; log hashes=${hashes}.\n`);
if (!passed) process.exitCode = 1;
